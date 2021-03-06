package com.splout.db.qnode;

/*
 * #%L
 * Splout SQL Server
 * %%
 * Copyright (C) 2012 Datasalt Systems S.L.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

import com.google.common.base.Joiner;
import com.hazelcast.core.*;
import com.splout.db.common.JSONSerDe;
import com.splout.db.common.JSONSerDe.JSONSerDeException;
import com.splout.db.common.SploutConfiguration;
import com.splout.db.common.Tablespace;
import com.splout.db.dnode.beans.DNodeSystemStatus;
import com.splout.db.engine.ResultSerializer.SerializationException;
import com.splout.db.hazelcast.*;
import com.splout.db.qnode.Deployer.UnexistingVersion;
import com.splout.db.qnode.QNodeHandlerContext.TablespaceVersionInfoException;
import com.splout.db.qnode.Querier.QuerierException;
import com.splout.db.qnode.beans.*;
import com.splout.db.thrift.DNodeService;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.Meter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.thrift.transport.TTransportException;
import org.codehaus.jackson.type.TypeReference;

import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

/**
 * Implements the business logic for the {@link QNode}.
 * <p/>
 * The QNode is the most complex and delicate part of Splout. Among its responsabilities are:
 * <ul>
 * <li>Handling deploys asynchronously: One QNode will lead a deployment. It will put a flag in ZooKeeper and trigger an
 * asynchronous deploy to all involved DNodes. Then, it has to finalize the deploy properly when all DNodes are ready.
 * This is handled by the {@link Deployer} module.</li>
 * <li>Performing queries and multiqueries. This is handled by the {@link Querier} module.</li>
 * <li>Handling rollbacks: Rollbacks are easy since we just need to change the version in ZooKeeper (DNodes already have
 * the data for past version in disk). The number of versions that are saved in the system per tablespace can be
 * configured (see {@link QNodeProperties}).</li>
 * </ul>
 * For convenience, there is some in-memory state grabbed from ZooKeeper in {@link QNodeHandlerContext}. This state is
 * passed through all modules ({@link Deployer} and such). Care has to be taken to have consistent in-memory state, for
 * that it is important to handle ZooKeeper events properly and be notified always on the paths that we are interested
 * in.
 * <p/>
 * One of the important business logic parts of this class is to synchronize the versions in ZooKeeper. Because we only
 * want to keep a certain amount of versions, the QNodes have to check for this and remove stalled versions if needed.
 * Then, DNodes will receive a notification and they will be able to delete the old data from disk.
 * <p/>
 * The QNode returns JSON strings for all of its methods. The beans that are serialized are indicated in the
 * documentation.
 */
public class QNodeHandler implements IQNodeHandler {

  /**
   * The JSON type reference for deserializing Multi-query results
   */
  public final static TypeReference<ArrayList<QueryStatus>> MULTIQUERY_TYPE_REF = new TypeReference<ArrayList<QueryStatus>>() {
  };

  private final static Log log = LogFactory.getLog(QNodeHandler.class);
  private QNodeHandlerContext context;
  private Deployer deployer;
  private Querier querier;
  private SploutConfiguration config;
  private CoordinationStructures coord;
  private Thread warmingThread;
  // Local copy of DNode registry. We need to maintain a local copy
  // because entryRemoved events don't provides value information :P
  private Hashtable<String, DNodeInfo> mapToDNodeInfo = new Hashtable<String, DNodeInfo>();

  private final Counter meterQueriesServed = Metrics.newCounter(QNodeHandler.class, "queries-served");
  private final Meter meterRequestsPerSecond = Metrics.newMeter(QNodeHandler.class, "queries-second",
      "queries-second", TimeUnit.SECONDS);
  private final Histogram meterResultSize = Metrics.newHistogram(QNodeHandler.class, "response-size");
  private String qNodeAddress;

  /**
   * Keep track of die/alive DNodes events.
   */
  public class DNodesListener implements EntryListener<String, DNodeInfo> {

