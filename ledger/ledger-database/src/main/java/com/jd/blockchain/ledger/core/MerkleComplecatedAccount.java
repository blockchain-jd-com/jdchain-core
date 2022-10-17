package com.jd.blockchain.ledger.core;

import com.jd.binaryproto.BinaryProtocol;
import com.jd.blockchain.crypto.HashDigest;
import com.jd.blockchain.crypto.PubKey;
import com.jd.blockchain.ledger.BlockchainIdentity;
import com.jd.blockchain.ledger.BytesValue;
import com.jd.blockchain.ledger.CryptoSetting;
import com.jd.blockchain.ledger.LedgerException;
import com.jd.blockchain.ledger.AccountSnapshot;
import com.jd.blockchain.ledger.MerkleProof;
import com.jd.blockchain.ledger.TypedValue;
import com.jd.blockchain.ledger.cache.PubkeyCache;
import com.jd.blockchain.ledger.core.DatasetHelper.DataChangedListener;
import com.jd.blockchain.ledger.core.DatasetHelper.TypeMapper;
import com.jd.blockchain.storage.service.ExPolicyKVStorage;
import com.jd.blockchain.storage.service.VersioningKVStorage;

import utils.Bytes;
import utils.Dataset;
import utils.Transactional;

/**
 * 事务性的基础账户；
 *
 * @author huanghaiquan
 *
 */
public class MerkleComplecatedAccount implements CompositeAccount, HashProvable, AccountSnapshot, Transactional {

    private static final Bytes HEADER_PREFIX = Bytes.fromString("H/");
    private static final Bytes DATA_PREFIX = Bytes.fromString("D/");

//	private static final Bytes KEY_HEADER_ROOT = Bytes.fromString("HEADER");

//	private static final Bytes KEY_DATA_ROOT = Bytes.fromString("DATA");

    private static final String KEY_PUBKEY = "PK";

    private BlockchainIdentity accountID;

//	private BaseDataset<Bytes, byte[]> rootDataset;

    private BaseDataset<Bytes, byte[]> headerDataset;

    private BaseDataset<Bytes, byte[]> dataDataset;

    private BaseDataset<String, TypedValue> typedHeader;

    private BaseDataset<String, TypedValue> typedData;

    private PubkeyCache pubkeyCache;

    /**
     * Create a new Account with the specified identity(address and pubkey); <br>
     *
     * At the same time, a empty merkle dataset is also created for this account,
     * which is used for storing data of this account.<br>
     *
     * This new account will be writable. <br>
     *
     * @param accountID     Identity of this new account;
     * @param cryptoSetting Settings about crypto operations；
     * @param keyPrefix     Prefix of all keys in this account's dataset;
     * @param exStorage     The base storage for existance operation;
     * @param verStorage    The base storage for versioning operation;
     */
    public MerkleComplecatedAccount(BlockchainIdentity accountID, CryptoSetting cryptoSetting, Bytes keyPrefix,
                                    ExPolicyKVStorage exStorage, VersioningKVStorage verStorage, PubkeyCache pubkeyCache) {
        this.pubkeyCache = pubkeyCache;
        // 初始化数据集；
        initializeDatasets(null, null, cryptoSetting, keyPrefix, exStorage, verStorage, false);

        initPubKey(accountID.getPubKey());
        this.accountID = accountID;
    }

    /**
     * Create a account instance with the specified address and root hash; load it's
     * merkle dataset from the specified root hash. This merkle dateset is used for
     * storing data of this account.<br>
     *
     * @param address       Address of this account;
     * @param rootHash      Merkle root hash of this account; It can not be null;
     * @param cryptoSetting Settings about crypto operations；
     * @param keyPrefix     Prefix of all keys in this account's dataset;
     * @param exStorage     The base storage for existance operation;
     * @param verStorage    The base storage for versioning operation;
     * @param readonly      Readonly about this account's dataset;
     */
    public MerkleComplecatedAccount(Bytes address, HashDigest headerRoot, HashDigest dataRoot, CryptoSetting cryptoSetting,
                                    Bytes keyPrefix, ExPolicyKVStorage exStorage, VersioningKVStorage verStorage, PubkeyCache pubkeyCache, boolean readonly) {
        this.pubkeyCache = pubkeyCache;
        if (headerRoot == null) {
            throw new IllegalArgumentException(
                    "Specified a null header-root hash for account[" + address.toBase58() + "]!");
        }
        if (dataRoot == null) {
            throw new IllegalArgumentException(
                    "Specified a null data-root hash for account[" + address.toBase58() + "]!");
        }

        // 初始化数据集；
        initializeDatasets(headerRoot, dataRoot, cryptoSetting, keyPrefix, exStorage, verStorage, readonly);

        // 初始化账户的身份；
        PubKey pubKey = loadPubKey(address);
        this.accountID = new AccountID(address, pubKey);
    }

