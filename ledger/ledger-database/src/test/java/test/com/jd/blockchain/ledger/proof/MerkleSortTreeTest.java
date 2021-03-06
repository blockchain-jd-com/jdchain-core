package test.com.jd.blockchain.ledger.proof;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.jd.blockchain.crypto.CryptoAlgorithm;
import com.jd.blockchain.crypto.HashDigest;
import com.jd.blockchain.crypto.service.classic.ClassicAlgorithm;
import com.jd.blockchain.ledger.core.MerkleProofException;
import com.jd.blockchain.ledger.merkletree.BytesConverter;
import com.jd.blockchain.ledger.merkletree.DataPolicy;
import com.jd.blockchain.ledger.merkletree.DefaultDataPolicy;
import com.jd.blockchain.ledger.merkletree.MerkleSortTree;
import com.jd.blockchain.ledger.merkletree.MerkleTreeKeyExistException;
import com.jd.blockchain.ledger.merkletree.MerkleValue;
import com.jd.blockchain.ledger.merkletree.TreeOptions;
import com.jd.blockchain.storage.service.utils.MemoryKVStorage;

import utils.AbstractSkippingIterator;
import utils.ArrayUtils;
import utils.SkippingIterator;
import utils.io.BytesEncoding;
import utils.io.BytesUtils;

public class MerkleSortTreeTest {

	private static final String DEFAULT_MKL_KEY_PREFIX = "";

	private static final CryptoAlgorithm HASH_ALGORITHM = ClassicAlgorithm.SHA256;

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

	@Test
	public void testAddDuplicatedData() {
		Random random = new Random();
		byte[] data = new byte[32];
		random.nextBytes(data);

		MemoryKVStorage storage = new MemoryKVStorage();

		// 配置选项设置为”不报告重复数据项“；
		// 以不同的 id 重复设置两个相同的数据，预期不会报告异常；
		MerkleProofException ex = null;
		try {
			TreeOptions options = TreeOptions.build().setDefaultHashAlgorithm(HASH_ALGORITHM.code())
					.setReportKeyStorageConfliction(false);
			MerkleSortTree<byte[]> mst = MerkleSortTree.createBytesTree(options, DEFAULT_MKL_KEY_PREFIX, storage);

			mst.set(1, data);
			mst.set(2, data);

			mst.commit();
		} catch (MerkleProofException e) {
			ex = e;
		}
		assertNull(ex);

		// 配置选项设置为”报告重复数据项“；
		// 以不同的 id 重复设置两个相同的数据，预期将报告异常；
		ex = null;
		try {
			TreeOptions options = TreeOptions.build().setDefaultHashAlgorithm(HASH_ALGORITHM.code())
					.setReportKeyStorageConfliction(true);
			MerkleSortTree<byte[]> mst = MerkleSortTree.createBytesTree(options, DEFAULT_MKL_KEY_PREFIX, storage);

			mst.set(1, data);
			mst.set(2, data);

			mst.commit();
		} catch (MerkleProofException e) {
			ex = e;
		}
		assertNotNull(ex);
	}

