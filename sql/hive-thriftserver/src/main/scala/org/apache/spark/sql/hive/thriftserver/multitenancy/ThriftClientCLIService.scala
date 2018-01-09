/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive.thriftserver.multitenancy

import java.net.{InetAddress, UnknownHostException}
import java.util.{ArrayList, HashMap, Map => JMap}
import java.util.concurrent.{SynchronousQueue, ThreadPoolExecutor, TimeUnit}

import scala.util.{Failure, Try}

import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.conf.HiveConf.ConfVars
import org.apache.hive.service.{AbstractService, ServiceException, ServiceUtils}
import org.apache.hive.service.auth.{HiveAuthFactory, TSetIpAddressProcessor}
import org.apache.hive.service.cli._
import org.apache.hive.service.cli.thrift._
import org.apache.hive.service.server.ThreadFactoryWithGarbageCleanup
import org.apache.thrift.protocol.{TBinaryProtocol, TProtocol}
import org.apache.thrift.server.{ServerContext, TServer, TServerEventHandler, TThreadPoolServer}
import org.apache.thrift.transport.{TServerSocket, TTransport}

import org.apache.spark.internal.Logging

class ThriftClientCLIService private(name: String, cliService: ThriftServerCLIService)
  extends AbstractService(name) with TCLIService.Iface with Runnable with Logging {

  private[this] var hiveConf: HiveConf = _
  private[this] var hiveAuthFactory: HiveAuthFactory = _

  private[this] val OK_STATUS = new TStatus(TStatusCode.SUCCESS_STATUS)

  private[this] var serverEventHandler: TServerEventHandler = _
  private[this] var currentServerContext: ThreadLocal[ServerContext] = _

  private[this] var server: Option[TServer] = _
  private[this] var serverHost: String = _
  private[this] var portNum = 0
  private[this] var serverIPAddress: InetAddress = _

  private[this] val threadPoolName = "SparkThriftServer-Handler-Pool"
  private[this] var minWorkerThreads = 0
  private[this] var maxWorkerThreads = 0
  private[this] var workerKeepAliveTime = 0L

  private[this] var isStarted = false

  private[this] var realUser: String = _

  class ThriftClientCLIServerContext extends ServerContext {
    private var sessionHandle: SessionHandle = _

    def setSessionHandle(sessionHandle: SessionHandle): Unit = {
      this.sessionHandle = sessionHandle
    }

    def getSessionHandle: SessionHandle = sessionHandle
  }

  def this(cliService: ThriftServerCLIService) = {
    this(classOf[ThriftClientCLIService].getSimpleName, cliService)
    currentServerContext = new ThreadLocal[ServerContext]()
    serverEventHandler = new ClientTServerEventHandler
  }

  class ClientTServerEventHandler extends TServerEventHandler {
    override def deleteContext(
        serverContext: ServerContext, tProtocol: TProtocol, tProtocol1: TProtocol): Unit = {
      Option(serverContext.asInstanceOf[ThriftClientCLIServerContext]
        .getSessionHandle).foreach { sessionHandle =>
        logWarning(s"Session [$sessionHandle] disconnected without closing properly, " +
          s"close it now")
        Try {cliService.closeSession(sessionHandle)} match {
          case Failure(exception) =>
            logWarning("Failed closing session " + exception, exception)
          case _ =>
        }
      }
    }

    override def processContext(
        serverContext: ServerContext, tTransport: TTransport, tTransport1: TTransport): Unit = {
      currentServerContext.set(serverContext)
    }

    override def preServe(): Unit = ()

    override def createContext(tProtocol: TProtocol, tProtocol1: TProtocol): ServerContext = {
      new ThriftClientCLIServerContext()
    }
  }

  override def init(hiveConf: HiveConf): Unit = synchronized {
    this.hiveConf = hiveConf
    serverHost = sys.env.getOrElse("HIVE_SERVER2_THRIFT_BIND_HOST",
      hiveConf.getVar(ConfVars.HIVE_SERVER2_THRIFT_BIND_HOST))
    try {
      if (serverHost != null && !serverHost.isEmpty) {
        serverIPAddress = InetAddress.getByName(serverHost)
      }
      else {
        serverIPAddress = InetAddress.getLocalHost
      }
    }
    catch {
      case e: UnknownHostException =>
        throw new ServiceException(e)
    }
    minWorkerThreads = hiveConf.getIntVar(ConfVars.HIVE_SERVER2_THRIFT_MIN_WORKER_THREADS)
    maxWorkerThreads = hiveConf.getIntVar(ConfVars.HIVE_SERVER2_THRIFT_MAX_WORKER_THREADS)
    workerKeepAliveTime =
      hiveConf.getTimeVar(ConfVars.HIVE_SERVER2_THRIFT_WORKER_KEEPALIVE_TIME, TimeUnit.SECONDS)
    portNum = sys.env.getOrElse("HIVE_SERVER2_THRIFT_PORT",
      hiveConf.getVar(ConfVars.HIVE_SERVER2_THRIFT_PORT)).toInt
    super.init(hiveConf)
  }

  override def start(): Unit = {
    super.start()
    if (!isStarted) {
      new Thread(this).start()
      isStarted = true
    }
  }

  override def stop(): Unit = {
    if (isStarted) {
      server.foreach { s =>
        s.stop()
        logInfo(this.name + " has stopped")
      }
      isStarted = false
    }
    super.stop()
  }

  def getPortNumber: Int = portNum

  def getServerIPAddress: InetAddress = serverIPAddress

  private[this] def isKerberosAuthMode = {
    cliService.getHiveConf.getVar(ConfVars.HIVE_SERVER2_AUTHENTICATION)
      .equalsIgnoreCase(HiveAuthFactory.AuthTypes.KERBEROS.toString)
  }

  private def getUserName(req: TOpenSessionReq) = {
    // Kerberos
    if (isKerberosAuthMode) {
      realUser = hiveAuthFactory.getRemoteUser
    }
    // Except kerberos, NOSASL
    if (realUser == null) {
      realUser = TSetIpAddressProcessor.getUserName
    }
    if (realUser == null) {
      realUser = req.getUsername
    }
    realUser = getShortName(realUser)
    getProxyUser(req.getConfiguration, getIpAddress)
  }

  private[this] def getShortName(userName: String): String = {
    var ret: String = null
    if (userName != null) {
      val indexOfDomainMatch = ServiceUtils.indexOfDomainMatch(userName)
      ret = if (indexOfDomainMatch <= 0) {
        userName
      } else {
        userName.substring(0, indexOfDomainMatch)
      }
    }
    ret
  }

  @throws[HiveSQLException]
  private[this] def getProxyUser(sessionConf: JMap[String, String], ipAddress: String): String = {
    var proxyUser: String = null
    if (sessionConf != null && sessionConf.containsKey(HiveAuthFactory.HS2_PROXY_USER)) {
      proxyUser = sessionConf.get(HiveAuthFactory.HS2_PROXY_USER)
    }
    if (proxyUser == null) {
      return realUser
    }
    // check whether substitution is allowed
    if (!hiveConf.getBoolVar(HiveConf.ConfVars.HIVE_SERVER2_ALLOW_USER_SUBSTITUTION)) {
      throw new HiveSQLException("Proxy user substitution is not allowed")
    }
    // If there's no authentication, then directly substitute the user
    if (HiveAuthFactory.AuthTypes.NONE.toString
      .equalsIgnoreCase(hiveConf.getVar(ConfVars.HIVE_SERVER2_AUTHENTICATION))) {
      return proxyUser
    }
    // Verify proxy user privilege of the realUser for the proxyUser
    HiveAuthFactory.verifyProxyAccess(realUser, proxyUser, ipAddress, hiveConf)
    proxyUser
  }

  private[this] def getIpAddress: String = {
    if (isKerberosAuthMode) {
      hiveAuthFactory.getIpAddress
    } else {
      TSetIpAddressProcessor.getUserIpAddress
    }
  }

  private[this] def getMinVersion(versions: TProtocolVersion*): TProtocolVersion = {
    val values = TProtocolVersion.values
    var current = values(values.length - 1).getValue
    for (version <- versions) {
      if (current > version.getValue) {
        current = version.getValue
      }
    }
    for (version <- values) {
      if (version.getValue == current) {
        return version
      }
    }
    throw new IllegalArgumentException("never")
  }

  /**
   * Create a session handle
   */
  @throws[HiveSQLException]
  private[this] def getSessionHandle(req: TOpenSessionReq, res: TOpenSessionResp) = {
    val userName = getUserName(req)
    val ipAddress = getIpAddress
    val protocol = getMinVersion(ThriftServerCLIService.SERVER_VERSION, req.getClient_protocol)
    val sessionHandle =
    if (cliService.getHiveConf.
      getBoolVar(ConfVars.HIVE_SERVER2_ENABLE_DOAS) && (userName != null)) {
      cliService.openSessionWithImpersonation(
        protocol, realUser, userName, req.getPassword, ipAddress, req.getConfiguration, null)
    } else {
      cliService.openSession(
        protocol, realUser, userName, req.getPassword, ipAddress, req.getConfiguration)
    }
    res.setServerProtocolVersion(protocol)
    sessionHandle
  }

  override def OpenSession(req: TOpenSessionReq): TOpenSessionResp = {
    logInfo("Client protocol version: " + req.getClient_protocol)
    val resp = new TOpenSessionResp
    try {
      val sessionHandle = getSessionHandle(req, resp)
      resp.setSessionHandle(sessionHandle.toTSessionHandle)
      resp.setConfiguration(new HashMap[String, String])
      resp.setStatus(OK_STATUS)
      val context = currentServerContext.get
        .asInstanceOf[ThriftClientCLIService#ThriftClientCLIServerContext]
      if (context != null) {
        context.setSessionHandle(sessionHandle)
      }
    } catch {
      case e: Exception =>
        logWarning("Error opening session: ", e)
        resp.setStatus(HiveSQLException.toTStatus(e))
    }
    resp

  }

  override def CloseSession(req: TCloseSessionReq): TCloseSessionResp = {
    val resp = new TCloseSessionResp
    try {
      val sessionHandle = new SessionHandle(req.getSessionHandle)
      cliService.closeSession(sessionHandle)
      resp.setStatus(OK_STATUS)
      val context = currentServerContext.get
        .asInstanceOf[ThriftClientCLIServerContext]
      if (context != null) {
        context.setSessionHandle(null)
      }
    } catch {
      case e: Exception =>
        logWarning("Error closing session: ", e)
        resp.setStatus(HiveSQLException.toTStatus(e))
    }
    resp
  }

  override def GetInfo(req: TGetInfoReq): TGetInfoResp = {
    val resp = new TGetInfoResp
    try {
      val getInfoValue = cliService.getInfo(
        new SessionHandle(req.getSessionHandle), GetInfoType.getGetInfoType(req.getInfoType))
      resp.setInfoValue(getInfoValue.toTGetInfoValue)
      resp.setStatus(OK_STATUS)
    } catch {
      case e: Exception =>
        logWarning("Error getting info: ", e)
        resp.setStatus(HiveSQLException.toTStatus(e))
    }
    resp
  }

  override def ExecuteStatement(req: TExecuteStatementReq): TExecuteStatementResp = {
    val resp = new TExecuteStatementResp
    try {
      val sessionHandle = new SessionHandle(req.getSessionHandle)
      val statement = req.getStatement
      val confOverlay = req.getConfOverlay
      val runAsync = req.isRunAsync
      val operationHandle = if (runAsync) {
        cliService.executeStatementAsync(sessionHandle, statement, confOverlay)
      } else {
        cliService.executeStatement(sessionHandle, statement, confOverlay)
      }
      resp.setOperationHandle(operationHandle.toTOperationHandle)
      resp.setStatus(OK_STATUS)
    } catch {
      case e: Exception =>
        logWarning("Error executing statement: ", e)
        resp.setStatus(HiveSQLException.toTStatus(e))
    }
    resp
  }

  override def GetTypeInfo(req: TGetTypeInfoReq): TGetTypeInfoResp = {
    val resp = new TGetTypeInfoResp
    try {
      val operationHandle = cliService.getTypeInfo(new SessionHandle(req.getSessionHandle))
      resp.setOperationHandle(operationHandle.toTOperationHandle)
      resp.setStatus(OK_STATUS)
    } catch {
      case e: Exception =>
        logWarning("Error getting type info: ", e)
        resp.setStatus(HiveSQLException.toTStatus(e))
    }
    resp
  }

  override def GetCatalogs(req: TGetCatalogsReq): TGetCatalogsResp = {
    val resp = new TGetCatalogsResp
    try {
      val opHandle = cliService.getCatalogs(new SessionHandle(req.getSessionHandle))
      resp.setOperationHandle(opHandle.toTOperationHandle)
      resp.setStatus(OK_STATUS)
    } catch {
      case e: Exception =>
        logWarning("Error getting catalogs: ", e)
        resp.setStatus(HiveSQLException.toTStatus(e))
    }
    resp
  }

  override def GetSchemas(req: TGetSchemasReq): TGetSchemasResp = {
    val resp = new TGetSchemasResp
    try {
      val opHandle = cliService.getSchemas(
        new SessionHandle(req.getSessionHandle), req.getCatalogName, req.getSchemaName)
      resp.setOperationHandle(opHandle.toTOperationHandle)
      resp.setStatus(OK_STATUS)
    } catch {
      case e: Exception =>
        logWarning("Error getting schemas: ", e)
        resp.setStatus(HiveSQLException.toTStatus(e))
    }
    resp
  }

  override def GetTables(req: TGetTablesReq): TGetTablesResp = {
    val resp = new TGetTablesResp
    try {
      val opHandle = cliService.getTables(new SessionHandle(req.getSessionHandle),
        req.getCatalogName, req.getSchemaName, req.getTableName, req.getTableTypes)
      resp.setOperationHandle(opHandle.toTOperationHandle)
      resp.setStatus(OK_STATUS)
    } catch {
      case e: Exception =>
        logWarning("Error getting tables: ", e)
        resp.setStatus(HiveSQLException.toTStatus(e))
    }
    resp
  }

  override def GetTableTypes(req: TGetTableTypesReq): TGetTableTypesResp = {
    val resp = new TGetTableTypesResp
    try {
      val opHandle = cliService.getTableTypes(new SessionHandle(req.getSessionHandle))
      resp.setOperationHandle(opHandle.toTOperationHandle)
      resp.setStatus(OK_STATUS)
    } catch {
      case e: Exception =>
        logWarning("Error getting table types: ", e)
        resp.setStatus(HiveSQLException.toTStatus(e))
    }
    resp
  }

  override def GetColumns(req: TGetColumnsReq): TGetColumnsResp = {
    val resp = new TGetColumnsResp
    try {
      val opHandle = cliService.getColumns(
        new SessionHandle(req.getSessionHandle),
        req.getCatalogName, req.getSchemaName, req.getTableName, req.getColumnName)
      resp.setOperationHandle(opHandle.toTOperationHandle)
      resp.setStatus(OK_STATUS)
    } catch {
      case e: Exception =>
        logWarning("Error getting columns: ", e)
        resp.setStatus(HiveSQLException.toTStatus(e))
    }
    resp
  }

  override def GetFunctions(req: TGetFunctionsReq): TGetFunctionsResp = {
    val resp = new TGetFunctionsResp
    try {
      val opHandle = cliService.getFunctions(
        new SessionHandle(req.getSessionHandle),
        req.getCatalogName, req.getSchemaName, req.getFunctionName)
      resp.setOperationHandle(opHandle.toTOperationHandle)
      resp.setStatus(OK_STATUS)
    } catch {
      case e: Exception =>
        logWarning("Error getting functions: ", e)
        resp.setStatus(HiveSQLException.toTStatus(e))
    }
    resp
  }

  override def GetOperationStatus(req: TGetOperationStatusReq): TGetOperationStatusResp = {
    val resp = new TGetOperationStatusResp
    try {
      val operationStatus = cliService.getOperationStatus(
        new OperationHandle(req.getOperationHandle))
      resp.setOperationState(operationStatus.getState.toTOperationState)
      val opException = operationStatus.getOperationException
      if (opException != null) {
        resp.setSqlState(opException.getSQLState)
        resp.setErrorCode(opException.getErrorCode)
        resp.setErrorMessage(opException.getMessage)
      }
      resp.setStatus(OK_STATUS)
    } catch {
      case e: Exception =>
        logWarning("Error getting operation status: ", e)
        resp.setStatus(HiveSQLException.toTStatus(e))
    }
    resp
  }

  override def CancelOperation(req: TCancelOperationReq): TCancelOperationResp = {
    val resp = new TCancelOperationResp
    try {
      cliService.cancelOperation(new OperationHandle(req.getOperationHandle))
      resp.setStatus(OK_STATUS)
    } catch {
      case e: Exception =>
        logWarning("Error cancelling operation: ", e)
        resp.setStatus(HiveSQLException.toTStatus(e))
    }
    resp
  }

  override def CloseOperation(req: TCloseOperationReq): TCloseOperationResp = {
    val resp = new TCloseOperationResp
    try {
      cliService.closeOperation(new OperationHandle(req.getOperationHandle))
      resp.setStatus(OK_STATUS)
    } catch {
      case e: Exception =>
        logWarning("Error closing operation: ", e)
        resp.setStatus(HiveSQLException.toTStatus(e))
    }
    resp
  }

  override def GetResultSetMetadata(req: TGetResultSetMetadataReq): TGetResultSetMetadataResp = {
    val resp = new TGetResultSetMetadataResp
    try {
      val schema = cliService.getResultSetMetadata(new OperationHandle(req.getOperationHandle))
      resp.setSchema(schema.toTTableSchema)
      resp.setStatus(OK_STATUS)
    } catch {
      case e: Exception =>
        logWarning("Error getting result set metadata: ", e)
        resp.setStatus(HiveSQLException.toTStatus(e))
    }
    resp
  }

  override def FetchResults(req: TFetchResultsReq): TFetchResultsResp = {
    val resp = new TFetchResultsResp
    try {
      val rowSet = cliService.fetchResults(
        new OperationHandle(req.getOperationHandle),
        FetchOrientation.getFetchOrientation(req.getOrientation),
        req.getMaxRows,
        FetchType.getFetchType(req.getFetchType))
      resp.setResults(rowSet.toTRowSet)
      resp.setHasMoreRows(false)
      resp.setStatus(OK_STATUS)
    } catch {
      case e: Exception =>
        logWarning("Error fetching results: ", e)
        resp.setStatus(HiveSQLException.toTStatus(e))
    }
    resp
  }

  override def GetDelegationToken(req: TGetDelegationTokenReq): TGetDelegationTokenResp = {
    val resp = new TGetDelegationTokenResp
    resp.setStatus(notSupportTokenErrorStatus)
    resp
  }

  private[this] def notSupportTokenErrorStatus = {
    val errorStatus = new TStatus(TStatusCode.ERROR_STATUS)
    errorStatus.setErrorMessage("Delegation token is not supported")
    errorStatus
  }

  override def CancelDelegationToken(req: TCancelDelegationTokenReq): TCancelDelegationTokenResp = {
    val resp = new TCancelDelegationTokenResp
    resp.setStatus(notSupportTokenErrorStatus)
    resp
  }

  override def RenewDelegationToken(req: TRenewDelegationTokenReq): TRenewDelegationTokenResp = {
    val resp = new TRenewDelegationTokenResp
    resp.setStatus(notSupportTokenErrorStatus)
    resp
  }

  override def run(): Unit = {
    try {
      // Server thread pool
      val executorService = new ThreadPoolExecutor(
        minWorkerThreads,
        maxWorkerThreads,
        workerKeepAliveTime,
        TimeUnit.SECONDS,
        new SynchronousQueue[Runnable],
        new ThreadFactoryWithGarbageCleanup(threadPoolName))

      // Thrift configs
      hiveAuthFactory = new HiveAuthFactory(hiveConf)
      val transportFactory = hiveAuthFactory.getAuthTransFactory
      val processorFactory = hiveAuthFactory.getAuthProcFactory(this)
      val serverSocket: TServerSocket = HiveAuthFactory.getServerSocket(serverHost, portNum)
      val sslVersionBlacklist = new ArrayList[String]
      for (sslVersion <- hiveConf.getVar(ConfVars.HIVE_SSL_PROTOCOL_BLACKLIST).split(",")) {
        sslVersionBlacklist.add(sslVersion)
      }

      // Server args
      val maxMessageSize = hiveConf.getIntVar(HiveConf.ConfVars.HIVE_SERVER2_THRIFT_MAX_MESSAGE_SIZE)
      val requestTimeout = hiveConf.getTimeVar(HiveConf.ConfVars.HIVE_SERVER2_THRIFT_LOGIN_TIMEOUT,
        TimeUnit.SECONDS).toInt
      val beBackoffSlotLength = hiveConf.getTimeVar(
        HiveConf.ConfVars.HIVE_SERVER2_THRIFT_LOGIN_BEBACKOFF_SLOT_LENGTH,
        TimeUnit.MILLISECONDS).toInt
      val sargs = new TThreadPoolServer.Args(serverSocket)
        .processorFactory(processorFactory)
        .transportFactory(transportFactory)
        .protocolFactory(new TBinaryProtocol.Factory)
        .inputProtocolFactory(
          new TBinaryProtocol.Factory(true, true, maxMessageSize, maxMessageSize))
        .requestTimeout(requestTimeout).requestTimeoutUnit(TimeUnit.SECONDS)
        .beBackoffSlotLength(beBackoffSlotLength)
        .beBackoffSlotLengthUnit(TimeUnit.MILLISECONDS)
        .executorService(executorService)
      // TCP Server
      server = Some(new TThreadPoolServer(sargs))
      server.foreach(_.setServerEventHandler(serverEventHandler))
      val msg = "Starting " + classOf[ThriftClientCLIService].getSimpleName + " on port " +
        portNum + " with " + minWorkerThreads + "..." + maxWorkerThreads + " worker threads"
      logInfo(msg)
      server.foreach(_.serve())
    } catch {
      case t: Throwable =>
        logError("Error starting Multi Tenancy Spark Thrift Server: could not start "
          + classOf[ThriftClientCLIService].getSimpleName, t)
        System.exit(-1)
    }
  }

}
