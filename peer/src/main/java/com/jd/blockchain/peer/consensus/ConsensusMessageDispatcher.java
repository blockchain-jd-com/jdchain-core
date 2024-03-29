package com.jd.blockchain.peer.consensus;

import com.jd.binaryproto.BinaryProtocol;
import com.jd.blockchain.consensus.BlockStateSnapshot;
import com.jd.blockchain.consensus.service.ConsensusContext;
import com.jd.blockchain.consensus.service.ConsensusMessageContext;
import com.jd.blockchain.consensus.service.MessageHandle;
import com.jd.blockchain.consensus.service.StateSnapshot;
import com.jd.blockchain.crypto.Crypto;
import com.jd.blockchain.crypto.HashDigest;
import com.jd.blockchain.ledger.LedgerBlock;
import com.jd.blockchain.ledger.OperationResult;
import com.jd.blockchain.ledger.TransactionRequest;
import com.jd.blockchain.ledger.TransactionResponse;
import com.jd.blockchain.ledger.TransactionState;
import com.jd.blockchain.ledger.core.LedgerEditor;
import com.jd.blockchain.ledger.core.TransactionBatchProcessor;
import com.jd.blockchain.ledger.core.TransactionEngineImpl;
import com.jd.blockchain.service.TransactionBatchProcess;
import com.jd.blockchain.service.TransactionBatchResultHandle;
import com.jd.blockchain.service.TransactionEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import utils.codec.Base58Utils;
import utils.concurrent.AsyncFuture;
import utils.concurrent.CompletableAsyncFuture;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.jd.blockchain.metrics.LedgerMetrics;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * @author huanghaiquan
 *
 */
@Component
public class ConsensusMessageDispatcher implements MessageHandle {

	@Autowired
	private TransactionEngine txEngine;

	@Autowired
	private MeterRegistry meterRegistry;

	// todo 当账本很多的时候，可能存在内存溢出的问题
	private final Map<String, RealmProcessor> realmProcessorMap = new ConcurrentHashMap<>();

	private final ReentrantLock beginLock = new ReentrantLock();

	//Used by mocked integration test example
	public void setTxEngine(TransactionEngine txEngine) {
		this.txEngine = txEngine;
	}

	@Override
	public String beginBatch(ConsensusContext consensusContext) {
		String realmName = realmName(consensusContext);
		RealmProcessor realmProcessor = realmProcessorMap.get(realmName);
		if (realmProcessor == null) {
			beginLock.lock();
			try {
				realmProcessor = realmProcessorMap.get(realmName);
				if (realmProcessor == null) {
					realmProcessor = initRealmProcessor(realmName);
					realmProcessorMap.put(realmName, realmProcessor);
				}
			} finally {
				beginLock.unlock();
			}
		}
		return realmProcessor.newBatchId();
	}

	@Override
	public StateSnapshot getLatestStateSnapshot(String realName) {
		RealmProcessor realmProcessor = realmProcessorMap.get(realName);
		if (realmProcessor == null) {
			throw new IllegalArgumentException("RealmName is not init!");
		}
		return realmProcessor.getStateSnapshot();
	}

	@Override
	public StateSnapshot getGenesisStateSnapshot(String realName) {
		RealmProcessor realmProcessor = realmProcessorMap.get(realName);
		if (realmProcessor == null) {
			throw new IllegalArgumentException("RealmName is not init!");
		}
		return realmProcessor.getGenesisStateSnapshot();
	}

	@Override
	public int getCommandsNumByCid(String realName, int cid) {

		byte[] hashBytes = Base58Utils.decode(realName);

		HashDigest ledgerHash =  Crypto.resolveAsHashDigest(hashBytes);

		//获得区块高度为cid + 1对应的增量交易数
		return ((TransactionEngineImpl)txEngine).getTxsNumByHeight(ledgerHash, cid + 1);

	}

	@Override
	public byte[][] getCommandsByCid(String realName, int  cid, int currHeightCommandsNum) {

		byte[] hashBytes = Base58Utils.decode(realName);

		HashDigest ledgerHash = Crypto.resolveAsHashDigest(hashBytes);

		// 获得区块高度为cid + 1对应的增量交易内容
		return ((TransactionEngineImpl)txEngine).getTxsByHeight(ledgerHash, cid + 1, currHeightCommandsNum);

	}

	@Override
	public byte[] getBlockHashByCid(String realName, int cid) {

		byte[] hashBytes = Base58Utils.decode(realName);

		HashDigest ledgerHash =  Crypto.resolveAsHashDigest(hashBytes);

		//获得区块高度为cid + 1的区块哈希
		return ((TransactionEngineImpl)txEngine).getBlockHashByCid(ledgerHash, cid + 1);
	}

	@Override
	public long getTimestampByCid(String realName, int cid) {
		byte[] hashBytes = Base58Utils.decode(realName);

		HashDigest ledgerHash =  Crypto.resolveAsHashDigest(hashBytes);

		return ((TransactionEngineImpl)txEngine).getTimestampByHeight(ledgerHash, cid + 1);
	}

