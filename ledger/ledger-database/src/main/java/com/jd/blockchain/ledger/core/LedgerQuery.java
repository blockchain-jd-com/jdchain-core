package com.jd.blockchain.ledger.core;

import com.jd.blockchain.crypto.HashDigest;
import com.jd.blockchain.ledger.LedgerAdminInfo;
import com.jd.blockchain.ledger.LedgerAdminSettings;
import com.jd.blockchain.ledger.LedgerBlock;

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
	LedgerDataQuery getLedgerData(LedgerBlock block);

	/**
	 * 查询账本事件；
	 * 
	 * @param block
	 * @return
	 */
	LedgerEventQuery getLedgerEvents(LedgerBlock block);

	/**
	 * 查询最新区块的账本数据；
	 * 
	 * @return
	 */
	default LedgerDataQuery getLedgerData() {
		return getLedgerData(getLatestBlock());
	}
	
	/**
	 * 查询最新区块的账本事件；
	 * 
	 * @return
	 */
	default LedgerEventQuery getLedgerEvents() {
		return getLedgerEvents(getLatestBlock());
	}

	TransactionQuery getTransactionSet(LedgerBlock block);

	UserAccountCollection getUserAccountSet(LedgerBlock block);

	DataAccountCollection getDataAccountSet(LedgerBlock block);

	ContractAccountCollection getContractAccountSet(LedgerBlock block);

	EventGroup getSystemEvents(LedgerBlock block);

	EventAccountCollection getUserEvents(LedgerBlock block);

	default TransactionQuery getTransactionSet() {
		return getTransactionSet(getLatestBlock());
	}

	default UserAccountCollection getUserAccountSet() {
		return getUserAccountSet(getLatestBlock());
	}

	default DataAccountCollection getDataAccountSet() {
		return getDataAccountSet(getLatestBlock());
	}

	default ContractAccountCollection getContractAccountset() {
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