    private void initializeDatasets(HashDigest headerRoot, HashDigest dataRoot, CryptoSetting cryptoSetting,
                                    Bytes keyPrefix, ExPolicyKVStorage exStorage, VersioningKVStorage verStorage, boolean readonly) {
//		// 加载“根数据集”
//		this.rootDataset = new MerkleHashDataset(rootHash, cryptoSetting, keyPrefix, exStorage, verStorage, readonly);

        // 初始化数据修改监听器；
        DataChangedListener<String, TypedValue> dataChangedListener = new DataChangedListener<String, TypedValue>() {
            @Override
            public void onChanged(String key, TypedValue value, long expectedVersion, long newVersion) {
                onUpdated(key, value, expectedVersion, newVersion);
            }
        };

        TypeMapper<byte[], TypedValue> valueMapper = new TypeMapper<byte[], TypedValue>() {

            @Override
            public byte[] encode(TypedValue t2) {
                return BinaryProtocol.encode(t2, BytesValue.class);
            }

            @Override
            public TypedValue decode(byte[] t1) {
                BytesValue v = BinaryProtocol.decode(t1, BytesValue.class);
                return TypedValue.wrap(v);
            }
        };

        // 加载“头数据集”；
//		HashDigest headerRoot = loadHeaderRoot();
        Bytes headerPrefix = keyPrefix.concat(HEADER_PREFIX);
        this.headerDataset = new MerkleHashDataset(headerRoot, cryptoSetting, headerPrefix, exStorage, verStorage,
                readonly);
        this.typedHeader = DatasetHelper.listen(DatasetHelper.map(headerDataset, valueMapper), dataChangedListener);

        // 加载“主数据集”
//		HashDigest dataRoot = loadDataRoot();
        Bytes dataPrefix = keyPrefix.concat(DATA_PREFIX);
        this.dataDataset = new MerkleHashDataset(dataRoot, cryptoSetting, dataPrefix, exStorage, verStorage, readonly);
        this.typedData = DatasetHelper.listen(DatasetHelper.map(dataDataset, valueMapper), dataChangedListener);
    }

    public Bytes getAddress() {
        return accountID.getAddress();
    }

    public PubKey getPubKey() {
        return accountID.getPubKey();
    }

    @Override
    public BlockchainIdentity getID() {
        return accountID;
    }

    public Dataset<String, TypedValue> getHeaders() {
        return typedHeader;
    }

    @Override
    public BaseDataset<String, TypedValue> getDataset() {
        return typedData;
    }

    @Override
    public HashDigest getHeaderRootHash() {
        return headerDataset.getRootHash();
    }

    @Override
    public HashDigest getDataRootHash() {
        return dataDataset.getRootHash();
    }

    @Override
    public MerkleProof getProof(Bytes key) {
        MerkleProof dataProof = dataDataset.getProof(key);
        if (dataProof == null) {
            return null;
        }
        // TODO Auto-generated method stub
        throw new IllegalStateException("Not implemented!");
    }

    /**
     * 是否只读；
     *
     * @return
     */
    public boolean isReadonly() {
        return dataDataset.isReadonly() || headerDataset.isReadonly();
    }

    /**
     * 初始化账户的公钥；
     *
     * @param pubKey
     */
    private void initPubKey(PubKey pubKey) {
        long v = typedHeader.setValue(KEY_PUBKEY, TypedValue.fromPubKey(pubKey));
        if (v < 0) {
            throw new LedgerException("PubKey storage conflict!");
        }
    }