	@Override
	public AsyncFuture<byte[]> processOrdered(int messageId, byte[] message, ConsensusMessageContext context) {
		// TODO 要求messageId在同一个批次不重复，但目前暂不验证
		RealmProcessor realmProcessor = realmProcessorMap.get(realmName(context));
		if (realmProcessor == null) {
			throw new IllegalArgumentException("RealmName is not init!");
		}
		if (!realmProcessor.getCurrBatchId().equalsIgnoreCase(batchId(context))) {
			throw new IllegalArgumentException("BatchId is not begin!");
		}
		TransactionRequest txRequest = BinaryProtocol.decode(message);
		return realmProcessor.schedule(txRequest);
	}

	@Override
	public StateSnapshot completeBatch(ConsensusMessageContext context) {
		RealmProcessor realmProcessor = realmProcessorMap.get(realmName(context));
		if (realmProcessor == null) {
			throw new IllegalArgumentException("RealmName is not init!");
		}
		if (!realmProcessor.getCurrBatchId().equalsIgnoreCase(batchId(context))) {
			throw new IllegalArgumentException("BatchId is not begin!");
		}
		return realmProcessor.complete(context.getTimestamp());
	}

	@Override
	public void commitBatch(ConsensusMessageContext context) {
		RealmProcessor realmProcessor = realmProcessorMap.get(realmName(context));
		if (realmProcessor == null) {
			throw new IllegalArgumentException("RealmName is not init!");
		}
		if (!realmProcessor.getCurrBatchId().equalsIgnoreCase(batchId(context))) {
			throw new IllegalArgumentException("BatchId is not begin!");
		}

		realmProcessor.commit();
	}

	@Override
	public void rollbackBatch(int reasonCode, ConsensusMessageContext context) {
		RealmProcessor realmProcessor = realmProcessorMap.get(realmName(context));
		if (realmProcessor == null) {
			throw new IllegalArgumentException("RealmName is not init!");
		}
		if (!realmProcessor.getCurrBatchId().equalsIgnoreCase(batchId(context))) {
			throw new IllegalArgumentException("BatchId is not begin!");
		}
		realmProcessor.rollback(reasonCode);
	}

	@Override
	public AsyncFuture<byte[]> processUnordered(byte[] message) {
		// TODO Auto-generated method stub
		throw new IllegalArgumentException("Not implemented!");
	}

	private RealmProcessor initRealmProcessor(String realmName) {
		RealmProcessor realmProcessor = new RealmProcessor();
		byte[] hashBytes = Base58Utils.decode(realmName);
		HashDigest ledgerHash = Crypto.resolveAsHashDigest(hashBytes);
		realmProcessor.realmName = realmName;
		realmProcessor.ledgerHash = ledgerHash;
		realmProcessor.metrics = new LedgerMetrics(realmName, meterRegistry);
		return realmProcessor;
	}

	private String realmName(ConsensusContext consensusContext) {
		return consensusContext.getRealmName();
	}

	private String batchId(ConsensusMessageContext context) {
	    return context.getBatchId();
    }

	private final class RealmProcessor {

		private final Lock realmLock = new ReentrantLock();

		private String currBatchId;

		// todo 暂不处理队列溢出导致的OOM问题
		private final ExecutorService asyncBlExecutor = Executors.newSingleThreadExecutor();

		private Map<TransactionResponse, CompletableAsyncFuture<byte[]>> txResponseMap;

		private TransactionBatchResultHandle batchResultHandle;

		private final AtomicLong batchIdIndex = new AtomicLong();

		private TransactionBatchProcess txBatchProcess;

		HashDigest ledgerHash;

		String realmName;

		private LedgerMetrics metrics;

		public String getRealmName() {
			return realmName;
		}

		public TransactionBatchProcess getTxBatchProcess() {
			return txBatchProcess;
		}

		public AtomicLong getBatchIdIndex() {
			return batchIdIndex;
		}

		public HashDigest getLedgerHash() {
			return ledgerHash;
		}

		public String getCurrBatchId() {
			return currBatchId;
		}

		public String newBatchId() {
			realmLock.lock();
			try {
				if (currBatchId == null) {
					currBatchId = getRealmName() + "-" + getBatchIdIndex().getAndIncrement();
				}
				if (txResponseMap == null) {
					txResponseMap = new ConcurrentHashMap<>();
				}
				if (txBatchProcess == null) {
					txBatchProcess = txEngine.createNextBatch(ledgerHash, metrics);
				}
			} finally {
				realmLock.unlock();
			}
			return currBatchId;
		}

		public StateSnapshot getStateSnapshot() {
			TransactionBatchProcess txBatchProcess = getTxBatchProcess();
			if (txBatchProcess instanceof TransactionBatchProcessor) {
				LedgerBlock block = ((TransactionBatchProcessor) txBatchProcess).getLatestBlock();
				return new BlockStateSnapshot(block.getHeight(), block.getTimestamp(), block.getHash());
			} else {
				throw new IllegalStateException("Tx batch process is not instance of TransactionBatchProcessor !!!");
			}
		}

