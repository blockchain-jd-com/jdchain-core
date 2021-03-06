package com.jd.blockchain.gateway.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.PreDestroy;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jd.blockchain.consensus.NodeNetworkAddress;
import com.jd.blockchain.consensus.NodeNetworkAddresses;
import com.jd.blockchain.consensus.service.MonitorService;
import com.jd.blockchain.crypto.AsymmetricKeypair;
import com.jd.blockchain.crypto.HashDigest;
import com.jd.blockchain.gateway.PeerConnector;
import com.jd.blockchain.gateway.PeerService;
import com.jd.blockchain.gateway.event.EventListener;
import com.jd.blockchain.gateway.event.PullEventListener;
import com.jd.blockchain.sdk.PeerBlockchainService;
import com.jd.blockchain.sdk.service.ConsensusClientManager;
import com.jd.blockchain.sdk.service.PeerBlockchainServiceFactory;
import com.jd.blockchain.sdk.service.SessionCredentialProvider;
import com.jd.blockchain.transaction.BlockchainQueryService;
import com.jd.blockchain.transaction.TransactionService;

import utils.net.NetworkAddress;

@Component
public class PeerConnectionManager implements PeerService, PeerConnector {

	private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(PeerConnectionManager.class);

	/**
	 * 30秒更新一次最新的情况
	 */
	private static final long LEDGER_REFRESH_PERIOD_SECONDS = 30L;

	/**
	 * Peer地址更新周期 该值表示
	 * ${@link PeerConnectionManager#LEDGER_REFRESH_PERIOD_SECONDS}的倍数
	 */
	private static final int PEER_NETWORK_UPDATE_PERIOD = 3;

	@Autowired
	private SessionCredentialProvider credentialProviders;

	@Autowired
	private ConsensusClientManager consensusClientManager;

	private final ScheduledThreadPoolExecutor peerConnectExecutor;

	private final Set<HashDigest> localLedgerCache = new HashSet<>();

	private final Lock ledgerHashLock = new ReentrantLock();

	private Map<NetworkAddress, PeerBlockchainServiceFactory> peerBlockchainServiceFactories = new ConcurrentHashMap<>();

	private Map<HashDigest, PeerBlockchainServiceFactory> latestPeerServiceFactories = new ConcurrentHashMap<>(16);

	private Set<NetworkAddress> peerAddresses = new HashSet<>();

	private NetworkAddress defaultMasterAddress;

	private volatile PeerServiceFactory mostLedgerPeerServiceFactory;

	private volatile AsymmetricKeypair gateWayKeyPair;

//	private volatile List<String> peerProviders;

	private volatile EventListener eventListener;

	public PeerConnectionManager() {
		peerConnectExecutor = scheduledThreadPoolExecutor("peer-connect-%d");
		executorStart();
	}

	@Override
	public Set<NetworkAddress> getPeerAddresses() {
		return peerAddresses;
	}

	@Override
	public boolean isConnected() {
		return !peerBlockchainServiceFactories.isEmpty();
	}

	// 需要进行重连指定账本的优化
	@Override
	public void reconnect(HashDigest ledgerHash) {
		ledgerHashLock.lock();
		try {
			// 先清理
			PeerBlockchainServiceFactory.clear();
			latestPeerServiceFactories.clear();
			mostLedgerPeerServiceFactory = null;
			for (NetworkAddress networkAddress : peerAddresses) {
				LOGGER.info("Reconnect peer , address = {}", networkAddress.getPort());
				PeerBlockchainServiceFactory blockchainServiceFactory = peerBlockchainServiceFactories
						.get(networkAddress);
				if (blockchainServiceFactory != null) {
					blockchainServiceFactory.close();
				}
			}
			Set<NetworkAddress> paddress = new HashSet<>(peerAddresses);
			peerAddresses.clear();
			// 清空账本维护的共识客户端对象，解决本地视图小于共识节点视图，而又无法触发共识客户端更新的问题；
			consensusClientManager.reset();
			// 重新连接
			for (NetworkAddress networkAddress : paddress) {
				connect(networkAddress, gateWayKeyPair);
			}

		} catch (Exception e) {
			throw new IllegalArgumentException(e);
		} finally {
			ledgerHashLock.unlock();
		}
	}


