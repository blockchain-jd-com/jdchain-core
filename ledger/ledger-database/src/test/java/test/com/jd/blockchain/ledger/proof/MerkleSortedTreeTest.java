package test.com.jd.blockchain.ledger.proof;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;

import org.junit.Test;
import org.mockito.Mockito;

import com.jd.blockchain.crypto.Crypto;
import com.jd.blockchain.crypto.CryptoAlgorithm;
import com.jd.blockchain.crypto.HashDigest;
import com.jd.blockchain.crypto.HashFunction;
import com.jd.blockchain.crypto.service.classic.ClassicAlgorithm;
import com.jd.blockchain.ledger.CryptoSetting;
import com.jd.blockchain.ledger.proof.MerkleSortTree;
import com.jd.blockchain.ledger.proof.MerkleSortTree.ValueEntry;
import com.jd.blockchain.ledger.proof.MerkleTreeKeyExistException;
import com.jd.blockchain.storage.service.utils.MemoryKVStorage;
import com.jd.blockchain.utils.ArrayUtils;
import com.jd.blockchain.utils.SkippingIterator;

public class MerkleSortedTreeTest {

	private static final String DEFAULT_MKL_KEY_PREFIX = "";

	private static final CryptoAlgorithm HASH_ALGORITHM = ClassicAlgorithm.SHA256;

	private static final HashFunction HASH_FUNCTION = Crypto.getHashFunction(HASH_ALGORITHM);

	/**
	 * 测试顺序加入数据，是否能够得到
	 */
	@Test
	public void testSequenceAdd() {
		int count = 1;
		byte[][] datas = generateRandomData(count);
		testWithSequenceIDs(datas, count);

		count = MerkleSortTree.DEFAULT_DEGREE;
		datas = generateRandomData(count);
		testWithSequenceIDs(datas, count);

		count = (int) power(MerkleSortTree.DEFAULT_DEGREE, 2);
		datas = generateRandomData(count);
		testWithSequenceIDs(datas, count);

		count = (int) power(MerkleSortTree.DEFAULT_DEGREE, 3);
		datas = generateRandomData(count);
		testWithSequenceIDs(datas, count);

		count = count + 1;
		datas = generateRandomData(count);
		testWithSequenceIDs(datas, count);

		count = count - 2;
		datas = generateRandomData(count);
		testWithSequenceIDs(datas, count);

		count = 10010;
		datas = generateRandomData(count);
		testWithSequenceIDs(datas, count);
	}

	/**
	 * 测试顺序加入数据，是否能够得到
	 */
	@Test
	public void testRandomAdd() {
		int count = 1;
		byte[][] datas = generateRandomData(count);
		testWithRandomIDs(datas, count);

		count = MerkleSortTree.DEFAULT_DEGREE;
		datas = generateRandomData(count);
		testWithRandomIDs(datas, count);

		count = (int) power(MerkleSortTree.DEFAULT_DEGREE, 2);
		datas = generateRandomData(count);
		testWithRandomIDs(datas, count);

		count = (int) power(MerkleSortTree.DEFAULT_DEGREE, 3);
		datas = generateRandomData(count);
		testWithRandomIDs(datas, count);

		count = count + 1;
		datas = generateRandomData(count);
		testWithRandomIDs(datas, count);

		count = count - 2;
		datas = generateRandomData(count);
		testWithRandomIDs(datas, count);

		count = 10010;
		datas = generateRandomData(count);
		testWithRandomIDs(datas, count);
	}