		public StateSnapshot getGenesisStateSnapshot() {
			TransactionBatchProcess txBatchProcess = getTxBatchProcess();
			if (txBatchProcess instanceof TransactionBatchProcessor) {
				LedgerBlock block = ((TransactionBatchProcessor) txBatchProcess).getGenesisBlock();
				return new BlockStateSnapshot(block.getHeight(), block.getTimestamp(), block.getHash());
			} else {
				throw new IllegalStateException("Tx batch process is not instance of TransactionBatchProcessor !!!");
			}
		}

		public AsyncFuture<byte[]> schedule(TransactionRequest txRequest) {
			CompletableAsyncFuture<byte[]> asyncTxResult = new CompletableAsyncFuture<>();
			TransactionResponse resp = getTxBatchProcess().schedule(txRequest);
			txResponseMap.put(resp, asyncTxResult);
			return asyncTxResult;
		}

		public StateSnapshot complete(long timestamp) {
			LedgerEditor.TIMESTAMP_HOLDER.set(timestamp);
			try {
				batchResultHandle = getTxBatchProcess().prepare();
				LedgerBlock currBlock = batchResultHandle.getBlock();
				long blockHeight = currBlock.getHeight();
				long blockTimestamp = currBlock.getTimestamp();
				HashDigest blockHash = currBlock.getHash();
				asyncBlExecute(new HashMap<>(txResponseMap), blockHeight, blockHash, blockTimestamp);
				return new BlockStateSnapshot(blockHeight, currBlock.getTimestamp(), blockHash);
			} finally {
				LedgerEditor.TIMESTAMP_HOLDER.remove();
			}
		}

		public void commit() {
			realmLock.lock();
			try {
				if (batchResultHandle == null) {
					throw new IllegalArgumentException("BatchResultHandle is null, complete() is not execute !");
				}
				batchResultHandle.commit();
				currBatchId = null;
				txResponseMap = null;
				txBatchProcess = null;
				batchResultHandle = null;
			} finally {
				realmLock.unlock();
			}
		}

		public void rollback(int reasonCode) {
			realmLock.lock();
			try {
				if (batchResultHandle != null) {
					batchResultHandle.cancel(TransactionState.valueOf((byte) reasonCode));
				}
				currBatchId = null;
				txResponseMap = null;
				txBatchProcess = null;
				batchResultHandle = null;
				if (txEngine != null && txEngine instanceof TransactionEngineImpl) {
					((TransactionEngineImpl) txEngine).freeBatch(ledgerHash);
					((TransactionEngineImpl) txEngine).resetNewBlockEditor(ledgerHash);
				} else {
					if (txEngine == null) {
						throw new IllegalStateException("You should init txEngine first !!!");
					} else {
						throw new IllegalStateException("TxEngine is not instance of TransactionEngineImpl !!!");
					}
				}
			} finally {
				realmLock.unlock();
			}
		}

		private void asyncBlExecute(Map<TransactionResponse, CompletableAsyncFuture<byte[]>> asyncMap,
                                    long blockHeight, HashDigest blockHash, long blockGenerateTime) {
			asyncBlExecutor.execute(() -> {
				// 填充应答结果
				for (Map.Entry<TransactionResponse, CompletableAsyncFuture<byte[]>> entry : asyncMap.entrySet()) {
					CompletableAsyncFuture<byte[]> asyncResult = entry.getValue();
					TxResponse txResponse = new TxResponse(entry.getKey());
					txResponse.setBlockHeight(blockHeight);
					txResponse.setBlockGenerateTime(blockGenerateTime);
					txResponse.setBlockHash(blockHash);
					asyncResult.complete(BinaryProtocol.encode(txResponse, TransactionResponse.class));
				}
			});
		}

		private final class TxResponse implements TransactionResponse {

			private long blockHeight;

			private long blockGenerateTime;

			private HashDigest blockHash;

			private TransactionResponse txResp;

			public TxResponse(TransactionResponse txResp) {
				this.txResp = txResp;
			}

			public void setBlockHeight(long blockHeight) {
				this.blockHeight = blockHeight;
			}

			public void setBlockGenerateTime(long blockGenerateTime) {
				this.blockGenerateTime = blockGenerateTime;
			}

			public void setBlockHash(HashDigest blockHash) {
				this.blockHash = blockHash;
			}

			@Override
			public HashDigest getContentHash() {
				return this.txResp.getContentHash();
			}

			@Override
			public TransactionState getExecutionState() {
				return this.txResp.getExecutionState();
			}

			@Override
			public HashDigest getBlockHash() {
				return this.blockHash;
			}

			@Override
			public long getBlockHeight() {
				return this.blockHeight;
			}

			@Override
			public boolean isSuccess() {
				return this.txResp.isSuccess();
			}

			@Override
			public OperationResult[] getOperationResults() {
				return txResp.getOperationResults();
			}

			@Override
			public long getBlockGenerateTime() {
				return blockGenerateTime;
			}
		}
	}
}