	@Override
	public synchronized void connect(NetworkAddress peerAddress, AsymmetricKeypair defaultKeyPair) {
		LOGGER.info("Add peerAddress{} to connect list !", peerAddress);
		if (peerAddresses.contains(peerAddress)) {
			return;
		}
		// 连接成功的话，更新账本
		ledgerHashLock.lock();
		try {
			setGateWayKeyPair(defaultKeyPair);
			PeerBlockchainServiceFactory peerServiceFactory = PeerBlockchainServiceFactory.connect(defaultKeyPair,
					peerAddress, credentialProviders, consensusClientManager);
			if (peerServiceFactory != null) {
				LOGGER.info("Connect peer {} success !!!", peerAddress);
				// 连接成功
				if (mostLedgerPeerServiceFactory == null) {
					// 默认设置为第一个连接成功的，后续更新需要等待定时任务处理
					mostLedgerPeerServiceFactory = new PeerServiceFactory(peerAddress, peerServiceFactory);
					LOGGER.info("Most ledgers remote update to {}", peerAddress);
				}
				peerBlockchainServiceFactories.put(peerAddress, peerServiceFactory);

				addPeerAddress(peerAddress);

				updateLedgerCache();
			} else {
				LOGGER.error("Connect peer {} fail !!!", peerAddress);
			}
		} catch (Exception e) {
			LOGGER.error(String.format("Connect peer %s fail !!!", peerAddress), e);
		} finally {
			// 连接成功的话，更新账本
			ledgerHashLock.unlock();
		}
	}

	@Override
	public void setMasterPeer(NetworkAddress masterPeer) {
		defaultMasterAddress = masterPeer;
	}

	@Override
	public void monitorAndReconnect() {
		if (getPeerAddresses().isEmpty()) {
			throw new IllegalArgumentException("Peer addresses must be init first !!!");
		}
		/**
		 * 1、首先判断是否之前连接成功过，若未成功则重连，走auth逻辑 2、若成功，则判断对端节点的账本与当前账本是否一致，有新增的情况下重连
		 */
		ledgerHashLock.lock();
		try {
			if (isConnected()) {
				LOGGER.info("----------- Start to load ledgers -----------");
				// 已连接成功，判断账本信息
				PeerServiceFactory serviceFactory = mostLedgerPeerServiceFactory;
				if (serviceFactory == null) {
					// 等待被更新
					return;
				}
				PeerBlockchainService queryService = serviceFactory.serviceFactory.getBlockchainService();
				NetworkAddress peerAddress = serviceFactory.peerAddress;

				HashDigest[] peerLedgerHashs = queryService.getLedgerHashsDirect();
				LOGGER.info("Most peer {} load ledger's size = {}", peerAddress, peerLedgerHashs.length);
				if (peerLedgerHashs.length > 0) {
					boolean haveNewLedger = false;
					for (HashDigest hash : peerLedgerHashs) {
						if (!localLedgerCache.contains(hash)) {
							haveNewLedger = true;
							break;
						}
					}
					if (haveNewLedger) {
						LOGGER.info("New ledger have been found, I will reconnect {} now !!!", peerAddress);
						// 有新账本的情况下重连，并更新本地账本
						try {
							PeerBlockchainServiceFactory peerServiceFactory = PeerBlockchainServiceFactory
									.connect(gateWayKeyPair, peerAddress, credentialProviders, consensusClientManager);
							if (peerServiceFactory != null) {
								peerBlockchainServiceFactories.put(peerAddress, peerServiceFactory);
								localLedgerCache.addAll(Arrays.asList(peerLedgerHashs));
								mostLedgerPeerServiceFactory = new PeerServiceFactory(peerAddress, peerServiceFactory);
								LOGGER.info("Most ledgers remote update to {}",
										mostLedgerPeerServiceFactory.peerAddress);
							} else {
								LOGGER.error("Peer connect fail {}", peerAddress);
							}
						} catch (Exception e) {
							LOGGER.error(String.format("Peer connect fail %s", peerAddress), e);
						}
					}
				}
				LOGGER.info("----------- Load ledgers complete -----------");
			}
		} finally {
			ledgerHashLock.unlock();
		}
	}

	@Override
	public void close() {
		for (Map.Entry<NetworkAddress, PeerBlockchainServiceFactory> entry : peerBlockchainServiceFactories
				.entrySet()) {
			PeerBlockchainServiceFactory serviceFactory = entry.getValue();
			if (serviceFactory != null) {
				serviceFactory.close();
			}
		}
		peerBlockchainServiceFactories.clear();
	}