	@Test
	public void testIterator() {
		CryptoSetting cryptoSetting = createCryptoSetting();
		MemoryKVStorage storage = new MemoryKVStorage();
		MerkleSortTree mst = new MerkleSortTree(cryptoSetting, DEFAULT_MKL_KEY_PREFIX, storage);

		// 验证空的迭代器；
		SkippingIterator<ValueEntry> iter = mst.iterator();

		assertEquals(0, iter.getTotalCount());
		assertEquals(-1, iter.getCursor());
		assertFalse(iter.hasNext());
		assertNull(iter.next());

		// 加入数据，验证顺序数据插入的生成的迭代器；
		int count1 = 10;
		byte[][] datas1 = generateRandomData(count1);
		long[] ids1 = generateSeqenceIDs(0, count1);
		addDatas(ids1, datas1, mst);

		iter = mst.iterator();
		assertEquals(count1, iter.getTotalCount());
		assertEquals(-1, iter.getCursor());
		assertTrue(iter.hasNext());
		int i = 0;
		while (iter.hasNext()) {
			ValueEntry merkleData = iter.next();
			assertNotNull(merkleData);
			assertEquals(ids1[i], merkleData.getId());
			i++;
		}
		assertEquals(count1, i);

		// 随机加入；验证迭代器返回有序的序列；
		HashSet<Long> excludingIDs = new HashSet<Long>();
		for (long l : ids1) {
			excludingIDs.add(l);
		}
		int count2 = (int) power(4, 8) + 1;
		byte[][] datas2 = generateRandomData(count2);
		long[] ids2 = generateRandomIDs(count2, excludingIDs, true);
		addDatas(ids2, datas2, mst);

		long[] totalIds = ArrayUtils.concat(ids1, ids2);
		Arrays.sort(totalIds);

		long totalCount = count1 + count2;
		iter = mst.iterator();
		assertEquals(totalCount, iter.getTotalCount());
		assertEquals(-1, iter.getCursor());
		assertTrue(iter.hasNext());
		i = 0;
		while (iter.hasNext()) {
			ValueEntry merkleData = iter.next();
			assertNotNull(merkleData);
			assertEquals(totalIds[i], merkleData.getId());
			i++;
		}
		assertEquals(totalCount, i);

		// 验证有跳跃的情形；
		iter = mst.iterator();
		assertEquals(-1, iter.getCursor());

		int index = -1;
		long skipped = 1;

		iter.skip(skipped);
		index += skipped;
		assertEquals(index, iter.getCursor());

		ValueEntry merkleData = iter.next();
		index++;
		assertEquals(index, iter.getCursor());
		assertNotNull(merkleData);
		assertEquals(totalIds[index], merkleData.getId());

		skipped = 2;
		iter.skip(skipped);
		index += skipped;
		assertEquals(index, iter.getCursor());

		merkleData = iter.next();
		index++;
		assertEquals(index, iter.getCursor());
		assertNotNull(merkleData);
		assertEquals(totalIds[index], merkleData.getId());

		skipped = 3;
		iter.skip(skipped);
		index += skipped;
		assertEquals(index, iter.getCursor());

		merkleData = iter.next();
		index++;
		assertEquals(index, iter.getCursor());
		assertNotNull(merkleData);
		assertEquals(totalIds[index], merkleData.getId());

		SecureRandom random = new SecureRandom();
		for (int j = 0; j < 100; j++) {
			skipped = random.nextInt(100);
			iter.skip(skipped);
			index += skipped;
			assertEquals(index, iter.getCursor());

			merkleData = iter.next();
			index++;
			assertEquals(index, iter.getCursor());
			assertNotNull(merkleData);
			assertEquals(totalIds[index], merkleData.getId());
		}
		
		//验证直接跳跃到倒数第 1 条的情形；
		long left = iter.getCount();
		iter.skip(left - 1);

		assertTrue(iter.hasNext());
		assertEquals(1, iter.getCount());

		merkleData = iter.next();
		assertEquals(totalCount-1, iter.getCursor());
		assertNotNull(merkleData);
		assertEquals(totalIds[(int)totalCount-1], merkleData.getId());
		
		assertFalse(iter.hasNext());
		merkleData = iter.next();
		assertNull(merkleData);
		
		//验证直接跳跃到末尾的情形；
		iter = mst.iterator();
		assertTrue(iter.hasNext());
		
		long c = iter.skip(totalCount);
		assertEquals(totalCount, c);
		assertFalse(iter.hasNext());
		merkleData = iter.next();
		assertNull(merkleData);
	}
	
	@Test
	public void testCounts() {
		CryptoSetting cryptoSetting = createCryptoSetting();
		MemoryKVStorage storage = new MemoryKVStorage();
		MerkleSortTree mst = new MerkleSortTree(cryptoSetting, DEFAULT_MKL_KEY_PREFIX, storage);

		HashSet<Long> excludingIDs = new HashSet<Long>();

		int count1 = (int) power(MerkleSortTree.DEFAULT_DEGREE, 2);
		byte[][] datas1 = generateRandomData(count1);
		long[] ids1 = generateRandomIDs(count1, excludingIDs, true);
		addDatas(ids1, datas1, mst);

		int count2 = (int) power(MerkleSortTree.DEFAULT_DEGREE, 3);
		byte[][] datas2 = generateRandomData(count2);
		long[] ids2 = generateRandomIDs(count2, excludingIDs, true);
		addDatas(ids2, datas2, mst);

		// 合并前两次产生的数据，验证默克尔树中是否已经写入相同的数据；
		long[] ids = ArrayUtils.concat(ids1, ids2);
		byte[][] datas = ArrayUtils.concat(datas1, datas2, byte[].class);
		assertDataEquals(ids, datas, mst);

		// 从存储中重新加载默克尔树，验证默克尔树中是否已经写入相同的数据；
		HashDigest rootHash = mst.getRootHash();
		mst = new MerkleSortTree(rootHash, cryptoSetting, DEFAULT_MKL_KEY_PREFIX, storage);
		assertDataEquals(ids, datas, mst);

		// 对重新加载的默克尔树持续写入，验证重复加载后持续写入的正确性；
		int count3 = 1023;
		byte[][] datas3 = generateRandomData(count3);
		long[] ids3 = generateRandomIDs(count3, excludingIDs, true);
		addDatas(ids3, datas3, mst);

		ids = ArrayUtils.concat(ids, ids3);
		datas = ArrayUtils.concat(datas, datas3, byte[].class);
		assertDataEquals(ids, datas, mst);
	}

