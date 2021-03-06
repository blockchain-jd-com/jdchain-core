package com.jd.blockchain.gateway;

import java.util.Set;

import com.jd.blockchain.crypto.AsymmetricKeypair;
import com.jd.blockchain.crypto.HashDigest;

import utils.net.NetworkAddress;

public interface PeerConnector {

	/**
	 * 获取Peer地址列表
	 *
	 * @return
	 */
	Set<NetworkAddress> getPeerAddresses();

	/**
	 * 是否连接成功
	 *
	 * @return
	 */
	boolean isConnected();

	/**
	 * 共识客户端视图落后时进行重连操作；
	 *
	 * @return
	 */
	void reconnect(HashDigest ledgerHash);

	/**
	 * 连接至指定Peer节点
	 *
	 * @param peerAddress
	 *             Peer地址
	 * @param defaultKeyPair
	 *             连接Peer所需公私钥信息
	 * @param peerProviders
	 *             支持的Provider解析列表
	 */
	void connect(NetworkAddress peerAddress, AsymmetricKeypair defaultKeyPair);

	/**
	 * 设置网关配置的默认连接Peer Http 地址
	 *
	 * @return void
	 */
	void setMasterPeer(NetworkAddress defaultMasterAddress);

	/**
	 * 监控重连，判断是否需要更新账本信息，再进行重连操作
	 * Peer地址及其他信息见${@link PeerConnector#connect(utils.net.NetworkAddress, com.jd.blockchain.crypto.AsymmetricKeypair, java.util.List)}
	 *
	 */
	void monitorAndReconnect();

	/**
	 * 关闭连接
	 *
	 */
	void close();
}