	@Override
	public BlockchainQueryService getQueryService() {
		// 查询选择最新的连接Factory
		PeerServiceFactory serviceFactory = this.mostLedgerPeerServiceFactory;
		if (serviceFactory == null) {
			// 再次尝试，直到获取一个可用的为止
			ledgerHashLock.lock();
			try {
				// double check
				if (mostLedgerPeerServiceFactory == null) {
					for (NetworkAddress peerAddress : peerAddresses) {
						try {
							PeerBlockchainServiceFactory peerServiceFactory = PeerBlockchainServiceFactory
									.connect(gateWayKeyPair, peerAddress, credentialProviders, consensusClientManager);
							if (peerServiceFactory != null) {
								mostLedgerPeerServiceFactory = new PeerServiceFactory(peerAddress, peerServiceFactory);
								LOGGER.info("Most ledgers remote update to {}", peerAddress);
								return mostLedgerPeerServiceFactory.serviceFactory.getBlockchainService();
							}
						} catch (Exception e) {
							LOGGER.warn("Update remote connect error !", e);
						}
					}
				}
			} finally {
				ledgerHashLock.unlock();
			}
			throw new IllegalStateException("Peer connection was closed!");
		}
		return serviceFactory.serviceFactory.getBlockchainService();
	}

	@Override
	public BlockchainQueryService getQueryService(HashDigest ledgerHash) {
		PeerBlockchainServiceFactory serviceFactory = latestPeerServiceFactories.get(ledgerHash);
		if (serviceFactory == null) {
			return getQueryService();
		}
		return serviceFactory.getBlockchainService();
	}

	@Override
	public TransactionService getTransactionService() {
		// 交易始终使用连接账本最多的那个Factory
		PeerServiceFactory peerServiceFactory = mostLedgerPeerServiceFactory;
		if (peerServiceFactory == null) {
			// 再次尝试，直到获取一个可用的为止
			ledgerHashLock.lock();
			try {
				// double check
				if (mostLedgerPeerServiceFactory == null) {
					for (NetworkAddress peerAddress : peerAddresses) {
						try {
							PeerBlockchainServiceFactory peerBlockchainServiceFactory = PeerBlockchainServiceFactory
									.connect(gateWayKeyPair, peerAddress, credentialProviders, consensusClientManager);
							if (peerBlockchainServiceFactory != null) {
								mostLedgerPeerServiceFactory = new PeerServiceFactory(peerAddress,
										peerBlockchainServiceFactory);
								LOGGER.info("Most ledgers remote update to {}", peerAddress);
								return mostLedgerPeerServiceFactory.serviceFactory.getTransactionService();
							}
						} catch (Exception e) {
							LOGGER.warn("Update remote connect error !", e);
						}
					}
				}
			} finally {
				ledgerHashLock.unlock();
			}
			throw new IllegalStateException("Peer connection was closed!");
		}
		return peerServiceFactory.serviceFactory.getTransactionService();
	}

	@PreDestroy
	private void destroy() {
		close();
	}

	public void addPeerAddress(NetworkAddress peerAddress) {
		this.peerAddresses.add(peerAddress);
	}

	public void setGateWayKeyPair(AsymmetricKeypair gateWayKeyPair) {
		this.gateWayKeyPair = gateWayKeyPair;
	}

	@Override
	public EventListener getEventListener() {
		if (eventListener == null) {
			eventListener = new PullEventListener(getQueryService());
			eventListener.start();
		}
		return eventListener;
	}

	/**
	 * 更新本地账本缓存
	 */
	private void updateLedgerCache() {
		if (isConnected()) {
			HashDigest[] peerLedgerHashs = getQueryService().getLedgerHashs();
			if (peerLedgerHashs != null && peerLedgerHashs.length > 0) {
				localLedgerCache.addAll(Arrays.asList(peerLedgerHashs));
			}
		}
	}

	/**
	 * 创建定时线程池
	 *
	 * @return
	 */
	private ScheduledThreadPoolExecutor scheduledThreadPoolExecutor(final String nameFormat) {
		ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat(nameFormat).build();
		return new ScheduledThreadPoolExecutor(1, threadFactory, new ThreadPoolExecutor.AbortPolicy());
	}

	/**
	 * 定时任务处理器启动
	 *
	 */
	private void executorStart() {
		// 定时任务处理线程
		peerConnectExecutor.scheduleWithFixedDelay(new PeerConnectRunner(new AtomicLong(-1L)), 0,
				LEDGER_REFRESH_PERIOD_SECONDS, TimeUnit.SECONDS);
	}