	/**
	 * 测试插入顺序的不变性；即，相同的数据集合，无论插入顺序如何，最终得到的结果都是相同的；
	 */
	@Test
	public void testImmutability() {
		MerkleSortTree mst1 = newMerkleSortedTree();

		// 创建基准数据；
		int count = 10253;
		byte[][] datas1 = generateRandomData(count);
		long[] ids1 = generateRandomIDs(count);
		addDatas(ids1, datas1, mst1);

		// 反转数据的顺序之后写入，校验是否一致；
		byte[][] datas2 = datas1.clone();
		long[] ids2 = ids1.clone();
		ArrayUtils.reverse(datas2);
		ArrayUtils.reverse(ids2);
		MerkleSortTree mst2 = newMerkleSortedTree();
		addDatas(ids2, datas2, mst2);
		assertEquals(mst1.getRootHash(), mst2.getRootHash());

		// 随机打乱顺序之后写入，校验是否一致；
		byte[][] datas3 = datas1.clone();
		long[] ids3 = ids1.clone();
		resortRandomly(ids3, datas3);
		MerkleSortTree mst3 = newMerkleSortedTree();
		addDatas(ids3, datas3, mst3);
		assertEquals(mst1.getRootHash(), mst3.getRootHash());

		// 先随机打乱顺序，然后分多次写入，验证最终是否得到一致的结果；
		byte[][] datas4 = datas1.clone();
		long[] ids4 = ids1.clone();
		resortRandomly(ids4, datas4);

		MerkleSortTree mst4 = newMerkleSortedTree();
		assertNull(mst4.getRootHash());

		int count4_1 = 1024;
		byte[][] datas4_1 = Arrays.copyOfRange(datas4, 0, count4_1);
		long[] ids4_1 = Arrays.copyOfRange(ids4, 0, count4_1);
		addDatas(ids4_1, datas4_1, mst4);
		HashDigest rootHash4_1 = mst4.getRootHash();
		assertNotNull(rootHash4_1);
		assertEquals(count4_1, mst4.getCount());

		int count4_2 = 1203;
		byte[][] datas4_2 = Arrays.copyOfRange(datas4, count4_1, count4_1 + count4_2);
		long[] ids4_2 = Arrays.copyOfRange(ids4, count4_1, count4_1 + count4_2);
		addDatas(ids4_2, datas4_2, mst4);
		HashDigest rootHash4_2 = mst4.getRootHash();
		assertNotNull(rootHash4_2);
		assertNotEquals(rootHash4_1, rootHash4_2);
		assertEquals(count4_1 + count4_2, mst4.getCount());

		byte[][] datas4_3 = Arrays.copyOfRange(datas4, count4_1 + count4_2, count);
		long[] ids4_3 = Arrays.copyOfRange(ids4, count4_1 + count4_2, count);
		addDatas(ids4_3, datas4_3, mst4);
		HashDigest rootHash4_3 = mst4.getRootHash();
		assertNotNull(rootHash4_3);
		assertNotEquals(rootHash4_2, rootHash4_3);
		assertEquals(count, mst4.getCount());

		assertEquals(mst1.getRootHash(), rootHash4_3);
	}

	/**
	 * 测试插入同一个 ID 的冲突表现是否符合预期；
	 */
	@Test
	public void testIdConfliction() {
		CryptoSetting cryptoSetting = createCryptoSetting();
		MemoryKVStorage storage = new MemoryKVStorage();
		MerkleSortTree mst = new MerkleSortTree(cryptoSetting, DEFAULT_MKL_KEY_PREFIX, storage);

		// 验证空的迭代器；
		SkippingIterator<ValueEntry> iter = mst.iterator();

		assertEquals(0, iter.getTotalCount());
		assertEquals(-1, iter.getCursor());
		assertFalse(iter.hasNext());
		assertNull(iter.next());

		// 加入数据，验证顺序数据插入的生成的迭代器；
		int count = 10;
		byte[][] datas = generateRandomData(count);
		long[] ids = generateSeqenceIDs(0, count);
		
		addDatas(ids, datas, mst);;
		
		//预期默认的 MerkleSortedTree 实现下，写入相同 id 的数据会引发移除；
		MerkleTreeKeyExistException keyExistException = null;
		try {
			mst.set(8, datas[0]);
		} catch (MerkleTreeKeyExistException e) {
			keyExistException = e;
		}
		assertNotNull(keyExistException);
	}
	
