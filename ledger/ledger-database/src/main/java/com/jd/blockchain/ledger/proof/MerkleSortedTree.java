package com.jd.blockchain.ledger.proof;

import java.awt.IllegalComponentStateException;

import com.jd.blockchain.binaryproto.BinaryProtocol;
import com.jd.blockchain.binaryproto.DataContract;
import com.jd.blockchain.binaryproto.DataField;
import com.jd.blockchain.binaryproto.NumberEncoding;
import com.jd.blockchain.binaryproto.PrimitiveType;
import com.jd.blockchain.consts.DataCodes;
import com.jd.blockchain.crypto.Crypto;
import com.jd.blockchain.crypto.HashDigest;
import com.jd.blockchain.crypto.HashFunction;
import com.jd.blockchain.ledger.CryptoSetting;
import com.jd.blockchain.ledger.MerkleProof;
import com.jd.blockchain.ledger.core.MerkleProofException;
import com.jd.blockchain.storage.service.ExPolicy;
import com.jd.blockchain.storage.service.ExPolicyKVStorage;
import com.jd.blockchain.utils.Bytes;
import com.jd.blockchain.utils.Transactional;
import com.jd.blockchain.utils.codec.Base58Utils;
import com.jd.blockchain.utils.io.BytesUtils;

/**
 * 默克尔树；
 * <p>
 * 树的level是按照倒置的方式计算，而不是以根节点的距离衡量，即叶子节点的 level 是 0； <br>
 * 所有的数据的哈希索引都以叶子节点进行记录; <br>
 * 每一个数据节点都以标记一个序列号（Sequence Number, 缩写为 SN），并按照序列号的大小统一地在 level 0
 * 上排列，并填充从根节点到数据节点的所有路径节点； <br>
 * 随着数据节点的增加，整棵树以倒置方式向上增长（根节点在上，叶子节点在下），此设计带来显著特性是已有节点的信息都可以不必修改；
 * 
 * <p>
 * <strong>注：此实现不是线程安全的；</strong><br>
 * 但由于对单个账本中的写入过程被设计为同步写入，因而非线程安全的设计并不会影响在此场景下的使用，而且由于省去了线程间同步操作，反而提升了性能；
 * 
 * @author huanghaiquan
 *
 *
 * @param <T>
 */
public class MerkleSortedTree implements Transactional {

	public static final int TREE_DEGREE = 4;

	public static final int MAX_LEVEL = 30;

	// 正好是 2 的 60 次方，足以覆盖 long 类型的正整数，且为避免溢出预留了区间；
	public static final long MAX_COUNT = power(TREE_DEGREE, MAX_LEVEL);

	private final Bytes keyPrefix;

	private CryptoSetting setting;

	private HashFunction hashFunc;

	private ExPolicyKVStorage kvStorage;

	private HashDigest rootHash;

	private MerklePath root;

	/**
	 * 构建空的树；
	 * 
	 * @param kvStorage
	 */
	public MerkleSortedTree(CryptoSetting setting, String keyPrefix, ExPolicyKVStorage kvStorage) {
		this(null, setting, Bytes.fromString(keyPrefix), kvStorage);
	}

	/**
	 * 构建空的树；
	 * 
	 * @param kvStorage
	 */
	public MerkleSortedTree(CryptoSetting setting, Bytes keyPrefix, ExPolicyKVStorage kvStorage) {
		this(null, setting, keyPrefix, kvStorage);
	}

	/**
	 * 创建 Merkle 树；
	 * 
	 * @param rootHash     节点的根Hash; 如果指定为 null，则实际上创建一个空的 Merkle Tree；
	 * @param verifyOnLoad 从外部存储加载节点时是否校验节点的哈希；
	 * @param kvStorage    保存 Merkle 节点的存储服务；
	 * @param readonly     是否只读；
	 */
	public MerkleSortedTree(HashDigest rootHash, CryptoSetting setting, String keyPrefix, ExPolicyKVStorage kvStorage) {
		this(rootHash, setting, Bytes.fromString(keyPrefix), kvStorage);
	}

	/**
	 * 创建 Merkle 树；
	 * 
	 * @param rootHash     节点的根Hash; 如果指定为 null，则实际上创建一个空的 Merkle Tree；
	 * @param verifyOnLoad 从外部存储加载节点时是否校验节点的哈希；
	 * @param kvStorage    保存 Merkle 节点的存储服务；
	 * @param readonly     是否只读；
	 */
	public MerkleSortedTree(HashDigest rootHash, CryptoSetting setting, Bytes keyPrefix, ExPolicyKVStorage kvStorage) {
		this.setting = setting;
		this.keyPrefix = keyPrefix;
		this.kvStorage = kvStorage;
		this.hashFunc = Crypto.getHashFunction(setting.getHashAlgorithm());

		if (rootHash != null) {
			loadNodeBytes(rootHash);
		}
	}