	private class PeerServiceFactory {

		private NetworkAddress peerAddress;

		private PeerBlockchainServiceFactory serviceFactory;

		PeerServiceFactory(NetworkAddress peerAddress, PeerBlockchainServiceFactory serviceFactory) {
			this.peerAddress = peerAddress;
			this.serviceFactory = serviceFactory;
		}
	}

	private class PeerConnectRunner implements Runnable {

		private AtomicLong counter;

		public PeerConnectRunner(AtomicLong counter) {
			this.counter = counter;
		}

		@Override
		public void run() {
			// 包括几部分工作
			// 1、重连没有连接成功的Peer；
			// 2、从已经连接成功的Peer节点获取账本数量和最新的区块高度
			// 3、根据目前的情况更新缓存
			ledgerHashLock.lock();
			try {
				LOGGER.info("Start the connection check with the remote peer...... ");
				// 将远端的列表重新进行连接
				reconnect();
				// 更新账本数量最多的节点连接
				HashDigest[] ledgerHashs = updateMostLedgerPeerServiceFactory();
				if (ledgerHashs != null) {
					LOGGER.info("Most ledgers remote update to {}", mostLedgerPeerServiceFactory.peerAddress);
					// 更新每个账本对应获取最高区块的缓存
					updateLatestPeerServiceFactories(ledgerHashs);
				}

				// 调整到最后，避免在不满足2f+1个节点的场景下，每发送一次unorder消息都要等待响应超时，长时间的等待消息超时导致网关更新到对新节点的绑定无法执行，浏览器无法使用
				if (counter.getAndIncrement() % PEER_NETWORK_UPDATE_PERIOD == 0) {
					// 更新远端Peer管理网络列表
					updatePeerAddresses();
				}
			} catch (Exception e) {
				LOGGER.error("Peer Connect Task Error !!!", e);
			} finally {
				ledgerHashLock.unlock();
			}
		}

		/**
		 * 更新Peer远端的地址集合
		 *
		 */
		private void updatePeerAddresses() {
			if (peerBlockchainServiceFactories != null && !peerBlockchainServiceFactories.isEmpty()) {
				Set<NetworkAddress> totalRemotePeerAddresses = new HashSet<>();
				// 遍历该factory
				for (Map.Entry<NetworkAddress, PeerBlockchainServiceFactory> entry : peerBlockchainServiceFactories
						.entrySet()) {
					totalRemotePeerAddresses.add(entry.getKey());
					PeerBlockchainServiceFactory peerBlockchainServiceFactory = entry.getValue();
					Map<HashDigest, MonitorService> monitorServiceMap = peerBlockchainServiceFactory
							.getMonitorServiceMap();
					if (monitorServiceMap != null && !monitorServiceMap.isEmpty()) {
						for (Map.Entry<HashDigest, MonitorService> ey : monitorServiceMap.entrySet()) {
							MonitorService monitorService = ey.getValue();
							NodeNetworkAddresses nodeNetworkAddresses = monitorService.loadMonitors();
							if (nodeNetworkAddresses != null) {
								NodeNetworkAddress[] addressList = nodeNetworkAddresses.getNodeNetworkAddresses();

								if (addressList != null && addressList.length > 0) {
									List<NetworkAddress> addresses = new ArrayList<>();
									// 打印获取到的地址信息
									for (NodeNetworkAddress address : addressList) {
										LOGGER.info("Load remote peer network -> [{}:{}:{}] !", address.getHost(),
												address.getConsensusPort(), address.getMonitorPort());
										if (address.getMonitorPort() > 0) {
											addresses.add(
													new NetworkAddress(address.getHost(), address.getMonitorPort()));
										}
									}
									// 将该值加入到远端节点集合中
									totalRemotePeerAddresses.addAll(addresses);
								}
							}
						}
					}
				}
				// 更新peerAddress
				peerAddresses = totalRemotePeerAddresses;
			}
		}