    /**
     * 加载公钥；
     *
     * @return
     */
    private PubKey loadPubKey(Bytes address) {
        PubKey pubkey = null;
        if(null != pubkeyCache) {
            pubkey = pubkeyCache.getPubkey(address);
        }
        if (null == pubkey) {
            TypedValue value = typedHeader.getValue(KEY_PUBKEY);
            if (value != null) {
                pubkey = value.pubKeyValue();
                if(null != pubkeyCache) {
                    pubkeyCache.setPubkey(address, pubkey);
                }
            }
        }
        return pubkey;
    }

    /**
     * 当写入新值时触发此方法；
     *
     * @param key
     * @param value
     * @param newVersion
     */
    protected void onUpdated(String key, TypedValue value, long expectedVersion, long newVersion) {
    }

    /**
     * 当账户数据提交后触发此方法；<br>
     *
     * @param headerRoot
     * @param dataRoot
     */
    protected void onCommited(HashDigest headerRoot, HashDigest dataRoot) {
    }

    @Override
    public boolean isUpdated() {
        return headerDataset.isUpdated() || dataDataset.isUpdated();
//		return headerDataset.isUpdated() || dataDataset.isUpdated() || rootDataset.isUpdated();
    }

    @Override
    public void commit() {
        boolean updated = false;
        HashDigest headerRoot = headerDataset.getRootHash();
        if (headerDataset.isUpdated()) {
            headerDataset.commit();
            updated = true;
            headerRoot = headerDataset.getRootHash();
//			long version = getHeaderRootVersion();
//			rootDataset.setValue(KEY_HEADER_ROOT, headerDataset.getRootHash().toBytes(), version);
        }

        HashDigest dataRoot = dataDataset.getRootHash();
        if (dataDataset.isUpdated()) {
            dataDataset.commit();
            updated = true;
            dataRoot = dataDataset.getRootHash();
//			long version = getDataRootVersion();
//			rootDataset.setValue(KEY_DATA_ROOT, dataDataset.getRootHash().toBytes(), version);
        }

//		if (rootDataset.isUpdated()) {
//			HashDigest previousRootHash = rootDataset.getRootHash();
//			rootDataset.commit();
//			onCommited(previousRootHash, rootDataset.getRootHash());
//		}

        if (updated) {
            onCommited(headerRoot, dataRoot);
        }

    }

    @Override
    public void cancel() {
        headerDataset.cancel();
        dataDataset.cancel();
//		rootDataset.cancel();
    }

    // ----------------------

    private class AccountID implements BlockchainIdentity {

        private Bytes address;

        private PubKey pubKey;

        public AccountID(Bytes address, PubKey pubKey) {
            this.address = address;
            this.pubKey = pubKey;
        }

        @Override
        public Bytes getAddress() {
            return address;
        }