	/**
	 * 随机地对 id 和数据两个数组重排序；
	 * 
	 * @param ids
	 * @param datas
	 */
	private static void resortRandomly(long[] ids, byte[][] datas) {
		SecureRandom random = new SecureRandom();
		int count = ids.length;

		for (int i = 0; i < count; i++) {
			int j = random.nextInt(count);
			int k = random.nextInt(count);
			long temp = ids[j];
			ids[j] = ids[k];
			ids[k] = temp;

			byte[] data = datas[j];
			datas[j] = datas[k];
			datas[k] = data;
		}
	}

	private static void testWithRandomIDs(byte[][] datas, int count) {
		long[] ids = generateRandomIDs(count);

		testAddingAndAssertingEquals(ids, datas);
	}

	private static long[] generateRandomIDs(int count) {
		HashSet<Long> excludingIDs = new HashSet<Long>();
		return generateRandomIDs(count, excludingIDs, true);
	}

	private static long[] generateRandomIDs(int count, HashSet<Long> excludingIDs, boolean noRepeatly) {
		long[] ids = new long[count];
		SecureRandom random = new SecureRandom();
		long id = -1;
		for (int i = 0; i < count; i++) {
			while (id < 0 || id >= MerkleSortTree.DEFAULT_MAX_COUNT || excludingIDs.contains(Long.valueOf(id))) {
				id = random.nextLong();
			}
			if (noRepeatly) {
				excludingIDs.add(Long.valueOf(id));
			}
			ids[i] = id;
		}
		return ids;
	}

	private static long[] generateSeqenceIDs(long from, int count) {
		long[] ids = new long[count];
		for (int i = 0; i < count; i++) {
			ids[i] = from + i;
		}
		return ids;
	}

	private static void testWithSequenceIDs(byte[][] datas, int count) {
		long[] ids = generateSeqenceIDs(0, count);
		testAddingAndAssertingEquals(ids, datas);
	}

	private static MerkleSortTree newMerkleSortedTree() {
		CryptoSetting cryptoSetting = createCryptoSetting();
		MemoryKVStorage storage = new MemoryKVStorage();
		MerkleSortTree mst = new MerkleSortTree(cryptoSetting, DEFAULT_MKL_KEY_PREFIX, storage);

		return mst;
	}

	private static void testAddingAndAssertingEquals(long[] ids, byte[][] datas) {
		CryptoSetting cryptoSetting = createCryptoSetting();
		MemoryKVStorage storage = new MemoryKVStorage();
		MerkleSortTree mst = new MerkleSortTree(cryptoSetting, DEFAULT_MKL_KEY_PREFIX, storage);

		assertNull(mst.getRootHash());

		addDatas(ids, datas, mst);

		HashDigest rootHash = mst.getRootHash();
		assertNotNull(rootHash);

		assertDataEquals(ids, datas, mst);

		// reload merkle tree from storage;
		MerkleSortTree mst1 = new MerkleSortTree(rootHash, cryptoSetting, DEFAULT_MKL_KEY_PREFIX, storage);

		assertEquals(rootHash, mst1.getRootHash());
		assertDataEquals(ids, datas, mst1);
	}

	private static void addDatas(long[] ids, byte[][] datas, MerkleSortTree mst) {
		for (int i = 0; i < ids.length; i++) {
			mst.set(ids[i], datas[i]);
		}
		mst.commit();
	}

	private static void assertDataEquals(long[] ids, byte[][] datas, MerkleSortTree mst) {
		assertEquals(ids.length, mst.getCount());

		int i;
		for (i = 0; i < ids.length; i++) {
			long id = ids[i];
			byte[] mdata = mst.getBytes(id);
			assertNotNull(mdata);

			HashDigest dataHash = HASH_FUNCTION.hash(datas[i]);
			HashDigest dataHash1 = HASH_FUNCTION.hash(mdata);
			assertEquals(dataHash, dataHash1);
			assertArrayEquals(datas[i], mdata);
		}
	}

	private static byte[][] generateRandomData(int count) {
		Random random = new Random();
		byte[][] datas = new byte[count][];
		for (int i = 0; i < count; i++) {
			datas[i] = new byte[8];
			random.nextBytes(datas[i]);
		}
		return datas;
	}

	private static CryptoSetting createCryptoSetting() {
		CryptoSetting cryptoSetting = Mockito.mock(CryptoSetting.class);
		when(cryptoSetting.getAutoVerifyHash()).thenReturn(true);
		when(cryptoSetting.getHashAlgorithm()).thenReturn(HASH_ALGORITHM.code());
		return cryptoSetting;
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
}