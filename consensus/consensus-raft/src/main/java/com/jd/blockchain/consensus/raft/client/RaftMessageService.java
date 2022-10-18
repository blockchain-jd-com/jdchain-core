package com.jd.blockchain.consensus.raft.client;

import com.alipay.sofa.jraft.JRaftUtils;
import com.alipay.sofa.jraft.RouteTable;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.error.RemotingException;
import com.alipay.sofa.jraft.option.CliOptions;
import com.alipay.sofa.jraft.rpc.CliRequests;
import com.alipay.sofa.jraft.rpc.RpcResponseClosureAdapter;
import com.alipay.sofa.jraft.rpc.impl.cli.CliClientServiceImpl;
import com.alipay.sofa.jraft.util.Endpoint;
import com.google.protobuf.ProtocolStringList;
import com.jd.binaryproto.BinaryProtocol;
import com.jd.blockchain.consensus.*;
import com.jd.blockchain.consensus.manage.ConsensusManageService;
import com.jd.blockchain.consensus.manage.ConsensusView;
import com.jd.blockchain.consensus.raft.config.RaftReplica;
import com.jd.blockchain.consensus.raft.manager.RaftConsensusView;
import com.jd.blockchain.consensus.raft.rpc.QueryManagerInfoRequest;
import com.jd.blockchain.consensus.raft.rpc.QueryManagerInfoRequestProcessor;
import com.jd.blockchain.consensus.raft.rpc.RpcResponse;
import com.jd.blockchain.consensus.raft.rpc.SubmitTxRequest;
import com.jd.blockchain.consensus.raft.settings.RaftClientSettings;
import com.jd.blockchain.consensus.raft.settings.RaftConsensusSettings;
import com.jd.blockchain.consensus.raft.settings.RaftNetworkSettings;
import com.jd.blockchain.consensus.raft.settings.RaftNodeSettings;
import com.jd.blockchain.consensus.raft.util.LoggerUtils;
import com.jd.blockchain.consensus.service.MonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.concurrent.AsyncFuture;
import utils.concurrent.CompletableAsyncFuture;
import utils.net.NetworkAddress;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public class RaftMessageService implements MessageService, ConsensusManageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RaftMessageService.class);
    private static final int MAX_RETRY_TIMES = 3;

    private final ReentrantLock lock = new ReentrantLock();
    private final String groupId;

    private PeerId leader;
    private Configuration configuration;
    private long lastLeaderUpdateTimestamp;
    private long lastConfigurationUpdateTimestamp;

    private final ScheduledExecutorService refreshLeaderExecutorService;
    private final Executor monitorExecutor;
    private final CliClientServiceImpl clientService;

    private final int rpcTimeoutMs;
    private final int refreshLeaderMs;
    private final int refreshConfigurationMs;

    private volatile boolean isStart;

    private final List<ConsensusNodeNetwork> monitorNodeNetworks = new CopyOnWriteArrayList<>();
    private volatile boolean isReloadMonitorNode = true;
    private final Object monitorLock = new Object();

    public RaftMessageService(String groupId, RaftClientSettings settings) {

        RaftConsensusSettings consensusSettings = (RaftConsensusSettings) settings.getViewSettings();
        RaftNetworkSettings networkSettings = consensusSettings.getNetworkSettings();

        this.rpcTimeoutMs = networkSettings.getRpcRequestTimeoutMs();
        this.refreshLeaderMs = consensusSettings.getElectionTimeoutMs();
        this.refreshConfigurationMs = consensusSettings.getRefreshConfigurationMs();

        CliOptions cliOptions = new CliOptions();
        cliOptions.setRpcConnectTimeoutMs(networkSettings.getRpcConnectTimeoutMs());
        cliOptions.setRpcDefaultTimeout(networkSettings.getRpcDefaultTimeoutMs());
        cliOptions.setRpcInstallSnapshotTimeout(networkSettings.getRpcSnapshotTimeoutMs());
        cliOptions.setTimeoutMs(networkSettings.getRpcRequestTimeoutMs());
        cliOptions.setMaxRetry(MAX_RETRY_TIMES);

        this.clientService = new CliClientServiceImpl();
        this.clientService.init(cliOptions);

        this.groupId = groupId;
        this.configuration = new Configuration();
        for (String currentPeer : settings.getCurrentPeers()) {
            LOGGER.info("ledger: {} add peer: {}", groupId, currentPeer);
            this.configuration.addPeer(PeerId.parsePeer(currentPeer));
        }

        NodeSettings[] nodeSettings = settings.getViewSettings().getNodes();
        for (NodeSettings nodeSetting : nodeSettings) {
            RaftNodeSettings raftNodeSettings = (RaftNodeSettings) nodeSetting;
            PeerId peerId = new PeerId(raftNodeSettings.getNetworkAddress().getHost(), raftNodeSettings.getNetworkAddress().getPort());
            if (!this.configuration.contains(peerId)) {
                LOGGER.info("ledger: {} add peer: {}", groupId, peerId);
                this.configuration.addPeer(peerId);
            }
        }

        this.refreshLeaderExecutorService = Executors.newSingleThreadScheduledExecutor(JRaftUtils.createThreadFactory("RAFT-REFRESH-LEADER"));
        this.monitorExecutor = JRaftUtils.createExecutor("RAFT-CLIENT-MONITOR", Runtime.getRuntime().availableProcessors());
    }

    public void init() {
        RouteTable.getInstance().updateConfiguration(groupId, configuration);
        refresh();
        refreshLeaderExecutorService.scheduleAtFixedRate(this::refresh, refreshLeaderMs, refreshLeaderMs, TimeUnit.MILLISECONDS);
        isStart = true;
    }

    private void refresh() {
        lock.lock();
        try {
            refreshLeader();
            refreshConfiguration();
        } catch (Exception e) {
            LOGGER.error("refresh raft client config error", e);
        } finally {
            lock.unlock();
        }
    }

    private void refreshConfiguration() throws TimeoutException, InterruptedException {
        if (System.currentTimeMillis() - this.lastConfigurationUpdateTimestamp < this.refreshConfigurationMs) {
            return;
        }
        Status status = RouteTable.getInstance().refreshConfiguration(this.clientService, this.groupId, this.rpcTimeoutMs);
        if (!status.isOk()) {
            LOGGER.warn("refreshConfiguration failed. reason: {}", status.getErrorMsg());
        } else {
            Configuration changedConfiguration = RouteTable.getInstance().getConfiguration(this.groupId);
            if (!changedConfiguration.isEmpty()) {
                Configuration include = new Configuration();
                Configuration exclude = new Configuration();

                changedConfiguration.diff(this.configuration, include, exclude);

                if (!include.isEmpty() || !exclude.isEmpty()) {
                    LOGGER.info("configuration changed from: {} to {}", this.configuration, changedConfiguration);
                    this.configuration = changedConfiguration;
                }
            }
        }

        this.lastConfigurationUpdateTimestamp = System.currentTimeMillis();
        this.isReloadMonitorNode = true;

    }

    private synchronized void refreshLeader() throws TimeoutException, InterruptedException {
        if (System.currentTimeMillis() - this.lastLeaderUpdateTimestamp < this.refreshLeaderMs) {
            return;
        }
        Status status = RouteTable.getInstance().refreshLeader(this.clientService, this.groupId, this.rpcTimeoutMs);
        if (!status.isOk()) {
            LOGGER.warn("refreshLeader failed. reason: {}", status.getErrorMsg());
        } else {
            PeerId peerId = RouteTable.getInstance().selectLeader(this.groupId);
            if (peerId != null && !peerId.equals(this.leader)) {
                LoggerUtils.infoIfEnabled(LOGGER, "leader changed. from {} to {}", this.leader, peerId);
                this.leader = peerId;
            }
        }

        this.lastLeaderUpdateTimestamp = System.currentTimeMillis();
    }


    @Override
    public AsyncFuture<byte[]> sendOrdered(byte[] message) {
        ensureConnected();
        CompletableAsyncFuture<byte[]> asyncFuture = new CompletableAsyncFuture<>();
        try {
            int retry = 0;
            SubmitTxRequest txRequest = new SubmitTxRequest(message);
            sendRequest(leader.getEndpoint(), txRequest, retry, asyncFuture);
        } catch (Exception e) {
            throw new RaftClientRequestException(e);
        }
        return asyncFuture;
    }

    @Override
    public AsyncFuture<byte[]> sendUnordered(byte[] message) {
        ensureConnected();
        if (Arrays.equals(MonitorService.LOAD_MONITOR, message)) {
            return queryPeersManagerInfo();
        }
        return new CompletableAsyncFuture<>();
    }

    private void sendRequest(Endpoint endpoint, SubmitTxRequest txRequest, int retry, CompletableAsyncFuture<byte[]> asyncFuture)
            throws RemotingException, InterruptedException {

        if (retry >= MAX_RETRY_TIMES) {
            asyncFuture.error(new RuntimeException("raft client send request exceed max retries"));
            return;
        }

        if (endpoint == null) {
            asyncFuture.error(new RuntimeException("raft client send request find leader endpoint is null"));
            return;
        }

        clientService.getRpcClient().invokeAsync(endpoint, txRequest, (o, throwable) -> {

            LoggerUtils.debugIfEnabled(LOGGER, "raft client send request: {} response: {} throwable: {}", txRequest, o, throwable);

            if (throwable != null) {
                LOGGER.error("raft client send request error, request: {}", txRequest, throwable);
                asyncFuture.error(throwable);
                return;
            }

            RpcResponse response = (RpcResponse) o;
            if (response.isRedirect()) {
                LoggerUtils.debugIfEnabled(LOGGER, "request should redirect to leader. current peer: {} , redirect leader: {}", endpoint, response.getLeaderEndpoint());
                try {
                    sendRequest(JRaftUtils.getEndPoint(response.getLeaderEndpoint()), txRequest, retry + 1, asyncFuture);
                } catch (Exception e) {
                    throw new RaftClientRequestException(e);
                }
                return;
            }

            if (response.isSuccess()) {
                asyncFuture.complete(response.getResult());
            } else {
                asyncFuture.complete(null);
            }

        }, rpcTimeoutMs);
    }


    private void ensureConnected() {

        if (!isStart) {
            throw new IllegalStateException("Client has closed");
        }

        if (!isConnected()) {
            throw new IllegalStateException("Client has not connected to the leader nodes!");
        }
    }

    private AsyncFuture<byte[]> queryPeersManagerInfo() {
        Configuration configuration = RouteTable.getInstance().getConfiguration(this.groupId);
        List<PeerId> peerIds = configuration.listPeers();

        return CompletableAsyncFuture.callAsync((Callable<byte[]>) () -> {
            try {
                List<ConsensusNodeNetwork> monitorNodeNetworkList = queryPeersManagerInfo(peerIds);
                ConsensusNodeNetwork[] monitorNodeNetworks = monitorNodeNetworkList.toArray(new ConsensusNodeNetwork[]{});
                NodeNetworkTopology addresses = new NodeNetworkTopology(monitorNodeNetworks);
                return BinaryProtocol.encode(addresses, NodeNetworkAddresses.class);
            } catch (Exception e) {
                LOGGER.error("refreshAndQueryPeersManagerInfo error", e);
            }
            return null;
        }, monitorExecutor);
    }

    private List<ConsensusNodeNetwork> queryPeersManagerInfo(List<PeerId> peerIds) throws InterruptedException {

        if (!isReloadMonitorNode) {
            return monitorNodeNetworks;
        }

        synchronized (monitorLock) {
            if (!isReloadMonitorNode) {
                return monitorNodeNetworks;
            }

            final List<ConsensusNodeNetwork> nodeNetworkList = Collections.synchronizedList(new ArrayList<>(peerIds.size()));
            CountDownLatch countDownLatch = new CountDownLatch(peerIds.size());
            QueryManagerInfoRequest infoRequest = new QueryManagerInfoRequest();

            for (PeerId peerId : peerIds) {
                try {
                    clientService.getRpcClient().invokeAsync(peerId.getEndpoint(), infoRequest, (o, e) -> {
                        try {
                            if (e != null) {
                                LoggerUtils.errorIfEnabled(LOGGER, "queryPeersManagerInfo response error, peer id: {}", peerId, e);
                            } else {
                                RpcResponse response = (RpcResponse) o;
                                QueryManagerInfoRequestProcessor.ManagerInfoResponse managerInfoResponse =
                                        QueryManagerInfoRequestProcessor.ManagerInfoResponse.fromBytes(response.getResult());

                                ConsensusNodeNetwork monitorNodeNetwork = new ConsensusNodeNetwork(
                                        managerInfoResponse.getHost(),
                                        managerInfoResponse.getConsensusPort(),
                                        managerInfoResponse.getManagerPort(),
                                        managerInfoResponse.isConsensusSSLEnabled(),
                                        managerInfoResponse.isManagerSSLEnabled()
                                );

                                nodeNetworkList.add(monitorNodeNetwork);
                            }
                        } catch (Exception exception) {
                            LoggerUtils.errorIfEnabled(LOGGER, "handle queryPeersManagerInfo response error", e);
                        } finally {
                            countDownLatch.countDown();
                        }
                    }, this.rpcTimeoutMs);
                } catch (Exception e) {
                    LOGGER.error("queryPeersManagerInfo error", e);
                    countDownLatch.countDown();
                }
            }

            countDownLatch.await(this.rpcTimeoutMs * peerIds.size(), TimeUnit.MILLISECONDS);
            monitorNodeNetworks.clear();
            monitorNodeNetworks.addAll(nodeNetworkList);
            isReloadMonitorNode = false;
        }

        return monitorNodeNetworks;
    }


    @Override
    public AsyncFuture<ConsensusView> addNode(Replica replica) {
        ensureConnected();
        CompletableAsyncFuture<ConsensusView> asyncFuture = new CompletableAsyncFuture<>();

        RaftReplica raftReplica = (RaftReplica) replica;
        CliRequests.AddPeerRequest addPeerRequest = CliRequests.AddPeerRequest.newBuilder()
                .setGroupId(this.groupId)
                .setPeerId(raftReplica.getPeerStr())
                .build();

        clientService.addPeer(leader.getEndpoint(), addPeerRequest, new RpcResponseClosureAdapter<CliRequests.AddPeerResponse>() {
            @Override
            public void run(Status status) {
                CliRequests.AddPeerResponse response = getResponse();
                LoggerUtils.debugIfEnabled(LOGGER, "raft client add node {} result is: {}, response is: {}", replica, status, response);

                if (!status.isOk()) {
                    asyncFuture.error(new RuntimeException(status.getErrorMsg()));
                    return;
                }

                RaftConsensusView consensusView = new RaftConsensusView();
                consensusView.setOldPeers(covertToRaftNodeInfoArray(response.getOldPeersList()));
                consensusView.setNewPeers(covertToRaftNodeInfoArray(response.getNewPeersList()));

                asyncFuture.complete(consensusView);
            }

        });

        return asyncFuture;
    }

    private RaftConsensusView.RaftNodeInfo[] covertToRaftNodeInfoArray(ProtocolStringList peersList) {

        if (peersList == null || peersList.asByteStringList().isEmpty()) {
            return null;
        }

        return peersList.asByteStringList().stream()
                .map(x -> PeerId.parsePeer(x.toStringUtf8()))
                .map(p -> new RaftConsensusView.RaftNodeInfo(0, new NetworkAddress(p.getIp(), p.getPort())))
                .toArray(RaftConsensusView.RaftNodeInfo[]::new);
    }

    @Override
    public AsyncFuture<ConsensusView> removeNode(Replica replica) {
        ensureConnected();
        CompletableAsyncFuture<ConsensusView> asyncFuture = new CompletableAsyncFuture<>();

        RaftReplica raftReplica = (RaftReplica) replica;
        CliRequests.RemovePeerRequest removePeerRequest = CliRequests.RemovePeerRequest.newBuilder()
                .setGroupId(this.groupId)
                .setPeerId(raftReplica.getPeerStr())
                .build();

        clientService.removePeer(leader.getEndpoint(), removePeerRequest, new RpcResponseClosureAdapter<CliRequests.RemovePeerResponse>() {
            @Override
            public void run(Status status) {
                CliRequests.RemovePeerResponse response = getResponse();
                LoggerUtils.debugIfEnabled(LOGGER, "raft client remove node {} result is: {}, response is: {}", replica, status, response);

                if (!status.isOk()) {
                    asyncFuture.error(new RuntimeException(status.getErrorMsg()));
                    return;
                }

                RaftConsensusView consensusView = new RaftConsensusView();
                consensusView.setOldPeers(covertToRaftNodeInfoArray(response.getOldPeersList()));
                consensusView.setNewPeers(covertToRaftNodeInfoArray(response.getNewPeersList()));

                asyncFuture.complete(consensusView);
            }


        });

        return asyncFuture;
    }

    public void close() {
        if (this.refreshLeaderExecutorService != null) {
            this.refreshLeaderExecutorService.shutdownNow();
        }

        if (this.clientService != null) {
            this.clientService.shutdown();
        }

        if (this.monitorExecutor != null) {
            ((ExecutorService) monitorExecutor).shutdown();
        }

        isStart = false;
    }

    public boolean isConnected() {

        if (this.leader == null) {
            return false;
        }

        if (clientService.isConnected(this.leader.getEndpoint())) {
            return true;
        }

        return clientService.connect(this.leader.getEndpoint());
    }

}