        @Override
        public PubKey getPubKey() {
            return pubKey;
        }

    }

//	private static class MerkleDatasetAdapter implements Dataset<String, BytesValue> {
//
//		private static DataChangedListener NULL_LISTENER = new DataChangedListener() {
//			@Override
//			public void onChanged(Bytes key, BytesValue value, long newVersion) {
//			}
//		};
//
//		private DataChangedListener changedListener;
//
//		private MerkleDataSet dataset;
//
//		public MerkleDataSet getDataset() {
//			return dataset;
//		}
//
//		@SuppressWarnings("unused")
//		public MerkleDatasetAdapter(MerkleDataSet dataset) {
//			this(dataset, NULL_LISTENER);
//		}
//
//		public MerkleDatasetAdapter(MerkleDataSet dataset, DataChangedListener listener) {
//			this.dataset = dataset;
//			this.changedListener = listener == null ? NULL_LISTENER : listener;
//		}
//
//		@Override
//		public DataEntry<String, BytesValue> getDataEntry(String key) {
//			return new VersioningKVEntryWraper(dataset.getDataEntry(Bytes.fromString(key)));
//		}
//
//		@Override
//		public DataEntry<String, BytesValue> getDataEntry(String key, long version) {
//			return new VersioningKVEntryWraper(dataset.getDataEntry(Bytes.fromString(key), version));
//		}
//
//		/**
//		 * Create or update the value associated the specified key if the version
//		 * checking is passed.<br>
//		 *
//		 * The value of the key will be updated only if it's latest version equals the
//		 * specified version argument. <br>
//		 * If the key doesn't exist, the version checking will be ignored, and key will
//		 * be created with a new sequence number as id. <br>
//		 * It also could specify the version argument to -1 to ignore the version
//		 * checking.
//		 * <p>
//		 * If updating is performed, the version of the key increase by 1. <br>
//		 * If creating is performed, the version of the key initialize by 0. <br>
//		 *
//		 * @param key     The key of data;
//		 * @param value   The value of data;
//		 * @param version The expected version of the key.
//		 * @return The new version of the key. <br>
//		 *         If the key is new created success, then return 0; <br>
//		 *         If the key is updated success, then return the new version;<br>
//		 *         If this operation fail by version checking or other reason, then
//		 *         return -1;
//		 */
//		@Override
//		public long setValue(Bytes key, BytesValue value, long version) {
//			byte[] bytesValue = BinaryProtocol.encode(value, BytesValue.class);
//			long v = dataset.setValue(key, bytesValue, version);
//			if (v > -1) {
//				changedListener.onChanged(key, value, v);
//			}
//			return v;
//		}
//
//		/**
//		 * Return the latest version entry associated the specified key; If the key
//		 * doesn't exist, then return -1;
//		 *
//		 * @param key
//		 * @return
//		 */
//		@Override
//		public long getVersion(Bytes key) {
//			return dataset.getVersion(key);
//		}
//
//		/**
//		 * return the latest version's value;
//		 *
//		 * @param key
//		 * @return return null if not exist;
//		 */
//		@Override
//		public BytesValue getValue(Bytes key) {
//			byte[] bytesValue = dataset.getValue(key);
//			if (bytesValue == null) {
//				return null;
//			}
//			return BinaryProtocol.decodeAs(bytesValue, BytesValue.class);
//		}
//
//		/**
//		 * Return the specified version's value;
//		 *
//		 * @param key
//		 * @param version
//		 * @return return null if not exist;
//		 */
//		@Override
//		public BytesValue getValue(Bytes key, long version) {
//			byte[] bytesValue = dataset.getValue(key, version);
//			if (bytesValue == null) {
//				return null;
//			}
//			return BinaryProtocol.decodeAs(bytesValue, BytesValue.class);
//		}
//
//		@Override
//		public long getDataCount() {
//			return dataset.getDataCount();
//		}
//
//		@Override
//		public long setValue(String key, BytesValue value, long version) {
//			byte[] bytesValue = BinaryProtocol.encode(value, BytesValue.class);
//			return dataset.setValue(key, bytesValue, version);
//		}
//
//		@Override
//		public BytesValue getValue(String key, long version) {
//			byte[] bytesValue = dataset.getValue(key, version);
//			if (bytesValue == null) {
//				return null;
//			}
//			return BinaryProtocol.decodeAs(bytesValue, BytesValue.class);
//		}
//
//		@Override
//		public BytesValue getValue(String key) {
//			byte[] bytesValue = dataset.getValue(key);
//			if (bytesValue == null) {
//				return null;
//			}
//			return BinaryProtocol.decodeAs(bytesValue, BytesValue.class);
//		}
//
//		@Override
//		public long getVersion(String key) {
//			return dataset.getVersion(key);
//		}
//
//		@Override
//		public DataEntry<String, BytesValue> getDataEntry(String key) {
//			return new VersioningKVEntryWraper<String>(dataset.getDataEntry(key));
//		}
//
//		@Override
//		public DataEntry<String, BytesValue> getDataEntry(String key, long version) {
//			return new VersioningKVEntryWraper<String>(dataset.getDataEntry(key, version));
//		}
//	}

//	private static interface DataChangedListener {
//
//		void onChanged(Bytes key, BytesValue value, long newVersion);
//
//	}

//	private static class VersioningKVEntryWraper implements DataEntry<String, BytesValue> {
//
//		private DataEntry<Bytes, byte[]> kv;
//
//		public VersioningKVEntryWraper(DataEntry<Bytes, byte[]> kv) {
//			this.kv = kv;
//		}
//
//		@Override
//		public String getKey() {
//			return kv.getKey().toUTF8String();
//		}
//
//		@Override
//		public long getVersion() {
//			return kv.getVersion();
//		}
//
//		@Override
//		public BytesValue getValue() {
//			return BinaryProtocol.decodeAs(kv.getValue(), BytesValue.class);
//		}
//
//	}

}