	/**
	 * 测试读未提交数据；
	 */
	@Test
	public void testReadUncommitting() {
		int count = 10;
		byte[][] datas = generateRandomData(count);
		long[] ids = generateSeqenceIDs(0, count);

		TreeOptions options = createTreeOptions();
		MemoryKVStorage storage = new MemoryKVStorage();
		MerkleSortTree<byte[]> mst = MerkleSortTree.createBytesTree(options, DEFAULT_MKL_KEY_PREFIX, storage);

		addDatas(ids, datas, mst);

		// 验证未提交之前能够读取到对应的数据；
		assertNull(mst.getRootHash());
		assertEquals(0, mst.getCount());
		assertEquals(ids[ids.length - 1], mst.getMaxId());

		assertDataExists(mst, ids, datas);

		mst.commit();

		assertNotNull(mst.getRootHash());
		assertDataEquals(mst, ids, datas);

		HashDigest rootHash = mst.getRootHash();
		mst = MerkleSortTree.createBytesTree(rootHash, options, DEFAULT_MKL_KEY_PREFIX, storage);
		assertDataEquals(mst, ids, datas);

		// 在已经有数据的默克尔树中以编码顺序递增的方式加入数据，验证在未提交之前能够读取到新加入的数据；
		int count1 = 200;
		byte[][] datas1 = generateRandomData(count1);
		long[] ids1 = generateSeqenceIDs(count + 10, count1);

		addDatas(ids1, datas1, mst);

		assertEquals(ids1[ids1.length - 1], mst.getMaxId());

		assertDataExists(mst, ids, datas);
		assertDataExists(mst, ids1, datas1);

		mst.commit();
		assertDataExists(mst, ids1, datas1);

		// 在已经有数据的默克尔树中以编码随机不重复产生的方式加入数据，验证在未提交之前能够读取到新加入的数据；
		Set<Long> excludingIDs = createIdSet(ids);
		joinIdSet(ids1, excludingIDs);

		int count2 = 300;
		byte[][] datas2 = generateRandomData(count2);
		long[] ids2 = generateRandomIDs(count2, excludingIDs, true);

		HashDigest rootHash1 = mst.getRootHash();
		mst = MerkleSortTree.createBytesTree(rootHash1, options, DEFAULT_MKL_KEY_PREFIX, storage);

		addDatas(ids2, datas2, mst);

		assertEquals(count + count1, mst.getCount());

		assertDataExists(mst, ids, datas);
		assertDataExists(mst, ids1, datas1);
		assertDataExists(mst, ids2, datas2);

		mst.commit();
		assertEquals(count + count1 + count2, mst.getCount());

		assertDataExists(mst, ids, datas);
		assertDataExists(mst, ids1, datas1);
		assertDataExists(mst, ids2, datas2);
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
	public void testCommitAndCancel() {
		int count1 = 48;
		byte[][] datas1 = generateRandomData(count1);
		long[] ids1 = generateRandomIDs(count1);

		Set<Long> excludingIds = createIdSet(ids1);
		int count2 = 32;
		byte[][] datas2 = generateRandomData(count2);
		long[] ids2 = generateRandomIDs(count2, excludingIds, true);

		TreeOptions options = createTreeOptions();
		MemoryKVStorage storage = new MemoryKVStorage();
		MerkleSortTree<byte[]> mst = MerkleSortTree.createBytesTree(options, DEFAULT_MKL_KEY_PREFIX, storage);

		long expectedMaxId1 = Arrays.stream(ids1).max().getAsLong();

		assertEquals(0, mst.getCount());
		assertNull(mst.getRootHash());
		assertEquals(-1L, mst.getMaxId());

		addDatas(ids1, datas1, mst);

		// 未提交之前总数不会发生变化；
		assertEquals(0, mst.getCount());
		assertNull(mst.getRootHash());
		// “最大编码”会实时更新；
		assertNotNull(mst.getMaxId());
		assertEquals(expectedMaxId1, mst.getMaxId());
		// 迭代器不包含未提交的数据；预期迭代器为空；
		SkippingIterator<MerkleValue<byte[]>> iter = mst.bytesIterator();
		assertEquals(0, iter.getTotalCount());

		// 提交之后才更新属性；
		mst.commit();

		assertEquals(count1, mst.getCount());
		assertNotNull(mst.getRootHash());
		assertNotNull(mst.getMaxId());

		assertEquals(expectedMaxId1, mst.getMaxId());

		assertDataEquals(mst, ids1, datas1);

		// 预期提交后，迭代器反映出最新提交的数据；
		Map<Long, byte[]> dataMap = new HashMap<Long, byte[]>();
		mapIdValues(ids1, datas1, dataMap);
		iter = mst.iterator();
		long[] sortedIds1 = ids1;
		Arrays.sort(sortedIds1);
		assertIteratorSortedAndEquals(iter, count1, sortedIds1, dataMap);

		// 测试写入数据后回滚操作是否符合预期；
		long expectedMaxId2 = Arrays.stream(ids2).max().getAsLong();
		expectedMaxId2 = Math.max(expectedMaxId1, expectedMaxId2);

		HashDigest rootHash1 = mst.getRootHash();

		// 写入数据，但并不提交；
		addDatas(ids2, datas2, mst);

		// 预期未提交之前，根哈希不会变化；
		assertEquals(rootHash1, mst.getRootHash());
		// 预期未提交之前，总数不会变化；
		assertEquals(count1, mst.getCount());
		// 预期“最大编码”属性是实时变化的；
		assertEquals(expectedMaxId2, mst.getMaxId());
		// 预期未提交之前，预期迭代器不会变化；
		iter = mst.iterator();
		assertIteratorSortedAndEquals(iter, count1, sortedIds1, dataMap);

		// 回滚之后，预期所有的属性恢复到上一次提交的结果；
		mst.cancel();

		// 预期“根哈希”维持上次提交之后的结果；
		assertEquals(rootHash1, mst.getRootHash());
		// 预期“总数”维持上次提交之后的结果；
		assertEquals(count1, mst.getCount());
		// 预期“最大编码”属性恢复到上次提交之后的结果；
		assertEquals(expectedMaxId1, mst.getMaxId());
		// 预期迭代器不会变化，维持上次提交之后的结果；
		iter = mst.iterator();
		assertIteratorSortedAndEquals(iter, count1, sortedIds1, dataMap);
	}

	@Test
	public void testIterator() {
		TreeOptions options = createTreeOptions();
		MemoryKVStorage storage = new MemoryKVStorage();
		MerkleSortTree<byte[]> mst = MerkleSortTree.createBytesTree(options, DEFAULT_MKL_KEY_PREFIX, storage);

		// 验证空的迭代器；
		SkippingIterator<MerkleValue<byte[]>> iter = mst.bytesIterator();

		assertEquals(0, iter.getTotalCount());
		assertEquals(-1, iter.getCursor());
		assertFalse(iter.hasNext());
		assertNull(iter.next());

		// 加入数据，验证顺序数据插入的生成的迭代器；
		int count1 = 10;
		byte[][] datas1 = generateRandomData(count1);
		long[] ids1 = generateSeqenceIDs(0, count1);
		HashMap<Long, byte[]> dataMap = new HashMap<Long, byte[]>();
		mapIdValues(ids1, datas1, dataMap);

		addDatasAndCommit(ids1, datas1, mst);

		iter = mst.iterator();

		assertIteratorSortedAndEquals(iter, count1, ids1, dataMap);

		// 随机加入；验证迭代器返回有序的序列；
		Set<Long> excludingIDs = createIdSet(ids1);
		int count2 = (int) power(4, 8) + 1;
		byte[][] datas2 = generateRandomData(count2);
		long[] ids2 = generateRandomIDs(count2, excludingIDs, true);
		mapIdValues(ids2, datas2, dataMap);

		addDatasAndCommit(ids2, datas2, mst);

		long[] totalIds = ArrayUtils.concat(ids1, ids2);
		Arrays.sort(totalIds);

		long totalCount = count1 + count2;
		iter = mst.iterator();

		assertIteratorSortedAndEquals(iter, totalCount, totalIds, dataMap);

		// 验证有跳跃的情形；
		iter = mst.iterator();
		assertEquals(-1, iter.getCursor());

		int index = -1;
		long skipped = 1;

		iter.skip(skipped);
		index += skipped;
		assertEquals(index, iter.getCursor());

		MerkleValue<byte[]> merkleData = iter.next();
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

		// 验证直接跳跃到倒数第 1 条的情形；
		long left = iter.getCount();
		iter.skip(left - 1);

		assertTrue(iter.hasNext());
		assertEquals(1, iter.getCount());

		merkleData = iter.next();
		assertEquals(totalCount - 1, iter.getCursor());
		assertNotNull(merkleData);
		assertEquals(totalIds[(int) totalCount - 1], merkleData.getId());

		assertFalse(iter.hasNext());
		merkleData = iter.next();
		assertNull(merkleData);

		// 验证直接跳跃到末尾的情形；
		iter = mst.iterator();
		assertTrue(iter.hasNext());

		long c = iter.skip(totalCount);
		assertEquals(totalCount, c);
		assertFalse(iter.hasNext());
		merkleData = iter.next();
		assertNull(merkleData);
	}

	/**
	 * 测试包含数据策略中计数大于 1 的数据迭代；
	 */
	@Test
	public void testMultiDataCountIterator() {
		TreeOptions options = createTreeOptions();
		MemoryKVStorage storage = new MemoryKVStorage();

		DataPolicy<byte[]> bytesDataPolicy = new DefaultDataPolicy<byte[]>() {
			@Override
			public byte[] updateData(long id, byte[] origData, byte[] newData) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				if (origData == null) {
					BytesUtils.writeInt(1, out);
				} else {
					int count = BytesUtils.toInt(origData) + 1;
					BytesUtils.writeInt(count, out);
					out.write(origData, 4, origData.length - 4);
				}
				BytesEncoding.writeInNormal(newData, out);
				return out.toByteArray();
			}

			@Override
			public long count(long id, byte[] data) {
				return BytesUtils.toInt(data);
			}

			@Override
			public SkippingIterator<MerkleValue<byte[]>> iterator(long id, byte[] bytesData, long count,
					BytesConverter<byte[]> converter) {
				byte[][] values = new byte[(int) count][];
				ByteArrayInputStream in = new ByteArrayInputStream(bytesData, 4, bytesData.length - 4);
				for (int i = 0; i < values.length; i++) {
					values[i] = BytesEncoding.readInNormal(in);
				}
				return new BytesEntriesIterator(id, values);
			}
		};

		MerkleSortTree<byte[]> mst = MerkleSortTree.createBytesTree(options, DEFAULT_MKL_KEY_PREFIX, storage,
				bytesDataPolicy);

		int count = 16;
		byte[][] datas = generateRandomData(count);
		long[] ids = new long[count];
		int startIndex = 10;
		for (int i = 0; i < startIndex; i++) {
			ids[i] = i;
		}
		// 从 10 开始，连续3条不同的记录使用相同的 编码；
		int testId = startIndex + 2;
		ids[startIndex] = testId;
		ids[startIndex + 1] = testId;
		ids[startIndex + 2] = testId;
		for (int i = 0; i < ids.length - startIndex - 3; i++) {
			ids[startIndex + i + 3] = startIndex + i + 5;
		}

		addDatas(ids, datas, mst);

		mst.commit();

		// 验证所有的数据都能够正常检索；
		SkippingIterator<MerkleValue<byte[]>> iter = mst.iterator();
		assertEquals(count, iter.getTotalCount());

		assertIteratorEquals(count, datas, ids, 0, iter);

		// 验证略过中间数据也能够正常检索：跳跃到连续 id 的前一条；
		iter = mst.iterator();
		iter.skip(startIndex - 1);
		int i = startIndex - 1;
		assertIteratorEquals(count - (startIndex - 1), datas, ids, startIndex - 1, iter);

		// 验证略过中间数据也能够正常检索：跳跃到连续 id 的第1条；
		iter = mst.iterator();
		iter.skip(startIndex);
		i = startIndex;
		{
			MerkleValue<byte[]> v = iter.next();
			assertNotNull(v);
			assertEquals(testId, v.getId());
			assertArrayEquals(datas[i], v.getValue());
			v = iter.next();
			assertNotNull(v);
			assertEquals(testId, v.getId());
			assertArrayEquals(datas[i + 1], v.getValue());
			v = iter.next();
			assertNotNull(v);
			assertEquals(testId, v.getId());
			assertArrayEquals(datas[i + 2], v.getValue());
		}
		assertIteratorEquals(count - (i + 3), datas, ids, i + 3, iter);

		// 验证略过中间数据也能够正常检索：跳跃到连续 id 的第2条；
		iter = mst.iterator();
		iter.skip(startIndex + 1);
		i = startIndex;
		{
			MerkleValue<byte[]> v = iter.next();
			assertNotNull(v);
			assertEquals(testId, v.getId());
			assertArrayEquals(datas[i + 1], v.getValue());
			v = iter.next();
			assertNotNull(v);
			assertEquals(testId, v.getId());
			assertArrayEquals(datas[i + 2], v.getValue());
		}
		assertIteratorEquals(count - (i + 3), datas, ids, i + 3, iter);

		// 验证略过中间数据也能够正常检索：跳跃到连续 id 的第3条；
		iter = mst.iterator();
		iter.skip(startIndex + 2);
		i = startIndex;
		{
			MerkleValue<byte[]> v = iter.next();
			assertNotNull(v);
			assertEquals(testId, v.getId());
			assertArrayEquals(datas[i + 2], v.getValue());
		}
		assertIteratorEquals(count - (i + 3), datas, ids, i + 3, iter);

		// 验证略过中间数据也能够正常检索：跳跃到连续 id 第3条；
		iter = mst.iterator();
		iter.skip(startIndex + 3);
		assertIteratorEquals(count - (startIndex + 3), datas, ids, startIndex + 3, iter);
	}