	/**
	 * 计算 value 的 x 次方；
	 * <p>
	 * 注：此方法不处理溢出；调用者需要自行规避；
	 * 
	 * @param value
	 * @param x     大于等于 0 的整数；
	 * @return
	 */
	private static long power(long value, int x) {
		if (x == 0) {
			return 1;
		}
		long r = value;
		for (int i = 1; i < x; i++) {
			r *= value;
		}
		return r;
	}

	private static long getLeafOffset(long id) {
		return id - id % TREE_DEGREE;
	}

	public boolean set(long id, byte[] data, ExPolicy ex) {
		if (id < 0) {
			throw new IllegalArgumentException("The argument 'id' is negative!");
		}
		if (root == null) {
			long offset = getLeafOffset(id);
			MerklePath leaf = createLeafNode(offset);
//			int index = (int) (id - offset);
			leaf.setChild(index, data);
			root = leaf;

			return true;
		}

		if (id < root.getOffset()) {
			// 要插入的节点在根节点的左侧的子树；

		} else if (id >= root.getOffset() + root.getStep() * TREE_DEGREE) {
			// 要插入的节点在根节点的右侧的子树；

		} else {
			// 要插入的节点在根节点当前的子树；

		}
	}

	public HashDigest getRootHash() {
		// TODO Auto-generated method stub
		return null;
	}

	public MerkleProof getProof(long id) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isUpdated() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void commit() {
		// TODO Auto-generated method stub

	}

	@Override
	public void cancel() {
		// TODO Auto-generated method stub

	}

	private MerkleIndex loadPathNode(HashDigest nodeHash) {
		byte[] nodeBytes = loadNodeBytes(nodeHash);
		MerkleIndex idx = BinaryProtocol.decode(nodeBytes, MerkleIndex.class);
		if (setting.getAutoVerifyHash()) {
			HashDigest hash = hashFunc.hash(nodeBytes);
			if (!hash.equals(nodeHash)) {
				throw new MerkleProofException("Merkle hash verification fail! -- NodeHash=" + nodeHash.toBase58());
			}
		}
		return idx;
	}

	private MerkleData loadData(long id, HashDigest nodeHash) {
		byte[] nodeBytes = loadNodeBytes(BytesUtils.toBytes(id));
		MerkleData merkleData = BinaryProtocol.decode(nodeBytes, MerkleData.class);
		if (setting.getAutoVerifyHash()) {
			HashDigest hash = hashFunc.hash(nodeBytes);
			if (!hash.equals(nodeHash)) {
				throw new MerkleProofException(
						String.format("Merkle hash verification fail! --ID=%s; NodeHash=%s", id, nodeHash.toBase58()));
			}
		}
		return merkleData;
	}

	/**
	 * 生成存储节点数据的key；
	 * 
	 * @param key 节点逻辑key；
	 * @return 节点的存储key；
	 */
	private Bytes encodeStorageKey(byte[] key) {
		return keyPrefix.concat(key);
	}

	/**
	 * 生成存储节点数据的key；
	 * 
	 * @param key 节点逻辑key；
	 * @return 节点的存储key；
	 */
	private Bytes encodeStorageKey(Bytes key) {
		return keyPrefix.concat(key);
	}

	/**
	 * 加载指定节点的内容，如果不存在，则抛出异常；
	 * 
	 * @param nodeHash
	 * @return
	 */
	private byte[] loadNodeBytes(byte[] key) {
		Bytes storageKey = encodeStorageKey(key);
		byte[] nodeBytes = kvStorage.get(storageKey);
		if (nodeBytes == null) {
			throw new MerkleProofException("Merkle node does not exist! -- key=" + storageKey.toBase58());
		}
		return nodeBytes;
	}

	private byte[] loadNodeBytes(Bytes key) {
		Bytes storageKey = encodeStorageKey(key);
		byte[] nodeBytes = kvStorage.get(storageKey);
		if (nodeBytes == null) {
			throw new MerkleProofException("Merkle node does not exist! -- key=" + storageKey.toBase58());
		}
		return nodeBytes;
	}

