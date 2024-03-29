package com.jd.blockchain.gateway.service;

import com.jd.blockchain.consensus.NodeNetworkAddress;
import com.jd.blockchain.consensus.NodeNetworkAddresses;
import com.jd.blockchain.crypto.HashDigest;
import com.jd.blockchain.gateway.service.topology.LedgerPeerApiServicesTopology;
import com.jd.blockchain.gateway.service.topology.LedgerPeersTopology;
import com.jd.blockchain.transaction.BlockchainQueryService;
import com.jd.blockchain.transaction.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.net.NetworkAddress;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 账本-共识节点管理
 */
public class LedgerPeersManager implements LedgerPeerConnectionListener {

    private static final Logger logger = LoggerFactory.getLogger(LedgerPeersManager.class);
    private ScheduledExecutorService executorService;

    // 账本哈希
    private HashDigest ledger;
    private LedgersManagerContext context;
    private LedgersListener ledgersListener;
    // 连接列表
    private Map<NetworkAddress, LedgerPeerConnectionManager> connections;

    private ReadWriteLock connectionsLock = new ReentrantReadWriteLock();

    // 是否准备就绪，已经有可用连接
    private volatile boolean ready;

    public LedgerPeersManager(HashDigest ledger, LedgerPeerConnectionManager[] peerConnectionServices, LedgersManagerContext context, LedgersListener ledgersListener) {
        this.ledger = ledger;
        this.context = context;
        this.ledgersListener = ledgersListener;
        this.executorService = Executors.newSingleThreadScheduledExecutor();
        this.connections = new ConcurrentHashMap<>();
        for (LedgerPeerConnectionManager manager : peerConnectionServices) {
            manager.setConnectionListener(this);
            connections.put(manager.getPeerAddress(), manager);
        }
    }

    public LedgerPeerConnectionManager newPeerConnectionManager(NetworkAddress peerAddress) {
        return new LedgerPeerConnectionManager(ledger, peerAddress, context, ledgersListener);
    }

    public HashDigest getLedger() {
        return ledger;
    }

    public BlockchainQueryService getQueryService() {
        int retryTimes = 10;
        while (retryTimes > 0) {
            connectionsLock.readLock().lock();
            try {
                long highestHeight = -1;
                Map<Long, Set<NetworkAddress>> connectionGroupByHeight = new HashMap<>();
                for (Map.Entry<NetworkAddress, LedgerPeerConnectionManager> entry : connections.entrySet()) {
                    long height = entry.getValue().getLatestHeight();
                    if (height > highestHeight) {
                        highestHeight = height;
                    }
                    if (!connectionGroupByHeight.containsKey(height)) {
                        connectionGroupByHeight.put(height, new HashSet<>());
                    }
                    connectionGroupByHeight.get(height).add(entry.getKey());
                }

                if (highestHeight > -1) {
                    Set<NetworkAddress> selectedConnections = connectionGroupByHeight.get(highestHeight);
                    return connections.get(new ArrayList(selectedConnections).get(new Random().nextInt(selectedConnections.size()))).getQueryService();
                }

            } finally {
                connectionsLock.readLock().unlock();
            }

            retryTimes--;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
            }
            logger.warn("No available query service for ledger: {}, retry times remain: {}", ledger, retryTimes);
        }

