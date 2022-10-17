package com.jd.blockchain.ledger.core;

import com.jd.binaryproto.BinaryProtocol;
import com.jd.blockchain.crypto.Crypto;
import com.jd.blockchain.crypto.HashDigest;
import com.jd.blockchain.crypto.HashFunction;
import com.jd.blockchain.ledger.BlockBody;
import com.jd.blockchain.ledger.CryptoSetting;
import com.jd.blockchain.ledger.LedgerAdminInfo;
import com.jd.blockchain.ledger.LedgerAdminSettings;
import com.jd.blockchain.ledger.LedgerBlock;
import com.jd.blockchain.ledger.LedgerDataSnapshot;
import com.jd.blockchain.ledger.LedgerDataStructure;
import com.jd.blockchain.ledger.LedgerInitSetting;
import com.jd.blockchain.ledger.LedgerSettings;
import com.jd.blockchain.ledger.TransactionRequest;
import com.jd.blockchain.ledger.cache.LedgerCache;
import com.jd.blockchain.ledger.cache.LedgerLRUCache;
import com.jd.blockchain.storage.service.ExPolicyKVStorage;
import com.jd.blockchain.storage.service.VersioningKVStorage;

import utils.Bytes;
import utils.codec.Base58Utils;
import utils.io.BytesUtils;

/**
 * 账本的存储结构： <br>
 *
 * 1、账本数据以版本化KV存储({@link VersioningKVStorage})为基础； <br>
 *
 * 2、以账本hash为 key，保存账本的每一个区块的hash，对应的版本序号恰好一致地表示了区块高度； <br>
 *
 * 3、区块数据以区块 hash 加上特定前缀({@link #BLOCK_PREFIX}) 构成 key
 * 进行保存，每个区块只有唯一个版本，在存储时会进行版本唯一性校验； <br>
 *
 * @author huanghaiquan
 *
 */
class LedgerRepositoryImpl implements LedgerRepository {

	private static final Bytes LEDGER_PREFIX = Bytes.fromString("IX" + LedgerConsts.KEY_SEPERATOR);

	private static final Bytes BLOCK_PREFIX = Bytes.fromString("BK" + LedgerConsts.KEY_SEPERATOR);

	private static final Bytes USER_SET_PREFIX = Bytes.fromString("US" + LedgerConsts.KEY_SEPERATOR);

	private static final Bytes DATA_SET_PREFIX = Bytes.fromString("DS" + LedgerConsts.KEY_SEPERATOR);

	private static final Bytes CONTRACT_SET_PREFIX = Bytes.fromString("CS" + LedgerConsts.KEY_SEPERATOR);

	private static final Bytes TRANSACTION_SET_PREFIX = Bytes.fromString("TS" + LedgerConsts.KEY_SEPERATOR);

	private static final Bytes SYSTEM_EVENT_SET_PREFIX = Bytes.fromString("SE" + LedgerConsts.KEY_SEPERATOR);

	private static final Bytes USER_EVENT_SET_PREFIX = Bytes.fromString("UE" + LedgerConsts.KEY_SEPERATOR);

	private static final AccountAccessPolicy DEFAULT_ACCESS_POLICY = new OpeningAccessPolicy();

	private HashDigest ledgerHash;

	private final String keyPrefix;

	private Bytes ledgerIndexKey;

	private VersioningKVStorage versioningStorage;

	private ExPolicyKVStorage exPolicyStorage;

	private volatile LedgerState latestState;

	private volatile LedgerEditor nextBlockEditor;

	private LedgerDataStructure dataStructure;

	private LedgerCache cacheService;

	/**
	 * 账本结构版本号
	 *         默认为-1，需通过MetaData获取
	 */
	private volatile long ledgerStructureVersion = -1L;

	private volatile boolean closed = false;