	private void assertIteratorEquals(int count, byte[][] datas, long[] ids, int startIndex,
			SkippingIterator<MerkleValue<byte[]>> iter) {
		int i = startIndex;
		int c = 0;
		while (iter.hasNext()) {
			MerkleValue<byte[]> v = iter.next();
			assertNotNull(v);
			assertEquals(ids[i], v.getId());
			assertArrayEquals(datas[i], v.getValue());
			i++;
			c++;
		}
		assertEquals(c, count);
	}

	private Set<Long> createIdSet(long[] ids) {
		HashSet<Long> idset = new HashSet<Long>();
		joinIdSet(ids, idset);
		return idset;
	}

	private void joinIdSet(long[] ids, Set<Long> idset) {
		for (int i = 0; i < ids.length; i++) {
			idset.add(ids[i]);
		}
	}

	private void mapIdValues(long[] ids, byte[][] values, Map<Long, byte[]> dataMap) {
		for (int i = 0; i < values.length; i++) {
			dataMap.put(ids[i], values[i]);
		}
	}

	/**
	 * 断言迭代器是有序的，且与指定的 id 和数据一致；
	 * 
	 * @param expectedCount
	 * @param iter
	 */
	private void assertIteratorSortedAndEquals(SkippingIterator<MerkleValue<byte[]>> iter, long expectedCount,
			long[] expectedSortIDs, Map<Long, byte[]> dataMap) {
		assertEquals(expectedCount, iter.getTotalCount());
		assertEquals(-1, iter.getCursor());
		assertTrue(iter.hasNext());
		int i = 0;
		long preId = -1;
		while (iter.hasNext()) {
			MerkleValue<byte[]> merkleData = iter.next();
			assertNotNull(merkleData);
			assertEquals(expectedSortIDs[i], merkleData.getId());
			assertArrayEquals(dataMap.get(expectedSortIDs[i]), merkleData.getValue());
			if (i > 0) {
				assertTrue(merkleData.getId() >= preId);
			}
			preId = merkleData.getId();
			i++;
		}
		assertEquals(expectedCount, i);
	}