	private void saveNodeBytes(byte[] key, byte[] nodeBytes) {
		Bytes storageKey = encodeStorageKey(key);
		boolean success = kvStorage.set(storageKey, nodeBytes, ExPolicy.NOT_EXISTING);
		if (!success) {
			throw new MerkleProofException("Merkle node already exist! -- key=" + storageKey.toBase58());
		}
	}

	private void saveNodeBytes(Bytes key, byte[] nodeBytes) {
		Bytes storageKey = encodeStorageKey(key);
		boolean success = kvStorage.set(storageKey, nodeBytes, ExPolicy.NOT_EXISTING);
		if (!success) {
			throw new MerkleProofException("Merkle node already exist! -- key=" + storageKey.toBase58());
		}
	}

	/**
	 * 默克尔节点；
	 * 
	 * @author huanghaiquan
	 *
	 */
	public static interface MerkleEntry {

	}

	/**
	 * 表示 {@link MerkleSortedTree} 维护的数据项；
	 * 
	 * @author huanghaiquan
	 *
	 */
	@DataContract(code = DataCodes.MERKLE_SORTED_TREE_DATA)
	public static interface MerkleData extends MerkleEntry {
		
		@DataField(order = 0, primitiveType = PrimitiveType.INT64, numberEncoding = NumberEncoding.LONG)
		long getId();

		/**
		 * 数据({@link #getBytes()})的哈希；
		 * 
		 * @return
		 */
		@DataField(order = 1, primitiveType = PrimitiveType.BYTES)
		HashDigest getHash();

		/**
		 * 数据字节；
		 * 
		 * @return
		 */
		@DataField(order = 2, primitiveType = PrimitiveType.BYTES)
		byte[] getBytes();

	}

	/**
	 * 默克尔数据索引；
	 * 
	 * @author huanghaiquan
	 *
	 */
	@DataContract(code = DataCodes.MERKLE_SORTED_TREE_INDEX)
	public static interface MerkleIndex extends MerkleEntry {

		/**
		 * 所有子项的起始ID； <br>
		 * 
		 * 即 {@link #getChildHashs()} 中第 0 个子项的 ID ；
		 * 
		 * @return
		 */
		@DataField(order = 0, primitiveType = PrimitiveType.INT64, numberEncoding = NumberEncoding.LONG)
		long getOffset();

		/**
		 * 子项的 ID 的递增步长；<br>
		 * 
		 * 即 {@link #getChildHashs()} 中任意子项的 ID 加上 {@link #getStep()} 为下一个子项的 ID；
		 * 
		 * @return
		 */
		@DataField(order = 1, primitiveType = PrimitiveType.INT64, numberEncoding = NumberEncoding.LONG)
		long getStep();

		/**
		 * 子项的哈希的列表； <br>
		 * 
		 * 子项的个数总是固定的 {@value MerkleSortedTree#TREE_DEGREE} ;
		 * 
		 * @return
		 */
		@DataField(order = 2, primitiveType = PrimitiveType.BYTES, list = true)
		HashDigest[] getChildHashs();
	}

	/**
	 * 默克尔数据节点；
	 * 
	 * @author huanghaiquan
	 *
	 */
	private static class MerkleDataNode implements MerkleData {

		private HashDigest hash;

		private byte[] bytes;

		/**
		 * 创建默克尔数据节点；
		 * 
		 * @param hash  数据参数的哈希值；
		 * @param bytes 数据；
		 */
		public MerkleDataNode(HashDigest hash, byte[] bytes) {
			this.hash = hash;
			this.bytes = bytes;
		}

		@Override
		public HashDigest getHash() {
			return hash;
		}

		@Override
		public byte[] getBytes() {
			return bytes;
		}

	}
	
	
	

	private MerklePath createLeafNode(long offset) {
		return new MerklePath(null, offset, 1L, new HashDigest[TREE_DEGREE]);
	}

	/**
	 * 默克尔路径的抽象实现；
	 * 
	 * @author huanghaiquan
	 *
	 */
	private class MerklePath implements MerkleIndex {

		/**
		 * 与当前子树相邻的右侧兄弟子树的偏移量；
		 */
		private final long NEXT_OFFSET;

		private HashDigest nodeHash;

		private long offset;

		private long step;

		private HashDigest[] origChildHashs;

		private HashDigest[] childHashs;

		private MerkleEntry[] children;


		protected MerklePath(HashDigest nodeHash, long offset, long step, HashDigest[] childHashs) {
			assert step > 0;
			NEXT_OFFSET = offset + step * TREE_DEGREE;

			this.nodeHash = nodeHash;

			this.offset = offset;
			this.step = step;
			this.childHashs = childHashs;
			this.origChildHashs = childHashs.clone();

			assert childHashs.length == TREE_DEGREE;
		}



