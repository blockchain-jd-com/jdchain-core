package com.jd.blockchain.ledger.core;

import com.jd.blockchain.ledger.BlockchainIdentity;
import com.jd.blockchain.ledger.MerkleProof;

import utils.Bytes;
import utils.SkippingIterator;

/**
 * {@link BaseAccountSet} 是一种只读列表；
 * 
 * @author huanghaiquan
 *
 * @param <T>
 */
public interface BaseAccountSet<T> extends MerkleProvable<Bytes> {

	/**
	 * 返回总数；
	 * 
	 * @return
	 */
	long getTotal();

	boolean contains(Bytes address);

	/**
	 * get proof of specified account;
	 */
	@Override
	MerkleProof getProof(Bytes address);

	/**
	 * 返回账户实例；
	 * 
	 * @param address Base58 格式的账户地址；
	 * @return 账户实例，如果不存在则返回 null；
	 */
	T getAccount(String address);

	T getAccount(Bytes address);

	T getAccount(Bytes address, long version);
	
	SkippingIterator<BlockchainIdentity> identityIterator();

}