	@Test
	public void testCounts() {
		TreeOptions options = createTreeOptions();
		MemoryKVStorage storage = new MemoryKVStorage();
		MerkleSortTree<byte[]> mst = MerkleSortTree.createBytesTree(options, DEFAULT_MKL_KEY_PREFIX, storage);

		HashSet<Long> excludingIDs = new HashSet<Long>();

		int count1 = (int) power(MerkleSortTree.DEFAULT_DEGREE, 2);
		byte[][] datas1 = generateRandomData(count1);
		long[] ids1 = generateRandomIDs(count1, excludingIDs, true);
		addDatasAndCommit(ids1, datas1, mst);

		int count2 = (int) power(MerkleSortTree.DEFAULT_DEGREE, 3);
		byte[][] datas2 = generateRandomData(count2);
		long[] ids2 = generateRandomIDs(count2, excludingIDs, true);
		addDatasAndCommit(ids2, datas2, mst);

		// 合并前两次产生的数据，验证默克尔树中是否已经写入相同的数据；
		long[] ids = ArrayUtils.concat(ids1, ids2);
		byte[][] datas = ArrayUtils.concat(datas1, datas2, byte[].class);
		assertDataEquals(mst, ids, datas);

		// 从存储中重新加载默克尔树，验证默克尔树中是否已经写入相同的数据；
		HashDigest rootHash = mst.getRootHash();
		mst = MerkleSortTree.createBytesTree(rootHash, options, DEFAULT_MKL_KEY_PREFIX, storage);
		assertDataEquals(mst, ids, datas);

		// 对重新加载的默克尔树持续写入，验证重复加载后持续写入的正确性；
		int count3 = 1023;
		byte[][] datas3 = generateRandomData(count3);
		long[] ids3 = generateRandomIDs(count3, excludingIDs, true);
		addDatasAndCommit(ids3, datas3, mst);

		ids = ArrayUtils.concat(ids, ids3);
		datas = ArrayUtils.concat(datas, datas3, byte[].class);
		assertDataEquals(mst, ids, datas);
	}