		public boolean isModified() {
			for (int i = 0; i < TREE_DEGREE; i++) {
				if (childHashs[i] == null && children[i] != null) {
					return true;
				}
			}
			return false;
		}

		@Override
		public long getOffset() {
			return offset;
		}

		@Override
		public long getStep() {
			return step;
		}

		@Override
		public HashDigest[] getChildHashs() {
			return childHashs;
		}

		/**
		 * 返回指定 ID 在当前节点表示的子树的偏移位置；
		 * 
		 * <br>
		 * 
		 * 如果不属于当前节点，则返回 -1；
		 * 
		 * @param id
		 * @return
		 */
		public int index(long id) {
			if (id < offset || id >= NEXT_OFFSET) {
				return -1;
			}
			long m = (id - offset) % step;
			return (int) ((id - offset - m) / step);
		}

		/**
		 * 返回子节点；
		 * 
		 * @param id 子节点的id, 如果子节点不属于当前节点的存储空间。则抛出异常；
		 */
		public MerkleEntry getChild(long id) {
			int index = index(id);
			assert index > -1;
			MerkleEntry child = children[index];
			if (child != null) {
				return child;
			}
			HashDigest childHash = childHashs[index];
			if (childHash == null) {
				return null;
			}

			if (step == 1) {
				// 叶子节点；
				child = loadData(id, childHash);
			} else {
				// step > 1， 非叶子节点； 注：构造器对输入参数的处理保证 step > 0;
				child = loadPathNode(childHash);
			}
			children[index] = child;
			return child;
		}
		
		
		public void setData(long id, byte[] data) {
			int index = index(id);
			assert index > -1;
			
			HashDigest childHash = childHashs[index];
			MerkleEntry child =  children[index];
			if (childHash == null) {
				if (child == null) {
					long offset = getLeafOffset(id);
					child = createLeafNode(offset);
					
					children[index] = child;
				}else {
					if (child instanceof MerkleData) {
						MerkleData childData = (MerkleData) child;
						if (childData.get) {
							
						}
					}
				}
			}
		}

		/**
		 * @param child
		 */
		protected void setChild(long id, HashDigest childHash, MerkleEntry child) {
			int index = index(id);
			assert index > -1;
			childHashs[index] = childHash;
			children[index] = child;
		}

		
		public HashDigest commit() {
			if (!isModified()) {
				return nodeHash;
			}
			// save the modified childNodes;
			for (int i = 0; i < TREE_DEGREE; i++) {
				if (childHashs[i] == null && children[i] != null) {
					MerkleEntry child = children[i];
					// 需要先保存子节点，获得子节点的哈希；
					if (step == 1) {
						// 当前已经是叶子节点，子项是数据项；
						long id = offset + i * step;
						childHashs[i] = saveData(id, (MerkleData) child);
					} else {
						// step > 1， 非叶子节点； 注：构造器对输入参数的处理保证 step > 0;
						if (child instanceof MerklePath) {
							childHashs[i] = ((MerklePath) child).commit();
						} else {
							// 注：上下文逻辑应确保不可能进入此分支，即一个新加入的尚未生成哈希的子节点，却不是 MerklePathNode 实例；
							// 对于附加已存在的节点的情况，已存在的节点已经生成子节点哈希，并且其实例是 MerkleIndex 的动态代理；
							throw new IllegalStateException(
									"Illegal child node which has no hash and is not instance of MerklePathNode!");
						}
					}
				}
			}

			// save;
			byte[] nodeBytes = BinaryProtocol.encode(this, MerkleIndex.class);
			HashDigest hash = hashFunc.hash(nodeBytes);
			Bytes storageKey = encodeStorageKey(hash);
			saveNodeBytes(storageKey, nodeBytes);

			this.nodeHash = hash;

			return hash;
		}

		HashDigest saveData(long id, MerkleData data) {
			byte[] dataNodeBytes = BinaryProtocol.encode(data, MerkleData.class);
			
			//以 id 建议存储key ，便于根据 id 直接快速查询检索，无需展开默克尔树；
			Bytes storageKey = encodeStorageKey(BytesUtils.toBytes(id));
			saveNodeBytes(storageKey, dataNodeBytes);
			
			HashDigest dataEntryHash = hashFunc.hash(dataNodeBytes);
			return dataEntryHash;
		}

	}

}