		/**
		 * 更新可获取最新区块的连接工厂
		 *
		 * @param ledgerHashs 账本列表
		 */
		private void updateLatestPeerServiceFactories(HashDigest[] ledgerHashs) {
			Map<HashDigest, PeerBlockchainServiceFactory> blockHeightServiceFactories = new HashMap<>();
			for (HashDigest ledgerHash : ledgerHashs) {
				long blockHeight = -1L;
				PeerBlockchainServiceFactory serviceFactory = latestPeerServiceFactories.get(ledgerHash);
				try {
					if (serviceFactory != null) {
						blockHeight = serviceFactory.getBlockchainService().getLedger(ledgerHash)
								.getLatestBlockHeight();
						blockHeightServiceFactories.put(ledgerHash, serviceFactory);
					}
				} catch (Exception e) {
					latestPeerServiceFactories.remove(ledgerHash);
					serviceFactory = null;
					LOGGER.error("Peer get latest block height fail !!!", e);
				}

				// 查询其他所有节点对应的区块高度的情况
				NetworkAddress defaultPeerAddress = null, latestPeerAddress = null;
				Map<NetworkAddress, PeerBlockchainServiceFactory> tmpEntries = new ConcurrentHashMap<>();
				for (Map.Entry<NetworkAddress, PeerBlockchainServiceFactory> entry : peerBlockchainServiceFactories
						.entrySet()) {
					PeerBlockchainServiceFactory sf = entry.getValue();
					if (sf != serviceFactory) {
						try {
							long latestBlockHeight = sf.getBlockchainService().getLedger(ledgerHash)
									.getLatestBlockHeight();
							if (latestBlockHeight > blockHeight) {
								latestPeerAddress = entry.getKey();
								blockHeightServiceFactories.put(ledgerHash, sf);
							}
							blockHeight = Math.max(latestBlockHeight, blockHeight);
						} catch (Exception e) {
							try {
								boolean isNeedReconnect = false;
								// 需要判断是否具有当前账本，有的话，进行重连，没有的话就算了
								PeerBlockchainService blockchainService = sf.getBlockchainService();
								ledgerHashs = blockchainService.getLedgerHashsDirect();
								if (ledgerHashs != null) {
									for (HashDigest h : ledgerHashs) {
										if (h.equals(ledgerHash)) {
											// 确实存在对应的账本，则重连
											isNeedReconnect = true;
										}
									}
								}
								if (isNeedReconnect) {
									// 需要重连的话打印错误信息
									LOGGER.error(String.format("Peer[%s] get ledger[%s]'s latest block height fail !!!",
											entry.getKey(), ledgerHash.toBase58()), e);
									// 此错误是由于对端的节点没有重连导致，需要进行重连操作
									NetworkAddress peerAddress = entry.getKey();
									try {
										PeerBlockchainServiceFactory peerServiceFactory = PeerBlockchainServiceFactory
												.connect(gateWayKeyPair, peerAddress, credentialProviders,
														consensusClientManager);
										if (peerServiceFactory != null) {
											tmpEntries.put(peerAddress, peerServiceFactory);
										}
									} catch (Exception ee) {
										LOGGER.error(String.format("Peer[%s] reconnect fail !!!", entry.getKey()), e);
									}
								}
							} catch (Exception ee) {
								LOGGER.error(String.format("Peer[%s] get ledger[%s]'s latest block height fail !!!",
										entry.getKey(), ledgerHash.toBase58()), ee);
							}
						}
					} else {
						defaultPeerAddress = entry.getKey();
					}
				}
				if (!tmpEntries.isEmpty()) {
					peerBlockchainServiceFactories.putAll(tmpEntries);
				}
				LOGGER.info("Ledger[{}]'s master remote update to {}", ledgerHash.toBase58(),
						latestPeerAddress == null ? defaultPeerAddress : latestPeerAddress);
			}
			// 更新结果集
			latestPeerServiceFactories.putAll(blockHeightServiceFactories);
		}