	/**
	 * 测试插入顺序的不变性；即，相同的数据集合，无论插入顺序如何，最终得到的结果都是相同的；
	 */
	@Test
	public void testImmutability() {
		MerkleSortTree<byte[]> mst1 = newMerkleSortedTree();

		// 创建基准数据；
		int count = 10253;
		byte[][] datas1 = generateRandomData(count);
		long[] ids1 = generateRandomIDs(count);
		addDatasAndCommit(ids1, datas1, mst1);

		// 反转数据的顺序之后写入，校验是否一致；
		byte[][] datas2 = datas1.clone();
		long[] ids2 = ids1.clone();
		ArrayUtils.reverse(datas2);
		ArrayUtils.reverse(ids2);
		MerkleSortTree<byte[]> mst2 = newMerkleSortedTree();
		addDatasAndCommit(ids2, datas2, mst2);
		assertEquals(mst1.getRootHash(), mst2.getRootHash());

		// 随机打乱顺序之后写入，校验是否一致；
		byte[][] datas3 = datas1.clone();
		long[] ids3 = ids1.clone();
		resortRandomly(ids3, datas3);
		MerkleSortTree<byte[]> mst3 = newMerkleSortedTree();
		addDatasAndCommit(ids3, datas3, mst3);
		assertEquals(mst1.getRootHash(), mst3.getRootHash());

		// 先随机打乱顺序，然后分多次写入，验证最终是否得到一致的结果；
		byte[][] datas4 = datas1.clone();
		long[] ids4 = ids1.clone();
		resortRandomly(ids4, datas4);

		MerkleSortTree<byte[]> mst4 = newMerkleSortedTree();
		assertNull(mst4.getRootHash());

		int count4_1 = 1024;
		byte[][] datas4_1 = Arrays.copyOfRange(datas4, 0, count4_1);
		long[] ids4_1 = Arrays.copyOfRange(ids4, 0, count4_1);
		addDatasAndCommit(ids4_1, datas4_1, mst4);
		HashDigest rootHash4_1 = mst4.getRootHash();
		assertNotNull(rootHash4_1);
		assertEquals(count4_1, mst4.getCount());

		int count4_2 = 1203;
		byte[][] datas4_2 = Arrays.copyOfRange(datas4, count4_1, count4_1 + count4_2);
		long[] ids4_2 = Arrays.copyOfRange(ids4, count4_1, count4_1 + count4_2);
		addDatasAndCommit(ids4_2, datas4_2, mst4);
		HashDigest rootHash4_2 = mst4.getRootHash();
		assertNotNull(rootHash4_2);
		assertNotEquals(rootHash4_1, rootHash4_2);
		assertEquals(count4_1 + count4_2, mst4.getCount());

		byte[][] datas4_3 = Arrays.copyOfRange(datas4, count4_1 + count4_2, count);
		long[] ids4_3 = Arrays.copyOfRange(ids4, count4_1 + count4_2, count);
		addDatasAndCommit(ids4_3, datas4_3, mst4);
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
		TreeOptions options = createTreeOptions();
		MemoryKVStorage storage = new MemoryKVStorage();
		MerkleSortTree<byte[]> mst = MerkleSortTree.createBytesTree(options, DEFAULT_MKL_KEY_PREFIX, storage);

		// 验证空的迭代器；
		SkippingIterator<MerkleValue<byte[]>> iter = mst.bytesIterator();

		assertEquals(0, iter.getTotalCount());
		assertEquals(-1, iter.getCursor());
		assertFalse(iter.hasNext());
		assertNull(iter.next());

		// 加入数据，验证顺序数据插入的生成的迭代器；
		int count = 10;
		byte[][] datas = generateRandomData(count);
		long[] ids = generateSeqenceIDs(0, count);

		addDatasAndCommit(ids, datas, mst);
		;

		// 预期默认的 MerkleSortedTree 实现下，写入相同 id 的数据会引发移除；
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

	/**
	 * 生成随机且不重复的编码；
	 * 
	 * @param count
	 * @return
	 */
	private static long[] generateRandomIDs(int count) {
		HashSet<Long> excludingIDs = new HashSet<Long>();
		return generateRandomIDs(count, excludingIDs, true);
	}

	private static long[] generateRandomIDs(int count, Set<Long> excludingIDs, boolean noRepeatly) {
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

	private static MerkleSortTree<byte[]> newMerkleSortedTree() {
		TreeOptions options = createTreeOptions();
		MemoryKVStorage storage = new MemoryKVStorage();
		MerkleSortTree<byte[]> mst = MerkleSortTree.createBytesTree(options, DEFAULT_MKL_KEY_PREFIX, storage);

		return mst;
	}

	private static void testAddingAndAssertingEquals(long[] ids, byte[][] datas) {
		TreeOptions options = createTreeOptions();
		MemoryKVStorage storage = new MemoryKVStorage();

		Counter counter = new Counter();
		MerkleSortTree<byte[]> mst = MerkleSortTree.createBytesTree(options, DEFAULT_MKL_KEY_PREFIX, storage, counter);

		assertNull(mst.getRootHash());

		addDatasAndCommit(ids, datas, mst);

		assertEquals(ids.length, counter.count.get());

		HashDigest rootHash = mst.getRootHash();
		assertNotNull(rootHash);

		assertDataEquals(mst, ids, datas);

		// reload merkle tree from storage;
		MerkleSortTree<byte[]> mst1 = MerkleSortTree.createBytesTree(rootHash, options, DEFAULT_MKL_KEY_PREFIX,
				storage);

		assertEquals(rootHash, mst1.getRootHash());
		assertDataEquals(mst1, ids, datas);
	}

	private static void addDatasAndCommit(long[] ids, byte[][] datas, MerkleSortTree<byte[]> mst) {
		addDatas(ids, datas, mst);
		mst.commit();
	}

	/**
	 * 把指定的数据加入的默克尔树；
	 * 
	 * @param ids
	 * @param datas
	 * @param mst
	 */
	private static void addDatas(long[] ids, byte[][] datas, MerkleSortTree<byte[]> mst) {
		for (int i = 0; i < ids.length; i++) {
			mst.set(ids[i], datas[i]);
		}
	}

	/**
	 * 断言默克尔树中的数据总数和内容与指定的 id 列表和数据列表一致；
	 * 
	 * @param mst
	 * @param ids
	 * @param datas
	 */
	private static void assertDataEquals(MerkleSortTree<byte[]> mst, long[] ids, byte[][] datas) {
		assertEquals(ids.length, mst.getCount());

		assertDataExists(mst, ids, datas);
	}

	/**
	 * 断言默克尔树中存在指定的数据；
	 * 
	 * @param mst
	 * @param ids
	 * @param datas
	 */
	private static void assertDataExists(MerkleSortTree<byte[]> mst, long[] ids, byte[][] datas) {
		int i;
		for (i = 0; i < ids.length; i++) {
			long id = ids[i];
			byte[] mdata = mst.get(id);
			assertNotNull(mdata);
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

	private static TreeOptions createTreeOptions() {
		return TreeOptions.build().setDefaultHashAlgorithm(HASH_ALGORITHM.code()).setVerifyHashOnLoad(true)
				.setReportKeyStorageConfliction(true);
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

	private static class Counter extends DefaultDataPolicy<byte[]> {

		private AtomicInteger count = new AtomicInteger(0);

		@Override
		public byte[] updateData(long id, byte[] origData, byte[] newData) {
			try {
				return super.updateData(id, origData, newData);
			} finally {
				count.incrementAndGet();
			}
		}
	}

	private static class BytesEntriesIterator extends AbstractSkippingIterator<MerkleValue<byte[]>> {

		private long id;

		private byte[][] items;

		public BytesEntriesIterator(long id, byte[][] items) {
			this.id = id;
			this.items = items;
		}

		@Override
		public long getTotalCount() {
			return items.length;
		}

		@Override
		protected MerkleValue<byte[]> get(long cursor) {
			return new BytesIDValue(id, items[(int) cursor]);
		}
	}

	private static class BytesIDValue implements MerkleValue<byte[]> {

		private long id;

		private byte[] value;

		private BytesIDValue(long id, byte[] value) {
			this.id = id;
			this.value = value;
		}

		@Override
		public long getId() {
			return id;
		}

		/**
		 * 数据字节；
		 * 
		 * @return
		 */
		@Override
		public byte[] getValue() {
			return value;
		}

	}
}