    public void entryAdded(String dNodeRef, DNodeInfo dNodeInfo) {
      log.info("DNode [" + dNodeInfo.getAddress() + "] joins the cluster as ready to server requests.");
      mapToDNodeInfo.put(dNodeRef, dNodeInfo);
      // Update TablespaceVersions
      try {
        String dnode = dNodeInfo.getAddress();
        log.info(Thread.currentThread().getName() + " : populating client queue for [" + dnode
            + "] as it connected.");
        context.initializeThriftClientCacheFor(dnode);
        context.getTablespaceState().updateTablespaceVersions(dNodeInfo, QNodeHandlerContext.DNodeEvent.ENTRY);
        context.maybeBalance();
      } catch (TablespaceVersionInfoException e) {
        throw new RuntimeException(e);
      } catch (TTransportException e) {
        throw new RuntimeException(e);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void entryAdded(EntryEvent<String, DNodeInfo> event) {
      entryAdded(event.getKey(), event.getValue());
    }

    @Override
    public void entryRemoved(EntryEvent<String, DNodeInfo> event) {
      // even.getValue() comes null here. Grrrrr!!!
      // http://docs.hazelcast.org/docs/3.3/javadoc/com/hazelcast/core/IMap.html#delete(java.lang.Object)
      // This is reason because we maintain mapToDNodeInfo map locally.
      DNodeInfo dNodeInfo = mapToDNodeInfo.get(event.getKey());
      mapToDNodeInfo.remove(event.getKey());
      log.info("DNode [" + dNodeInfo.getAddress() + "] left.");
      // Update TablespaceVersions
      try {
        context.discardThriftClientCacheFor(dNodeInfo.getAddress());
        context.getTablespaceState().updateTablespaceVersions(dNodeInfo, QNodeHandlerContext.DNodeEvent.LEAVE);
        context.maybeBalance();
      } catch (TablespaceVersionInfoException e) {
        throw new RuntimeException(e);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void entryUpdated(EntryEvent<String, DNodeInfo> event) {
      // Update TablespaceVersions
      try {
        mapToDNodeInfo.put(event.getKey(), event.getValue());
        context.getTablespaceState().updateTablespaceVersions(event.getValue(), QNodeHandlerContext.DNodeEvent.UPDATE);
      } catch (TablespaceVersionInfoException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void entryEvicted(EntryEvent<String, DNodeInfo> event) {
      // Never happens
      log.error("Event entryEvicted received for [" + event + "]. "
          + "Should have never happened... Something wrong in the code");
    }

    @Override
    public void mapEvicted(MapEvent mapEvent) {
      log.error("Event mapEvicted received for [" + mapEvent + "]. "
          + "Should have never happened... Something wrong in the code");
    }

    @Override
    public void mapCleared(MapEvent mapEvent) {
      log.error("Event mapCleared received for [" + mapEvent + "]. "
          + "Should have never happened... Something wrong in the code");
    }
  }

  public class VersionListener implements EntryListener<String, Map<String, Long>> {

    private void check(EntryEvent<String, Map<String, Long>> event) {
      if (!CoordinationStructures.KEY_FOR_VERSIONS_BEING_SERVED.equals(event.getKey())) {
        throw new RuntimeException("Unexpected key " + event.getKey() + " for map "
            + CoordinationStructures.KEY_FOR_VERSIONS_BEING_SERVED);
      }
    }

    private void processAddOrUpdate(EntryEvent<String, Map<String, Long>> event) {
      check(event);
      try {
        // We perform all changes together with the aim of atomicity
        updateLocalTablespace(event.getValue());
        // After a change in versions (deployment, rollback, delete) we must
        // synchronize tablespace versions to see if we have to remove some.
        context.synchronizeTablespaceVersions();
      } catch (Exception e) {
        log.error(
            "Error changing serving tablespace [" + event.getKey() + " to version [" + event.getValue()
                + "]. Probably the system is now unstable.", e);
      }
    }

    @Override
    public void entryAdded(EntryEvent<String, Map<String, Long>> event) {
      // log.info("New versions table event received.");
      processAddOrUpdate(event);
    }

    @Override
    public void entryUpdated(EntryEvent<String, Map<String, Long>> event) {
      // log.info("Updated versions table event received.");
      processAddOrUpdate(event);
    }

    @Override
    public synchronized void entryRemoved(EntryEvent<String, Map<String, Long>> event) {
      check(event);
      // TODO: make this operation atomical. ConcurrentHashMap.clear() is not.
      log.info("Versions table removed!. Clearing up all tablespace versions.");
      context.getCurrentVersionsMap().clear();
      return;
    }

    @Override
    public void entryEvicted(EntryEvent<String, Map<String, Long>> event) {
      throw new RuntimeException("Should never happen. Something is really wrong :O");
    }

    @Override
    public void mapEvicted(MapEvent mapEvent) {
      log.error("Event mapEvicted received for [" + mapEvent + "]. "
          + "Should have never happened... Something wrong in the code");
    }

    @Override
    public void mapCleared(MapEvent mapEvent) {
      log.error("Event mapCleared received for [" + mapEvent + "]. "
          + "Should have never happened... Something wrong in the code");
    }
  }

  public void init(final SploutConfiguration config) throws Exception {
    this.config = config;
    log.info(this + " - Initializing QNode...");
    // Connect with the cluster.
    HazelcastInstance hz = Hazelcast.newHazelcastInstance(HazelcastConfigBuilder.build(config));
    int minutesToCheckRegister = config.getInt(HazelcastProperties.MAX_TIME_TO_CHECK_REGISTRATION, 5);
    int oldestMembersLeading = config.getInt(HazelcastProperties.OLDEST_MEMBERS_LEADING_COUNT, 3);
    // we must instantiate the DistributedRegistry even if we're not a DNode to be able to receive memembership leaving
    // in race conditions such as all DNodes leaving.
    new DistributedRegistry(CoordinationStructures.DNODES, null, hz, minutesToCheckRegister,
        oldestMembersLeading);
    coord = new CoordinationStructures(hz);
    context = new QNodeHandlerContext(config, coord);
    // Initialialize DNodes tracking
    initDNodesTracking();
    // Initialize versions to be served tracking
    initVersionTracking();
    // Now instantiate modules
    deployer = new Deployer(context);
    querier = new Querier(context);
    log.info(Thread.currentThread() + " - Initializing QNode [DONE].");
    warmingThread = new Thread() {
      @Override
      public void run() {
        try {
          log.info("Currently warming up for [" + config.getInt(QNodeProperties.WARMING_TIME)
              + "] - certain actions will only be taken afterwards.");
          Thread.sleep(config.getInt(QNodeProperties.WARMING_TIME) * 1000);
          log.info("Warming time ended [OK] Now the QNode will operate fully normally.");
        } catch (InterruptedException e) {
          log.error("Warming time interrupted - ");
        }
        context.getIsWarming().set(false);
      }
    };
    warmingThread.start();
  }

  /**
   * Initializes the tracking of DNodes joining and leaving the cluster.
   */
  private void initDNodesTracking() {
    IMap<String, DNodeInfo> dnodes = context.getCoordinationStructures().getDNodes();
    // CAUTION: We must register the listener BEFORE reading the list
    // of dnodes. Otherwise we could have a race condition.
    DNodesListener listener = new DNodesListener();
    dnodes.addEntryListener(new DNodesListener(), true);
    Set<String> dNodes = new HashSet<String>();
    for (Entry<String, DNodeInfo> entry : dnodes.entrySet()) {
      // Manually adding existing DNodes, as Hazelcast won't do for us.
      listener.entryAdded(entry.getKey(), entry.getValue());
      dNodes.add(entry.getValue().getAddress());
    }
    log.info("Alive DNodes at QNode startup [" + Joiner.on(", ").skipNulls().join(dNodes) + "]");
    log.info("TablespaceVersion map at QNode startup [" + context.getTablespaceVersionsMap() + "]");
  }

  /**
   * Loads the tablespaces information in memory to being ready to serve them, and starts to keep track of changes in
   * tablespace's version to be served. To be called at initialization.
   */
  private void initVersionTracking() throws IOException {
    IMap<String, Map<String, Long>> versions = context.getCoordinationStructures()
        .getVersionsBeingServed();
    // CAUTION: We register the listener before updating the in memory versions
    // because if we do the other way around, we could lose updates to tablespace
    // versions or new tablespaces.
    VersionListener listener = new VersionListener();
    versions.addEntryListener(listener, true);

    Map<String, Long> vBeingServed = new HashMap<String, Long>();
    String persistenceFolder = config.getString(HazelcastProperties.HZ_PERSISTENCE_FOLDER);
    if (persistenceFolder != null && !persistenceFolder.equals("")) {
      TablespaceVersionStore vStore = new TablespaceVersionStore(persistenceFolder);
      Map<String, Long> vBeingServedFromDisk = vStore
          .load(CoordinationStructures.KEY_FOR_VERSIONS_BEING_SERVED);
      if (vBeingServedFromDisk == null) {
        log.info("No state about versions to be served in disk.");
      } else {
        vBeingServed = vBeingServedFromDisk;
        log.info("Loaded tablespace versions to be served from disk: " + vBeingServedFromDisk);
      }
    }

    Map<String, Long> vBeingServedFromHz = null;
    Map<String, Long> vFinalBeingServed = null;
    // Trying until successful update.
    do {
      vFinalBeingServed = new HashMap<String, Long>(vBeingServed); // A fresh copy for this trial
      vBeingServedFromHz = context.getCoordinationStructures().getCopyVersionsBeingServed();
      if (vBeingServedFromHz != null) {
        // We assume info in memory (Hazelcast) is fresher than info in disk
        for (Map.Entry<String, Long> entry : vBeingServedFromHz.entrySet()) {
          vFinalBeingServed.put(entry.getKey(), entry.getValue());
        }
      }
    } while (!context.getCoordinationStructures().updateVersionsBeingServed(vBeingServedFromHz,
        vFinalBeingServed));

    updateLocalTablespace(vFinalBeingServed);
    log.info("Tablespaces versions after merging loaded disk state with HZ: " + vFinalBeingServed);
  }

  private void updateLocalTablespace(Map<String, Long> tablespacesAndVersions) throws IOException {
    log.info("Update local in-memory tablespace versions to serve: " + tablespacesAndVersions);
    if (tablespacesAndVersions == null) {
      return;
    }
    // CAREFUL TODO: That is not atomic. Something should
    // be done to make that update atomic.
    context.getCurrentVersionsMap().putAll(tablespacesAndVersions);
    String persistenceFolder = config.getString(HazelcastProperties.HZ_PERSISTENCE_FOLDER);
    if (persistenceFolder != null && !persistenceFolder.equals("")) {
      TablespaceVersionStore vStore = new TablespaceVersionStore(persistenceFolder);
      vStore.store(CoordinationStructures.KEY_FOR_VERSIONS_BEING_SERVED, tablespacesAndVersions);
    }
  }

  /**
   * Given a key, a tablespace and a SQL, query it to the appropriated DNode and return the result.
   * <p/>
   * Returns a {@link QueryStatus}.
   *
   * @throws QuerierException
   * @throws SerializationException 
   */
  public QueryStatus query(String tablespace, String key, String sql, String partition)
      throws JSONSerDeException, QuerierException, SerializationException {
    if (sql == null) {
      return new ErrorQueryStatus("Null sql provided, can't query.");
    }
    if (sql.length() < 1) {
      return new ErrorQueryStatus("Empty sql provided, can't query.");
    }
    if (key == null && partition == null) {
      return new ErrorQueryStatus(
          "Null key / partition provided, can't query. Either partition or key must not be null.");
    }
    if (key != null && partition != null) {
      return new ErrorQueryStatus(
          "(partition, key) parameters are mutually exclusive. Please use one or other, not both at the same time.");
    }
    meterQueriesServed.inc();
    meterRequestsPerSecond.mark();
    /*
     * The queries are handled by the specialized module {@link Querier}
		 */
    QueryStatus result = querier.query(tablespace, key, sql, partition);
    if (result.getResult() != null) {
      meterResultSize.update(result.getResult().size());
    }
    return result;
  }

  /**
   * Multi-query: use {@link Querier} for as many shards as needed and return a list of {@link QueryStatus}
   * <p/>
   * Returns a list of {@link QueryStatus}.
   * @throws SerializationException 
   */
  public ArrayList<QueryStatus> multiQuery(String tablespaceName, List<String> keyMins,
                                           List<String> keyMaxs, String sql) throws JSONSerDeException, QuerierException, SerializationException {

    if (sql == null) {
      return new ArrayList<QueryStatus>(Arrays.asList(new QueryStatus[]{new ErrorQueryStatus(
          "Null sql provided, can't query.")}));
    }
    if (sql.length() < 1) {
      return new ArrayList<QueryStatus>(Arrays.asList(new QueryStatus[]{new ErrorQueryStatus(
          "Empty sql provided, can't query.")}));
    }

    if (keyMins.size() != keyMaxs.size()) {
      // This has to be handled before! We are not going to be polite here
      throw new RuntimeException(
          "This is very likely a software bug: Inconsistent parameters received in "
              + QNodeHandler.class + " for multiQuery() : " + tablespaceName + ", " + keyMins + ","
              + keyMaxs + ", " + sql);
    }
    Set<Integer> impactedKeys = new HashSet<Integer>();
    Long version = context.getCurrentVersionsMap().get(tablespaceName);
    if (version == null) {
      return new ArrayList<QueryStatus>(Arrays.asList(new QueryStatus[]{new ErrorQueryStatus(
          "No available version for tablespace " + tablespaceName)}));
    }
    Tablespace tablespace = context.getTablespaceVersionsMap().get(
        new TablespaceVersion(tablespaceName, version));
    if (tablespace == null) { // This can happen if, at startup, we only received the version and not the DNodeInfo
      return new ArrayList<QueryStatus>(Arrays.asList(new QueryStatus[]{new ErrorQueryStatus(
          "No available information for tablespace version " + tablespaceName + "," + version)}));
    }
    if (keyMins.size() == 0) {
      impactedKeys.addAll(tablespace.getPartitionMap().findPartitions(null, null)); // all partitions are hit
    }
    for (int i = 0; i < keyMins.size(); i++) {
      impactedKeys.addAll(tablespace.getPartitionMap().findPartitions(keyMins.get(i), keyMaxs.get(i)));
    }
    ArrayList<QueryStatus> toReturn = new ArrayList<QueryStatus>();
    for (Integer shardKey : impactedKeys) {
      toReturn.add(querier.query(tablespaceName, sql, shardKey));
    }
    meterQueriesServed.inc();
    meterRequestsPerSecond.mark();
    return toReturn;
  }

  /**
   * Given a list of {@link DeployRequest}, perform an asynchronous deploy. This is currently the most important part of
   * Splout and the most complex one. Here we are involving several DNodes asynchronously and later we will check that
   * everything finished.
   * <p/>
   * Returns a {@link DeployInfo}.
   */
  public DeployInfo deploy(List<DeployRequest> deployRequest) throws Exception {
    /*
     * The deployment is handled by the specialized module {@link Deployer}
		 */
    return deployer.deploy(deployRequest);
  }

  /**
   * Rollback: Set the version of some tablespaces to a particular one.
   * <p/>
   * Returns a {@link StatusMessage}.
   */
  public StatusMessage rollback(List<SwitchVersionRequest> rollbackRequest) throws JSONSerDeException {
    try {
      // TODO: Coordinate with context.synchronizeTablespaceVersions() because one could being deleting some tablespace
      // when other is trying a rollback.
      deployer.switchVersions(rollbackRequest);
      // TODO: Change this status message to something more programmatic
      return new StatusMessage(StatusMessage.Status.OK, "Done");

    } catch (UnexistingVersion e) {
      return new StatusMessage(StatusMessage.Status.ERROR, e.getMessage() + ". Not possible to rollback to unexisting version.");
    }
  }

  /**
   * Returns the {@link QNodeStatus} filled correctly.
   */
  public QNodeStatus overview() throws Exception {
    QNodeStatus status = new QNodeStatus();
    status.setClusterSize(coord.getHz().getCluster().getMembers().size());
    Map<String, DNodeSystemStatus> aliveDNodes = new HashMap<String, DNodeSystemStatus>();
    for (DNodeInfo dnode : context.getCoordinationStructures().getDNodes().values()) {
      DNodeService.Client client = null;
      boolean renew = false;
      try {
        client = getContext().getDNodeClientFromPool(dnode.getAddress());
        aliveDNodes.put(dnode.getAddress(), JSONSerDe.deSer(client.status(), DNodeSystemStatus.class));
      } catch (TTransportException e) {
        renew = true;
        DNodeSystemStatus dstatus = new DNodeSystemStatus();
        dstatus.setSystemStatus("Unreachable");
        aliveDNodes.put(dnode.getAddress(), dstatus);
      } finally {
        if (client != null) {
          context.returnDNodeClientToPool(dnode.getAddress(), client, renew);
        }
      }
    }
    status.setdNodes(aliveDNodes);
    Map<String, Tablespace> tablespaceMap = new HashMap<String, Tablespace>();
    for (Map.Entry<String, Long> currentVersion : context.getCurrentVersionsMap().entrySet()) {
      Tablespace tablespace = context.getTablespaceVersionsMap().get(
          new TablespaceVersion(currentVersion.getKey(), currentVersion.getValue()));
      if (tablespace != null) { // this might happen and it is not a bug
        tablespaceMap.put(currentVersion.getKey(), tablespace);
      }
    }
    status.setTablespaceMap(tablespaceMap);
    return status;
  }

  /**
   * Returns the list of tablespaces
   */
  public Set<String> tablespaces() throws Exception {
    return context.getCurrentVersionsMap().keySet();
  }

  /**
   * Return all available versions for each tablespace
   */
  @Override
  public Map<Long, Tablespace> allTablespaceVersions(final String tablespace) throws Exception {
    HashMap<Long, Tablespace> ret = new HashMap<Long, Tablespace>();
    Set<Entry<TablespaceVersion, Tablespace>> versions = context.getTablespaceVersionsMap().entrySet();
    for (Entry<TablespaceVersion, Tablespace> entry : versions) {
      if (entry.getKey().getTablespace().equals(tablespace)) {
        ret.put(entry.getKey().getVersion(), entry.getValue());
      }
    }
    return ret;
  }

  /**
   * Return a properly filled {@link DNodeSystemStatus}
   */
  @Override
  public DNodeSystemStatus dnodeStatus(String dnode) throws Exception {
    DNodeService.Client client = null;
    boolean renew = false;
    try {
      client = getContext().getDNodeClientFromPool(dnode);
      return JSONSerDe.deSer(client.status(), DNodeSystemStatus.class);
    } catch (TTransportException e) {
      renew = true;
      throw e;
    } finally {
      if (client != null) {
        context.returnDNodeClientToPool(dnode, client, renew);
      }
    }
  }

  /**
   * Returns an overview of what happened with all deployments so far. There is a short status associated with each
   * deploy, and there is a set of detailed log messages as well.
   */
  @Override
  public DeploymentsStatus deploymentsStatus() throws Exception {
    DeploymentsStatus status = new DeploymentsStatus();
    status.setFailedDeployments(new ArrayList<DeploymentStatus>());
    status.setFinishedDeployments(new ArrayList<DeploymentStatus>());
    status.setOngoingDeployments(new ArrayList<DeploymentStatus>());

    for (Map.Entry<Long, DeployStatus> deployment : coord.getDeploymentsStatusPanel().entrySet()) {
      long deployVersion = deployment.getKey();

      DeploymentStatus dStatus = new DeploymentStatus();
      dStatus.setVersion(deployVersion);

      DeployInfo dInfo = coord.getDeployInfoPanel().get(deployVersion);
      dStatus.setDate("");

      if (dInfo != null) {
        dStatus.setDate(dInfo.getStartedAt());
        dStatus.setDataURIs(dInfo.getDataURIs());
        dStatus.setTablespacesDeployed(dInfo.getTablespacesDeployed());
        dStatus.setqNode(dInfo.getqNode());
      } else {
        log.warn("Null DeployInfo for deploy: " + deployVersion
            + " - it should be persisted in any case.");
      }

      List<String> logsPerDeploy = new ArrayList<String>();
      logsPerDeploy.addAll(coord.getDeployLogPanel(deployVersion));
      logsPerDeploy.addAll(coord.getDeploySpeedPanel(deployVersion).values());
      Collections.sort(logsPerDeploy);
      dStatus.setLogMessages(logsPerDeploy);

      if (deployment.getValue().equals(DeployStatus.FAILED)) {
        status.getFailedDeployments().add(dStatus);
      } else if (deployment.getValue().equals(DeployStatus.FINISHED)) {
        status.getFinishedDeployments().add(dStatus);
      } else if (deployment.getValue().equals(DeployStatus.ONGOING)) {
        status.getOngoingDeployments().add(dStatus);
      }
    }

    Comparator<DeploymentStatus> byDeploymentDateDesc = new Comparator<DeploymentStatus>() {

      @Override
      public int compare(DeploymentStatus dStatus1, DeploymentStatus dStatus2) {
        return dStatus2.getDate().compareTo(dStatus1.getDate());
      }
    };

    final int maxDeploymentsToShowPerCategory = 10;

    Collections.sort(status.getFailedDeployments(), byDeploymentDateDesc);
    Collections.sort(status.getFinishedDeployments(), byDeploymentDateDesc);
    Collections.sort(status.getOngoingDeployments(), byDeploymentDateDesc);

    status.setFailedDeployments(status.getFailedDeployments().subList(0,
        Math.min(status.getFailedDeployments().size(), maxDeploymentsToShowPerCategory)));
    status.setFinishedDeployments(status.getFinishedDeployments().subList(0,
        Math.min(status.getFinishedDeployments().size(), maxDeploymentsToShowPerCategory)));
    status.setOngoingDeployments(status.getOngoingDeployments().subList(0,
        Math.min(status.getOngoingDeployments().size(), maxDeploymentsToShowPerCategory)));

    return status;
  }

  @Override
  public Tablespace tablespace(String tablespace) throws Exception {
    Long version = context.getCurrentVersionsMap().get(tablespace);
    if (version == null) {
      return null;
    }
    Tablespace t = context.getTablespaceVersionsMap().get(new TablespaceVersion(tablespace, version));
    return t;
  }

  @Override
  public StatusMessage cancelDeployment(String version) {
    try {
      long v = Long.valueOf(version);
      return deployer.cancelDeployment(v);
    } catch (NumberFormatException e) {
      return new StatusMessage(StatusMessage.Status.ERROR, "Wrong version number: " + version);
    }
  }

  @Override
  public void setQNodeAddress(String qNodeAddress) {
    this.qNodeAddress = qNodeAddress;
    if (context !=null) {
      context.setQNodeAddress(qNodeAddress);
    }
  }

  @Override
  public String getQNodeAddress() {
    return qNodeAddress;
  }

  /**
   * Get the list of DNodes
   */
  @Override
  public List<String> getDNodeList() throws Exception {
    return context.getDNodeList();
  }

  /**
   * Properly dispose this QNodeHandler.
   */
  @Override
  public void close() throws Exception {
    if (context != null) {
      context.close();
    }
    if (warmingThread != null) {
      warmingThread.interrupt();
      warmingThread.join();
    }
  }

  /**
   * Used for testing.
   */
  public QNodeHandlerContext getContext() {
    return context;
  }

  /**
   * Used for testing.
   */
  public Deployer getDeployer() {
    return deployer;
  }

  /**
   * Allows the user to manually tell the QNode to look for old tablespace versions to remove.
   * This happens automatically on every deploy. But if some disks are about to be filled,
   * this can be invoked manually after changing {@link QNodeProperties.#VERSIONS_PER_TABLESPACE}
   * configuration property and restarting the services.
   */
  @Override
  public StatusMessage cleanOldVersions() throws Exception {
    // Check old versions to remove on demand
    List<com.splout.db.thrift.TablespaceVersion> removed = context.synchronizeTablespaceVersions();
    String rmvdTxt = "";
    for (com.splout.db.thrift.TablespaceVersion tV : removed) {
      rmvdTxt += tV.getTablespace() + ":" + tV.getVersion() + ", ";
    }
    if (rmvdTxt.length() > 0) {
      rmvdTxt = rmvdTxt.substring(0, rmvdTxt.length() - 1);
      return new StatusMessage(StatusMessage.Status.OK, "Removing tablespace versions: " + rmvdTxt);
    } else {
      return new StatusMessage(StatusMessage.Status.OK, "No old tablespace versions to remove. Change "
          + QNodeProperties.VERSIONS_PER_TABLESPACE
          + " configuration property and restart the QNodes if you intend to free some space.");
    }
  }
}