	public LedgerRepositoryImpl(HashDigest ledgerHash, String keyPrefix, ExPolicyKVStorage exPolicyStorage,
			VersioningKVStorage versioningStorage, LedgerDataStructure dataStructure) {
		this.keyPrefix = keyPrefix;
		this.ledgerHash = ledgerHash;
		this.versioningStorage = versioningStorage;
		this.exPolicyStorage = exPolicyStorage;
		this.ledgerIndexKey = encodeLedgerIndexKey(ledgerHash);
		this.dataStructure = dataStructure;
		this.cacheService = new LedgerLRUCache(ledgerHash);

		if (getLatestBlockHeight() < 0) {
			throw new RuntimeException("Ledger doesn't exist!");
		}

		retrieveLatestState();
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see com.jd.blockchain.ledger.core.LedgerRepository#getHash()
	 */
	@Override
	public HashDigest getHash() {
		return ledgerHash;
	}

	@Override
	public long getVersion() {
		return ledgerStructureVersion;
	}

	@Override
	public HashDigest getLatestBlockHash() {
		if (latestState == null) {
			return innerGetBlockHash(innerGetLatestBlockHeight());
		}
		return latestState.block.getHash();
	}

	@Override
	public long getLatestBlockHeight() {
		if (latestState == null) {
			return innerGetLatestBlockHeight();
		}
		return latestState.block.getHeight();
	}

	@Override
	public LedgerBlock getLatestBlock() {
		return latestState.block;
	}

	@Override
	public LedgerDataStructure getLedgerDataStructure() {
		return dataStructure;
	}
	/**
	 * 重新检索加载最新的状态；
	 *
	 * @return
	 */
	private LedgerState retrieveLatestState() {
		LedgerBlock latestBlock = innerGetBlock(innerGetLatestBlockHeight());
		LedgerDataSet ledgerDataset;
		TransactionSet txSet;
		LedgerEventSet ledgerEventset;

		ledgerDataset = innerGetLedgerDataset(latestBlock);
		txSet = loadTransactionSet(latestBlock.getHeight(), latestBlock.getTransactionSetHash(),
				((LedgerAdminDataSetEditor)(ledgerDataset.getAdminDataset())).getSettings().getCryptoSetting(), keyPrefix, exPolicyStorage,
				versioningStorage, dataStructure,true);
		ledgerEventset = innerGetLedgerEventSet(latestBlock);
		this.ledgerStructureVersion = ((LedgerAdminDataSetEditor)(ledgerDataset.getAdminDataset())).getMetadata().getLedgerStructureVersion();

		this.latestState = new LedgerState(latestBlock, ledgerDataset, txSet, ledgerEventset);

		return latestState;
	}

	@Override
	public LedgerBlock retrieveLatestBlock() {
		return retrieveLatestState().block;
	}

	@Override
	public HashDigest retrieveLatestBlockHash() {
		HashDigest latestBlockHash = innerGetBlockHash(innerGetLatestBlockHeight());
		if (latestState != null && !latestBlockHash.equals(latestState.block.getHash())) {
			latestState = null;
		}
		return latestBlockHash;
	}

	@Override
	public long retrieveLatestBlockHeight() {
		long latestBlockHeight = innerGetLatestBlockHeight();
		if (latestState != null && latestBlockHeight != latestState.block.getHeight()) {
			latestState = null;
		}
		return latestBlockHeight;
	}

	private long innerGetLatestBlockHeight() {
		return versioningStorage.getVersion(ledgerIndexKey);
	}

	@Override
	public HashDigest getBlockHash(long height) {
		LedgerBlock blk = latestState == null ? null : latestState.block;
		if (blk != null && height == blk.getHeight()) {
			return blk.getHash();
		}
		return innerGetBlockHash(height);
	}

	private HashDigest innerGetBlockHash(long height) {
		if (height < 0) {
			return null;
		}
		if (dataStructure.equals(LedgerDataStructure.MERKLE_TREE)) {
			// get block hash by height;
			byte[] hashBytes = versioningStorage.get(ledgerIndexKey, height);
			if (hashBytes == null || hashBytes.length == 0) {
				return null;
			}
			return Crypto.resolveAsHashDigest(hashBytes);
		} else {
			byte[] blockContent = versioningStorage.get(ledgerIndexKey, height);
			return deserialize(blockContent).getHash();
		}
	}

	@Override
	public LedgerBlock getBlock(long height) {
		LedgerBlock blk = latestState == null ? null : latestState.block;
		if (blk != null && height == blk.getHeight()) {
			return blk;
		}
		return innerGetBlock(height);
	}

	private LedgerBlock innerGetBlock(long height) {
		if (height < 0) {
			return null;
		}
		return innerGetBlock(innerGetBlockHash(height));
	}

	@Override
	public LedgerBlock getBlock(HashDigest blockHash) {
		LedgerBlock blk = latestState == null ? null : latestState.block;
		if (blk != null && blockHash.equals(blk.getHash())) {
			return blk;
		}
		return innerGetBlock(blockHash);
	}

	private LedgerBlock innerGetBlock(HashDigest blockHash) {
		if (blockHash == null) {
			return null;
		}
		Bytes key = encodeBlockStorageKey(blockHash);
		// Every one block has only one version;
		byte[] blockBytes = versioningStorage.get(key, 0);
		if(null == blockBytes) {
			return null;
		}
		LedgerBlockData block;
		if (dataStructure.equals(LedgerDataStructure.MERKLE_TREE)) {
			block = new LedgerBlockData(deserialize(blockBytes));
		} else {
			long blockHeight = BytesUtils.toLong(blockBytes);
			byte[] blockContent =  versioningStorage.get(LEDGER_PREFIX, blockHeight);
			block = new LedgerBlockData(deserialize(blockContent));
		}

		if (!blockHash.equals(block.getHash())) {
			throw new RuntimeException("Block hash not equals to it's storage key!");
		}

		// verify block hash;
		byte[] blockBodyBytes = null;
		if (block.getHeight() == 0) {
			// 计算创世区块的 hash 时，不包括 ledgerHash 字段；
			blockBodyBytes = BinaryProtocol.encode(block, BlockBody.class);
		} else {
			blockBodyBytes = BinaryProtocol.encode(block, BlockBody.class);
		}
		HashFunction hashFunc = Crypto.getHashFunction(blockHash.getAlgorithm());
		boolean pass = hashFunc.verify(blockHash, blockBodyBytes);
		if (!pass) {
			throw new RuntimeException("Block hash verification fail!");
		}

		// verify height;
		HashDigest indexedHash = getBlockHash(block.getHeight());
		if (indexedHash == null || !indexedHash.equals(blockHash)) {
			throw new RuntimeException(
					"Illegal ledger state in storage that ledger height index doesn't match it's block data in height["
							+ block.getHeight() + "] and block hash[" + Base58Utils.encode(blockHash.toBytes())
							+ "] !");
		}

		return block;
	}

	/**
	 * 获取最新区块的账本参数；
	 *
	 * @return
	 */
	private LedgerSettings getLatestSettings() {
		return getAdminInfo().getSettings();
	}

	@Override
	public LedgerAdminInfo getAdminInfo() {
		return createAdminData(getLatestBlock());
	}

	private LedgerBlock deserialize(byte[] blockBytes) {
		return BinaryProtocol.decode(blockBytes);
	}

	@Override
	public TransactionSet getTransactionSet(LedgerBlock block) {
		long height = getLatestBlockHeight();
		if (height == block.getHeight()) {
			// 从缓存中返回最新区块的数据集；
			return latestState.getTransactionSet();
		}
		LedgerAdminInfo adminAccount = getAdminInfo(block);
		// All of existing block is readonly;
		return loadTransactionSet(block.getHeight(), block.getTransactionSetHash(), adminAccount.getSettings().getCryptoSetting(),
				keyPrefix, exPolicyStorage, versioningStorage, dataStructure, true);
	}

	@Override
	public LedgerAdminInfo getAdminInfo(LedgerBlock block) {
		return createAdminData(block);
	}

	@Override
	public LedgerAdminSettings getAdminSettings() {
		return getAdminSettings(getLatestBlock());
	}

	@Override
	public LedgerAdminSettings getAdminSettings(LedgerBlock block) {
		long height = getLatestBlockHeight();
		if (height == block.getHeight()) {
			return (LedgerAdminSettings) latestState.getAdminDataset();
		}

		return  createAdminDataset(block);
	}

	@Override
	public LedgerDiffView getDiffView(LedgerBlock recentBlock, LedgerBlock previousBlock) {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * 生成LedgerAdminInfoData对象
	 *     该对象主要用于页面展示
	 *
	 * @param block
	 * @return
	 */
	private LedgerAdminInfoData createAdminData(LedgerBlock block) {
		return new LedgerAdminInfoData(createAdminDataset(block));
	}

	/**
	 * 生成LedgerAdminDataset对象
	 *
	 * @param block
	 * @return
	 */
	private LedgerAdminDataSetEditor createAdminDataset(LedgerBlock block) {
		return new LedgerAdminDataSetEditor(block.getHeight(), block.getAdminAccountHash(), keyPrefix, exPolicyStorage, versioningStorage, dataStructure, cacheService.getAdminCache(), true);
	}

	@Override
	public UserAccountSet getUserAccountSet(LedgerBlock block) {
		long height = getLatestBlockHeight();
		if (height == block.getHeight()) {
			return latestState.getUserAccountSet();
		}
		LedgerAdminSettings adminAccount = getAdminSettings(block);
		return createUserAccountSet(block, adminAccount.getSettings().getCryptoSetting());
	}

	private UserAccountSetEditor createUserAccountSet(LedgerBlock block, CryptoSetting cryptoSetting) {
		return loadUserAccountSet(block.getHeight(), block.getUserAccountSetHash(), cryptoSetting, keyPrefix, exPolicyStorage,
				versioningStorage, dataStructure, cacheService, true);
	}

	@Override
	public DataAccountSet getDataAccountSet(LedgerBlock block) {
		long height = getLatestBlockHeight();
		if (height == block.getHeight()) {
			return latestState.getDataAccountSet();
		}

		LedgerAdminSettings adminAccount = getAdminSettings(block);
		return createDataAccountSet(block, adminAccount.getSettings().getCryptoSetting());
	}

	private DataAccountSetEditor createDataAccountSet(LedgerBlock block, CryptoSetting setting) {
		return loadDataAccountSet(block.getHeight(), block.getDataAccountSetHash(), setting, keyPrefix, exPolicyStorage, versioningStorage,
				dataStructure, cacheService, true);
	}

	@Override
	public ContractAccountSet getContractAccountSet(LedgerBlock block) {
		long height = getLatestBlockHeight();
		if (height == block.getHeight()) {
			return latestState.getContractAccountSet();
		}

		LedgerAdminSettings adminAccount = getAdminSettings(block);
		return createContractAccountSet(block, adminAccount.getSettings().getCryptoSetting());
	}

	@Override
	public EventGroup getSystemEventGroup(LedgerBlock block) {
		long height = getLatestBlockHeight();
		if (height == block.getHeight()) {
			return latestState.getLedgerEventSet().getSystemEventGroup();
		}

		LedgerAdminSettings adminAccount = getAdminSettings(block);
		return createSystemEventSet(block, adminAccount.getSettings().getCryptoSetting());
	}

	private EventGroupPublisher createSystemEventSet(LedgerBlock block, CryptoSetting cryptoSetting) {
		return loadSystemEventSet(block.getHeight(), block.getSystemEventSetHash(), cryptoSetting, keyPrefix, exPolicyStorage,
				versioningStorage, dataStructure, true);
	}

	@Override
	public EventAccountSet getEventAccountSet(LedgerBlock block) {
		long height = getLatestBlockHeight();
		if (height == block.getHeight()) {
			return latestState.getLedgerEventSet().getEventAccountSet();
		}

		LedgerAdminSettings adminAccount = getAdminSettings(block);
		return createUserEventSet(block, adminAccount.getSettings().getCryptoSetting());
	}

	private EventAccountSetEditor createUserEventSet(LedgerBlock block, CryptoSetting cryptoSetting) {
		return loadUserEventSet(block.getHeight(), block.getUserEventSetHash(), cryptoSetting, keyPrefix, exPolicyStorage,
				versioningStorage,  dataStructure,cacheService, true);
	}

	private ContractAccountSetEditor createContractAccountSet(LedgerBlock block, CryptoSetting cryptoSetting) {
		return loadContractAccountSet(block.getHeight(), block.getContractAccountSetHash(), cryptoSetting, keyPrefix, exPolicyStorage,
				versioningStorage, dataStructure,cacheService, true);
	}

	@Override
	public LedgerDataSet getLedgerDataSet(LedgerBlock block) {
		long height = getLatestBlockHeight();
		if (height == block.getHeight()) {
			return latestState.getLedgerDataset();
		}

		return innerGetLedgerDataset(block);
	}

	@Override
	public LedgerEventSet getLedgerEventSet(LedgerBlock block) {
		long height = getLatestBlockHeight();
		if (height == block.getHeight()) {
			return latestState.getLedgerEventSet();
		}

		// All of existing block is readonly;
		return innerGetLedgerEventSet(block);
	}

	private LedgerDataSetEditor innerGetLedgerDataset(LedgerBlock block) {
		LedgerAdminDataSetEditor adminDataset = createAdminDataset(block);
		CryptoSetting cryptoSetting = adminDataset.getSettings().getCryptoSetting();

		UserAccountSetEditor userAccountSet = createUserAccountSet(block, cryptoSetting);
		DataAccountSetEditor dataAccountSet = createDataAccountSet(block, cryptoSetting);
		ContractAccountSetEditor contractAccountSet = createContractAccountSet(block, cryptoSetting);
		return new LedgerDataSetEditor(adminDataset, userAccountSet, dataAccountSet, contractAccountSet, true);
	}

	private LedgerEventSetEditor innerGetLedgerEventSet(LedgerBlock block) {
		LedgerAdminDataSetEditor adminDataset = createAdminDataset(block);
		CryptoSetting cryptoSetting = adminDataset.getSettings().getCryptoSetting();

		EventGroupPublisher systemEventSet = createSystemEventSet(block, cryptoSetting);
		EventAccountSetEditor userEventSet = createUserEventSet(block, cryptoSetting);
		return new LedgerEventSetEditor(systemEventSet, userEventSet, true);
	}

	public synchronized void resetNextBlockEditor() {
		this.nextBlockEditor = null;
	}

	@Override
	public synchronized LedgerEditor createNextBlock() {
		LedgerEditor editor;

		if (closed) {
			throw new RuntimeException("Ledger repository has been closed!");
		}
		if (this.nextBlockEditor != null) {
			throw new RuntimeException(
					"A new block is in process, cann't create another one until it finish by committing or canceling.");
		}
		LedgerBlock previousBlock = getLatestBlock();

		editor = LedgerTransactionalEditor.createEditor(previousBlock, getLatestSettings(),
				keyPrefix, exPolicyStorage, versioningStorage, dataStructure, cacheService);

		NewBlockCommittingMonitor committingMonitor = new NewBlockCommittingMonitor(editor, this);
		this.nextBlockEditor = committingMonitor;
		return committingMonitor;
	}

	@Override
	public LedgerEditor getNextBlockEditor() {
		return nextBlockEditor;
	}

	@Override
	public LedgerSecurityManager getSecurityManager() {
		LedgerBlock ledgerBlock = getLatestBlock();

		LedgerDataSet ledgerDataQuery = getLedgerDataSet(ledgerBlock);
		LedgerAdminDataSet previousAdminDataset = ledgerDataQuery.getAdminDataset();
		LedgerSecurityManager securityManager = new LedgerSecurityManagerImpl(previousAdminDataset.getAdminSettings(), previousAdminDataset.getParticipantDataset(),
				ledgerDataQuery.getUserAccountSet());
		return securityManager;
	}

	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		if (this.nextBlockEditor != null) {
			throw new RuntimeException("A new block is in process, cann't close the ledger repository!");
		}
		cacheService.clear();
		closed = true;
	}

	static Bytes encodeLedgerIndexKey(HashDigest ledgerHash) {
//		return LEDGER_PREFIX.concat(ledgerHash);
		return LEDGER_PREFIX;
	}

	static Bytes encodeBlockStorageKey(HashDigest blockHash) {
		return BLOCK_PREFIX.concat(blockHash);
	}

	static LedgerDataSetEditor newDataSet(LedgerInitSetting initSetting, String keyPrefix,
			ExPolicyKVStorage ledgerExStorage, VersioningKVStorage ledgerVerStorage, LedgerDataStructure dataStructure, LedgerCache cacheService) {
		LedgerAdminDataSetEditor adminAccount = new LedgerAdminDataSetEditor(initSetting, keyPrefix, ledgerExStorage,
				ledgerVerStorage, cacheService.getAdminCache());

		String usersetKeyPrefix = keyPrefix + USER_SET_PREFIX;
		String datasetKeyPrefix = keyPrefix + DATA_SET_PREFIX;
		String contractsetKeyPrefix = keyPrefix + CONTRACT_SET_PREFIX;

		UserAccountSetEditor userAccountSet = new UserAccountSetEditor(adminAccount.getSettings().getCryptoSetting(),
				usersetKeyPrefix, ledgerExStorage, ledgerVerStorage, DEFAULT_ACCESS_POLICY, dataStructure, cacheService.getUserCache());

		DataAccountSetEditor dataAccountSet = new DataAccountSetEditor(adminAccount.getSettings().getCryptoSetting(),
				datasetKeyPrefix, ledgerExStorage, ledgerVerStorage, DEFAULT_ACCESS_POLICY, dataStructure, cacheService.getDataAccountCache());

		ContractAccountSetEditor contractAccountSet = new ContractAccountSetEditor(adminAccount.getSettings().getCryptoSetting(),
				contractsetKeyPrefix, ledgerExStorage, ledgerVerStorage, DEFAULT_ACCESS_POLICY, dataStructure, cacheService.getContractCache());

		LedgerDataSetEditor newDataSet = new LedgerDataSetEditor(adminAccount, userAccountSet, dataAccountSet,
				contractAccountSet, false);

		return newDataSet;
	}

	static LedgerEventSetEditor newEventSet(CryptoSetting cryptoSetting, String keyPrefix,
									ExPolicyKVStorage ledgerExStorage, VersioningKVStorage ledgerVerStorage,
											LedgerDataStructure dataStructure, LedgerCache cacheService) {

		EventGroupPublisher systemEventSet = new EventGroupPublisher(cryptoSetting,
				keyPrefix + SYSTEM_EVENT_SET_PREFIX, ledgerExStorage, ledgerVerStorage, dataStructure);

		EventAccountSetEditor userEventSet = new EventAccountSetEditor(cryptoSetting,
				keyPrefix + USER_EVENT_SET_PREFIX, ledgerExStorage, ledgerVerStorage, DEFAULT_ACCESS_POLICY, dataStructure, cacheService.getEventAccountCache());

		LedgerEventSetEditor newEventSet = new LedgerEventSetEditor(systemEventSet, userEventSet, false);

		return newEventSet;
	}

	static TransactionSetEditor newTransactionSet(CryptoSetting cryptoSetting, String keyPrefix,
			ExPolicyKVStorage ledgerExStorage, VersioningKVStorage ledgerVerStorage, LedgerDataStructure dataStructure) {

		String txsetKeyPrefix = keyPrefix + TRANSACTION_SET_PREFIX;

		TransactionSetEditor transactionSet = new TransactionSetEditor(cryptoSetting, txsetKeyPrefix,
				ledgerExStorage, ledgerVerStorage, dataStructure);
		return transactionSet;
	}

	static LedgerDataSetEditor loadDataSet(long preBlockHeight, LedgerDataSnapshot dataSnapshot, CryptoSetting cryptoSetting, String keyPrefix,
			ExPolicyKVStorage ledgerExStorage, VersioningKVStorage ledgerVerStorage, LedgerDataStructure dataStructure, LedgerCache cacheService, boolean readonly) {
		LedgerAdminDataSetEditor adminAccount = new LedgerAdminDataSetEditor(preBlockHeight, dataSnapshot.getAdminAccountHash(), keyPrefix,
				ledgerExStorage, ledgerVerStorage, dataStructure, cacheService.getAdminCache(), readonly);

		UserAccountSetEditor userAccountSet = loadUserAccountSet(preBlockHeight, dataSnapshot.getUserAccountSetHash(), cryptoSetting,
				keyPrefix, ledgerExStorage, ledgerVerStorage, dataStructure, cacheService, readonly);

		DataAccountSetEditor dataAccountSet = loadDataAccountSet(preBlockHeight, dataSnapshot.getDataAccountSetHash(), cryptoSetting,
				keyPrefix, ledgerExStorage, ledgerVerStorage, dataStructure, cacheService, readonly);

		ContractAccountSetEditor contractAccountSet = loadContractAccountSet(preBlockHeight, dataSnapshot.getContractAccountSetHash(),
				cryptoSetting, keyPrefix, ledgerExStorage, ledgerVerStorage, dataStructure, cacheService, readonly);

		LedgerDataSetEditor dataset = new LedgerDataSetEditor(adminAccount, userAccountSet, dataAccountSet,
				contractAccountSet, readonly);

		return dataset;
	}

	static LedgerEventSetEditor loadEventSet(long preBlockHeight, LedgerDataSnapshot dataSnapshot, CryptoSetting cryptoSetting, String keyPrefix,
									   ExPolicyKVStorage ledgerExStorage, VersioningKVStorage ledgerVerStorage, LedgerDataStructure dataStructure, LedgerCache cacheService,  boolean readonly) {

		EventGroupPublisher systemEventSet = loadSystemEventSet(preBlockHeight, dataSnapshot.getSystemEventSetHash(), cryptoSetting,
				keyPrefix, ledgerExStorage, ledgerVerStorage, dataStructure, readonly);
		EventAccountSetEditor userEventSet = loadUserEventSet(preBlockHeight, dataSnapshot.getUserEventSetHash(), cryptoSetting,
				keyPrefix, ledgerExStorage, ledgerVerStorage, dataStructure, cacheService, readonly);
		LedgerEventSetEditor newEventSet = new LedgerEventSetEditor(systemEventSet, userEventSet, false);

		return newEventSet;
	}

	static UserAccountSetEditor loadUserAccountSet(long preBlockHeight, HashDigest userAccountSetHash, CryptoSetting cryptoSetting,
			String keyPrefix, ExPolicyKVStorage ledgerExStorage, VersioningKVStorage ledgerVerStorage,
												   LedgerDataStructure dataStructure, LedgerCache cacheService, boolean readonly) {

		String usersetKeyPrefix = keyPrefix + USER_SET_PREFIX;
		return new UserAccountSetEditor(preBlockHeight, userAccountSetHash, cryptoSetting, usersetKeyPrefix, ledgerExStorage,
				ledgerVerStorage, readonly, dataStructure, cacheService.getUserCache(), DEFAULT_ACCESS_POLICY);
	}

	static DataAccountSetEditor loadDataAccountSet(long preBlockHeight, HashDigest dataAccountSetHash, CryptoSetting cryptoSetting,
			String keyPrefix, ExPolicyKVStorage ledgerExStorage, VersioningKVStorage ledgerVerStorage,
												   LedgerDataStructure dataStructure, LedgerCache cacheService, boolean readonly) {

		String datasetKeyPrefix = keyPrefix + DATA_SET_PREFIX;
		return new DataAccountSetEditor(preBlockHeight, dataAccountSetHash, cryptoSetting, datasetKeyPrefix, ledgerExStorage,
				ledgerVerStorage, readonly, dataStructure, cacheService.getDataAccountCache(), DEFAULT_ACCESS_POLICY);
	}

	static ContractAccountSetEditor loadContractAccountSet(long preBlockHeight, HashDigest contractAccountSetHash, CryptoSetting cryptoSetting,
			String keyPrefix, ExPolicyKVStorage ledgerExStorage, VersioningKVStorage ledgerVerStorage,
														   LedgerDataStructure dataStructure, LedgerCache cacheService, boolean readonly) {

		String contractsetKeyPrefix = keyPrefix + CONTRACT_SET_PREFIX;
		return new ContractAccountSetEditor(preBlockHeight, contractAccountSetHash, cryptoSetting, contractsetKeyPrefix, ledgerExStorage,
				ledgerVerStorage, readonly, dataStructure, cacheService.getContractCache(), DEFAULT_ACCESS_POLICY);
	}

	static TransactionSetEditor loadTransactionSet(long preBlockHeight, HashDigest txsetHash, CryptoSetting cryptoSetting, String keyPrefix,
			ExPolicyKVStorage ledgerExStorage, VersioningKVStorage ledgerVerStorage, LedgerDataStructure dataStructure, boolean readonly) {

		String txsetKeyPrefix = keyPrefix + TRANSACTION_SET_PREFIX;
		return new TransactionSetEditor(preBlockHeight, txsetHash, cryptoSetting, txsetKeyPrefix, ledgerExStorage, ledgerVerStorage, dataStructure,
				readonly);

	}

	static EventGroupPublisher loadSystemEventSet(long preBlockHeight, HashDigest systemEventSetHash, CryptoSetting cryptoSetting,
                                                  String keyPrefix, ExPolicyKVStorage ledgerExStorage, VersioningKVStorage ledgerVerStorage,
                                                  LedgerDataStructure dataStructure, boolean readonly) {
		return new EventGroupPublisher(preBlockHeight, systemEventSetHash, cryptoSetting, keyPrefix+ SYSTEM_EVENT_SET_PREFIX, ledgerExStorage,
				ledgerVerStorage, dataStructure, readonly);
	}

	static EventAccountSetEditor loadUserEventSet(long preBlockHeight, HashDigest eventAccountSetHash, CryptoSetting cryptoSetting,
											String keyPrefix, ExPolicyKVStorage ledgerExStorage, VersioningKVStorage ledgerVerStorage,
											LedgerDataStructure dataStructure, LedgerCache cacheService, boolean readonly) {

		return new EventAccountSetEditor(preBlockHeight, eventAccountSetHash, cryptoSetting, keyPrefix + USER_EVENT_SET_PREFIX, ledgerExStorage,
				ledgerVerStorage, readonly, dataStructure, cacheService.getEventAccountCache(), DEFAULT_ACCESS_POLICY);
	}

	private static class NewBlockCommittingMonitor implements LedgerEditor {

		private LedgerEditor editor;

		private LedgerRepositoryImpl ledgerRepo;

		public NewBlockCommittingMonitor(LedgerEditor editor, LedgerRepositoryImpl ledgerRepo) {
			this.editor = editor;
			this.ledgerRepo = ledgerRepo;
		}

		@Override
		public HashDigest getLedgerHash() {
			return editor.getLedgerHash();
		}

		@Override
		public long getBlockHeight() {
			return editor.getBlockHeight();
		}

		@Override
		public LedgerBlock getCurrentBlock() {
			return editor.getCurrentBlock();
		}

		@Override
		public LedgerDataSetEditor getLedgerDataset() {
			return (LedgerDataSetEditor) editor.getLedgerDataset();
		}

		@Override
		public LedgerEventSetEditor getLedgerEventSet() {
			return (LedgerEventSetEditor) editor.getLedgerEventSet();
		}

		@Override
		public TransactionSetEditor getTransactionSet() {
			return (TransactionSetEditor) editor.getTransactionSet();
		}

		@Override
		public LedgerTransactionContext newTransaction(TransactionRequest txRequest) {
			return editor.newTransaction(txRequest);
		}

		@Override
		public LedgerBlock prepare() {
			return editor.prepare();
		}

		@Override
		public void commit() {
			try {
				editor.commit();
				LedgerBlock latestBlock = editor.getCurrentBlock();
				ledgerRepo.latestState = new LedgerState(latestBlock, editor.getLedgerDataset(),
						editor.getTransactionSet(), editor.getLedgerEventSet());
			} finally {
				ledgerRepo.nextBlockEditor = null;
			}
		}

		@Override
		public void cancel() {
			try {
				editor.cancel();
			} finally {
				ledgerRepo.nextBlockEditor = null;
			}
		}

	}

	/**
	 * 维护账本某个区块的数据状态的缓存结构；
	 *
	 * @author huanghaiquan
	 *
	 */
	private static class LedgerState {

		private final LedgerBlock block;

		private final TransactionSet transactionSet;

		private final LedgerDataSet ledgerDataset;

		private final LedgerEventSet ledgerEventSet;

		public LedgerState(LedgerBlock block, LedgerDataSet ledgerDataset, TransactionSet transactionSet, LedgerEventSet ledgerEventSet) {
			this.block = block;
			this.ledgerDataset = ledgerDataset;
			this.transactionSet = transactionSet;
			this.ledgerEventSet = ledgerEventSet;

		}

		public LedgerAdminDataSet getAdminDataset() {
			return ledgerDataset.getAdminDataset();
		}

		public LedgerDataSet getLedgerDataset() {
			return ledgerDataset;
		}

		public ContractAccountSet getContractAccountSet() {
			return ledgerDataset.getContractAccountSet();
		}

		public DataAccountSet getDataAccountSet() {
			return ledgerDataset.getDataAccountSet();
		}

		public UserAccountSet getUserAccountSet() {
			return ledgerDataset.getUserAccountSet();
		}

		public TransactionSet getTransactionSet() {
			return transactionSet;
		}

		public LedgerEventSet getLedgerEventSet() {
			return ledgerEventSet;
		}

	}

}