        throw new IllegalStateException("No available query service for ledger: " + ledger);
    }

    public TransactionService getTransactionService() {
        int retryTimes = 10;
        while (retryTimes > 0) {
            connectionsLock.readLock().lock();
            try {
                long highestHeight = -1;
                Map<Long, Set<NetworkAddress>> connectionGroupByHeight = new HashMap<>();
                for (Map.Entry<NetworkAddress, LedgerPeerConnectionManager> entry : connections.entrySet()) {
                    // 去除未认证连接
                    if (!entry.getValue().isAuthorized()) {
                        continue;
                    }
                    long height = entry.getValue().getLatestHeight();
                    if (height > highestHeight) {
                        highestHeight = height;
                    }
                    if (!connectionGroupByHeight.containsKey(height)) {
                        connectionGroupByHeight.put(height, new HashSet<>());
                    }
                    connectionGroupByHeight.get(height).add(entry.getKey());
                }

                if (highestHeight > -1) {
                    Set<NetworkAddress> selectedConnections = connectionGroupByHeight.get(highestHeight);
                    return connections.get(new ArrayList(selectedConnections).get(new Random().nextInt(selectedConnections.size()))).getTransactionService();
                }
            } finally {
                connectionsLock.readLock().unlock();
            }
            retryTimes--;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
            }
            logger.warn("No available tx service for ledger: {}, retry times remain: {}", ledger, retryTimes);
        }

        throw new IllegalStateException("No available tx service for ledger: " + ledger);
    }

    /**
     * 重置所有连接
     */
    public void reset() {
        logger.info("Ledger reset {}", ledger);
        context.getClientManager().reset();
        updatePeers(connections.keySet(), true);
    }

    public void addPeer(NetworkAddress peer) {
        connectionsLock.writeLock().lock();
        try {
            if (connections.containsKey(peer)) {
                return;
            }
            logger.debug("Add peer {} in {}", peer, ledger);
            LedgerPeerConnectionManager connectionManager = newPeerConnectionManager(peer);
            connectionManager.setConnectionListener(this);
            connectionManager.startTimerTask();
            connections.put(peer, connectionManager);
        } finally {
            connectionsLock.writeLock().unlock();
        }
    }

    public synchronized void startTimerTask() {
        // 存储拓扑信息
        storeTopology(new LedgerPeerApiServicesTopology(ledger, context.getKeyPair(), connections.keySet()));

        // 所有连接启动定时任务
        for (LedgerPeerConnectionManager manager : connections.values()) {
            manager.startTimerTask();
        }

        if (context.isAwareTopology()) {
            // 启动定期拓扑感知
            if (context.getTopologyAwareInterval() <= 0) {
                executorService.schedule(() -> updateTopologyTask(), 3000, TimeUnit.MILLISECONDS);
            } else {
                executorService.scheduleWithFixedDelay(() -> updateTopologyTask(), 500, context.getTopologyAwareInterval(), TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * 查询拓扑，更新共识节点连接
     */
    private void updateTopologyTask() {
        // 统计未认证连接数量，并关闭所有连接均为未认证的账本-连接拓扑管理
        int unAuthorizedSize = 0;
        connectionsLock.writeLock().lock();
        try {
            if (connections.size() >= 4) {
                for (Map.Entry<NetworkAddress, LedgerPeerConnectionManager> entry : connections.entrySet()) {
                    if (!entry.getValue().isAuthorized()) {
                        unAuthorizedSize++;
                    }
                }
                if (unAuthorizedSize == connections.size()) {
                    logger.info("Close ledger {}", ledger);
                    if (null != ledgersListener) {
                        // 移除账本
                        ledgersListener.LedgerRemoved(ledger);
                    }
                    storeTopology(new LedgerPeerApiServicesTopology(ledger, context.getKeyPair(), new HashSet<>()));
                    close();
                    return;
                }
            }
        } finally {
            connectionsLock.writeLock().unlock();
        }

        Set<NetworkAddress> addresses = updateTopology();
        if (null != addresses) {
            // 更新连接
            updatePeers(addresses, false);
        }

        // 拓扑结构写入磁盘
        if (context.isStoreTopology()) {
            connectionsLock.readLock().lock();
            try {
                storeTopology(new LedgerPeerApiServicesTopology(ledger, context.getKeyPair(), connections.keySet()));
            } finally {
                connectionsLock.readLock().unlock();
            }
        }
    }

    public Set<NetworkAddress> updateTopology() {
        for (Map.Entry<NetworkAddress, LedgerPeerConnectionManager> entry : connections.entrySet()) {
            try {
                logger.debug("UpdateTopology by {}", entry.getKey());
                NodeNetworkAddresses nodeNetworkAddresses = entry.getValue().loadMonitors();
                if (null != nodeNetworkAddresses) {
                    NodeNetworkAddress[] nodeAddresses = nodeNetworkAddresses.getNodeNetworkAddresses();
                    if (nodeAddresses != null && nodeAddresses.length > 0) {
                        Set<NetworkAddress> addresses = new HashSet<>();
                        boolean satisfied = true;
                        for (NodeNetworkAddress address : nodeAddresses) {
                            // 存在端口小于0的情况则不使用此次查询结果
                            if (address.getMonitorPort() > 0) {
                                addresses.add(new NetworkAddress(address.getHost(), address.getMonitorPort(), address.isMonitorSecure()));
                            } else {
                                satisfied = false;
                                break;
                            }
                        }
                        if (satisfied) {
                            logger.debug("UpdateTopology by {} : {}", entry.getKey(), addresses);
                            return addresses;
                        } else {
                            continue;
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("UpdateTopology by {}", entry.getKey(), e);
            }
        }

        return null;
    }

    private void storeTopology(LedgerPeersTopology topology) {
        if (context.isStoreTopology()) {
            try {
                context.getTopologyStorage().setTopology(ledger.toBase58(), topology);
                logger.debug("Store topology {}", topology);
            } catch (Exception e) {
                logger.error("Store topology error", e);
            }
        }
    }

    /**
     * 更新节点信息
     *
     * @param peers 待更新地址列表
     * @param force 是否强制更新，true会根据传入peers创建并替换现有连接，false则会只在匹配有地址更新时替换。
     */
    private void updatePeers(Set<NetworkAddress> peers, boolean force) {
        connectionsLock.writeLock().lock();
        try {
            if (!force) {
                if (null == peers || peers.size() == 0) {
                    return;
                }
                // 判断连接拓扑是否有变化
                if (peers.size() == connections.size() && connections.keySet().containsAll(peers)) {
                    return;
                }
            }

            closeConsensusClient();

            // 关闭旧的连接，替换新连接
            Map<NetworkAddress, LedgerPeerConnectionManager> oldConnections = new HashMap<>();
            oldConnections.putAll(connections);
            for (Map.Entry<NetworkAddress, LedgerPeerConnectionManager> entry : oldConnections.entrySet()) {
                if (null != entry.getValue()) {
                    entry.getValue().close();
                }
            }

            // 有差异，重新创建所有连接
            Map<NetworkAddress, LedgerPeerConnectionManager> newConnections = new ConcurrentHashMap<>();
            for (NetworkAddress address : peers) {
                LedgerPeerConnectionManager connectionManager = newPeerConnectionManager(address);
                connectionManager.setConnectionListener(this);
                connectionManager.startTimerTask();
                newConnections.put(address, connectionManager);
            }

            connections = newConnections;
        } catch (Exception e) {
            logger.error("UpdateTopology {}:{}", ledger, peers, e);
        } finally {
            connectionsLock.writeLock().unlock();
        }
    }

    public void close() {
        connectionsLock.writeLock().lock();
        try {

            closeConsensusClient();

            for (Map.Entry<NetworkAddress, LedgerPeerConnectionManager> entry : connections.entrySet()) {
                entry.getValue().close();
            }

            executorService.shutdownNow();

            logger.info("LedgerManager {} closed", ledger);
        } finally {
            connectionsLock.writeLock().unlock();
        }
    }

    @Override
    public void connected(NetworkAddress peer) {
        logger.info("LedgerManager {} is ready", ledger);
        this.ready = true;
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * 关闭 consensus client
     */
    private void closeConsensusClient() {
        logger.info("Close consensus client for {}", getLedger());
        context.getClientManager().remove(getLedger());
    }
}
