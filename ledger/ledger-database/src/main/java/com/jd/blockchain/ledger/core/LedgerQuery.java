package com.jd.blockchain.ledger.core;

import com.jd.blockchain.crypto.HashDigest;
import com.jd.blockchain.ledger.LedgerAdminInfo;
import com.jd.blockchain.ledger.LedgerAdminSettings;
import com.jd.blockchain.ledger.LedgerBlock;
import com.jd.blockchain.ledger.LedgerDataStructure;

public interface LedgerQuery {

	/**
	 * 账本哈希，这是账本的唯一标识；
	 * 
	 * @return
	 */
	HashDigest getHash();

	/**
	 * 账本结构版本；<br>
	 * 
	 * 如果未定义版本，则返回 -1；
	 * 
	 * @return
	 */
	long getVersion();

	/**
	 * 最新区块高度；
	 * 
	 * @return
	 */
	long getLatestBlockHeight();

	/**
	 * 最新区块哈希；
	 * 
	 * @return
	 */
	HashDigest getLatestBlockHash();

	/**
	 * 最新区块；
	 * 
	 * @return
	 */
	LedgerBlock getLatestBlock();

	/**
	 * 指定高度的区块哈希；
	 *
	 * @param height
	 * @return
	 */
	HashDigest getBlockHash(long height);

	/**
	 * 账本数据结构
	 *
	 *
	 * @return
	 */
	LedgerDataStructure getLedgerDataStructure();


	/**
	 * 指定高度的区块；
	 * 
	 * @param height
	 * @return
	 */
	LedgerBlock getBlock(long height);

	LedgerAdminInfo getAdminInfo();

	LedgerAdminInfo getAdminInfo(LedgerBlock block);

	LedgerAdminSettings getAdminSettings();

	LedgerAdminSettings getAdminSettings(LedgerBlock block);

	LedgerBlock getBlock(HashDigest hash);

	/**
	 * 查询账本数据；
	 * 
	 * 
	 * @param block
	 * @return
	 */
	LedgerDataSet getLedgerDataSet(LedgerBlock block);

	/**
	 * 查询账本事件；
	 * 
	 * @param block
	 * @return
	 */
	LedgerEventSet getLedgerEventSet(LedgerBlock block);

	/**
	 * 查询最新区块的账本数据；
	 * 
	 * @return
	 */
	default LedgerDataSet getLedgerDataSet() {
		return getLedgerDataSet(getLatestBlock());
	}
	
	/**
	 * 查询最新区块的账本事件；
	 * 
	 * @return
	 */
	default LedgerEventSet getLedgerEventSet() {
		return getLedgerEventSet(getLatestBlock());
	}

	TransactionSet getTransactionSet(LedgerBlock block);

	UserAccountSet getUserAccountSet(LedgerBlock block);

	DataAccountSet getDataAccountSet(LedgerBlock block);

	ContractAccountSet getContractAccountSet(LedgerBlock block);

	EventGroup getSystemEventGroup(LedgerBlock block);

	EventAccountSet getEventAccountSet(LedgerBlock block);

	default TransactionSet getTransactionSet() {
		return getTransactionSet(getLatestBlock());
	}

	default UserAccountSet getUserAccountSet() {
		return getUserAccountSet(getLatestBlock());
	}

	default DataAccountSet getDataAccountSet() {
		return getDataAccountSet(getLatestBlock());
	}

	default ContractAccountSet getContractAccountset() {
		return getContractAccountSet(getLatestBlock());
	}

	/**
	 * 重新检索最新区块，同时更新缓存；
	 * 
	 * @return
	 */
	LedgerBlock retrieveLatestBlock();

	/**
	 * 重新检索最新区块，同时更新缓存；
	 * 
	 * @return
	 */
	long retrieveLatestBlockHeight();

	/**
	 * 重新检索最新区块哈希，同时更新缓存；
	 * 
	 * @return
	 */
	HashDigest retrieveLatestBlockHash();

}