		/**
		 * 之前未连接成功的Peer节点进行重连操作
		 *
		 */
		private void reconnect() {
			for (NetworkAddress peerAddress : peerAddresses) {
				if (!peerBlockchainServiceFactories.containsKey(peerAddress)) {
					// 重连指定节点
					try {
						PeerBlockchainServiceFactory peerServiceFactory = PeerBlockchainServiceFactory
								.connect(gateWayKeyPair, peerAddress, credentialProviders, consensusClientManager);
						if (peerServiceFactory != null) {
							peerBlockchainServiceFactories.put(peerAddress, peerServiceFactory);
						}
					} catch (Exception e) {
						LOGGER.error(String.format("Reconnect %s fail !!!", peerAddress), e);
					}
				}
			}

			// 如果网关启动时默认绑定的peer节点未启动，则从各个共识节点统计的peerAddresses地址列表为空，此时通过重连默认的绑定地址来补齐
			if (peerAddresses.size() == 0 && (!peerBlockchainServiceFactories.containsKey(defaultMasterAddress))) {

				LOGGER.info("Reconnect default master peer!");
				try {
					PeerBlockchainServiceFactory peerServiceFactory = PeerBlockchainServiceFactory
							.connect(gateWayKeyPair, defaultMasterAddress, credentialProviders, consensusClientManager);
					if (peerServiceFactory != null) {
						peerBlockchainServiceFactories.put(defaultMasterAddress, peerServiceFactory);
					}
				} catch (Exception e) {
					LOGGER.error(String.format("Reconnect default master peer %s fail !!!", defaultMasterAddress), e);
				}
			}
		}

		private HashDigest[] updateMostLedgerPeerServiceFactory() {
			int ledgerSize = -1;
			if (mostLedgerPeerServiceFactory == null) {
				// 更新一个最初级的
				initMostLedgerPeerServiceFactory();
				if (mostLedgerPeerServiceFactory == null) {
					// 更新完后若仍未空，则退出
					return null;
				}
			}
			HashDigest[] ledgerHashs = null;
			PeerBlockchainService blockchainService = mostLedgerPeerServiceFactory.serviceFactory
					.getBlockchainService();
			try {
				ledgerHashs = blockchainService.getLedgerHashsDirect();
				if (ledgerHashs != null) {
					ledgerSize = ledgerHashs.length;
					for (HashDigest h : ledgerHashs) {
						LOGGER.debug("Most peer[{}] get ledger direct [{}]", mostLedgerPeerServiceFactory.peerAddress,
								h.toBase58());
					}
				}
			} catch (Exception e) {
				// 连接失败的情况下清除该连接
				LOGGER.error(String.format("Connect %s fail !!!", mostLedgerPeerServiceFactory.peerAddress), e);
				peerBlockchainServiceFactories.remove(mostLedgerPeerServiceFactory.peerAddress);
				mostLedgerPeerServiceFactory = null;
				blockchainService = null;
			}
			PeerServiceFactory tempMostLedgerPeerServiceFactory = mostLedgerPeerServiceFactory;

			// 遍历，获取对应端的账本数量及最新的区块高度
			for (Map.Entry<NetworkAddress, PeerBlockchainServiceFactory> entry : peerBlockchainServiceFactories
					.entrySet()) {
				PeerBlockchainService loopBlockchainService = entry.getValue().getBlockchainService();
				if (loopBlockchainService != blockchainService) {
					// 处理账本数量
					try {
						HashDigest[] tempLedgerHashs = loopBlockchainService.getLedgerHashsDirect();
						if (tempLedgerHashs != null) {
							for (HashDigest h : tempLedgerHashs) {
								LOGGER.debug("Temp peer[{}] get ledger direct [{}]", entry.getKey(), h.toBase58());
							}
							if (tempLedgerHashs.length > ledgerSize) {
								tempMostLedgerPeerServiceFactory = new PeerServiceFactory(entry.getKey(),
										entry.getValue());
								ledgerHashs = tempLedgerHashs;
							}
						}
					} catch (Exception e) {
						LOGGER.error(String.format("%s get ledger hash fail !!!", entry.getKey()), e);
					}
				}
			}
			// 更新mostLedgerPeerServiceFactory
			mostLedgerPeerServiceFactory = tempMostLedgerPeerServiceFactory;
			return ledgerHashs;
		}

		private void initMostLedgerPeerServiceFactory() {
			// check
			if (mostLedgerPeerServiceFactory == null) {
				for (NetworkAddress peerAddress : peerAddresses) {
					try {
						PeerBlockchainServiceFactory peerBlockchainServiceFactory = PeerBlockchainServiceFactory
								.connect(gateWayKeyPair, peerAddress, credentialProviders, consensusClientManager);
						if (peerBlockchainServiceFactory != null) {
							mostLedgerPeerServiceFactory = new PeerServiceFactory(peerAddress,
									peerBlockchainServiceFactory);
							LOGGER.info("Most ledgers remote update to {}", peerAddress);
							// 默认使用第一个即可
							break;
						}
					} catch (Exception e) {
						LOGGER.warn("Update remote connect error !", e);
					}
				}
			}
		}
	}
}
