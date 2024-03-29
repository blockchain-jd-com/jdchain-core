package com.jd.blockchain.consensus.raft.server;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.core.StateMachineAdapter;
import com.alipay.sofa.jraft.entity.LeaderChangeContext;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.error.RaftError;
import com.alipay.sofa.jraft.error.RaftException;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotReader;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotWriter;
import com.alipay.sofa.jraft.util.Utils;
import com.jd.blockchain.consensus.raft.consensus.*;
import com.jd.blockchain.consensus.raft.util.LoggerUtils;
import com.jd.blockchain.crypto.HashDigest;
import com.jd.blockchain.ledger.LedgerBlock;
import com.jd.blockchain.ledger.core.LedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class RaftConsensusStateMachine extends StateMachineAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RaftConsensusStateMachine.class);

    private final BlockCommitter committer;
    private final BlockSerializer serializer;
    private final BlockSyncer blockSyncer;
    private final LedgerRepository ledgerRepository;

    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private final AtomicLong currentBlockHeight = new AtomicLong(1);

    public RaftConsensusStateMachine(BlockCommitter committer, BlockSerializer serializer, BlockSyncer blockSyncer, LedgerRepository ledgerRepository) {
        this.committer = committer;
        this.serializer = serializer;
        this.ledgerRepository = ledgerRepository;
        this.blockSyncer = blockSyncer;
    }

    public void onStart() {
        LedgerBlock ledgerBlock = ledgerRepository.retrieveLatestBlock();
        this.currentBlockHeight.set(ledgerBlock.getHeight());
    }

    @Override
    public void onApply(Iterator iterator) {
        int index = 0;
        int applied = 0;
        BlockClosure closure = null;
        Block block = null;
        try {
            while (iterator.hasNext()) {
                Status status = Status.OK();
                try {
                    if (iterator.done() != null) {
                        closure = (BlockClosure) iterator.done();
                        block = closure.getBlock();
                    } else {
                        final ByteBuffer data = iterator.getData();
                        block = serializer.deserialize(data.array());
                    }

                    LoggerUtils.debugIfEnabled(LOGGER, "apply state machine log index: {} term: {}, block: {}", iterator.getIndex(), iterator.getTerm(), block);

                    try {
                        boolean result = committer.commitBlock(block, closure);
                        if (result) {
                            this.currentBlockHeight.set(block.getHeight());
                        }
                    } catch (BlockCommittedException bce) {
                        LOGGER.info("block height: {} committed,  ignore it", bce.getHeight());
                    }
                } catch (Throwable e) {
                    LOGGER.error("apply state machine log index: {} term: {} , txList: {} error", iterator.getIndex(), iterator.getTerm(), block, e);
                    index++;
                    status.setError(RaftError.UNKNOWN, e.toString());
                    throw e;
                }

                applied++;
                index++;
                iterator.next();
            }
        } catch (Throwable t) {
            LOGGER.error("RaftConsensusStateMachine caught error.", t);
            Status status = new Status(RaftError.ESTATEMACHINE, "RaftConsensusStateMachine caught error: %s.", t.getLocalizedMessage());
            iterator.setErrorAndRollback(index - applied, status);
            if (closure != null) {
                closure.run(status);
            }
        }

    }

    @Override
    public void onSnapshotSave(SnapshotWriter writer, Closure done) {
        long height = this.currentBlockHeight.get();

        Utils.runInThread(() -> {
            final RaftSnapshotFile snapshot = new RaftSnapshotFile(writer.getPath());

            RaftSnapshotFile.RaftSnapshotData snapshotData = new RaftSnapshotFile.RaftSnapshotData();
            snapshotData.setHeight(height);

            if (snapshot.save(snapshotData)) {
                if (writer.addFile(snapshot.getName())) {
                    done.run(Status.OK());
                } else {
                    done.run(new Status(RaftError.EIO, "fail add snap file to writer"));
                }
            } else {
                done.run(new Status(RaftError.EIO, "fail save snapshot %s", snapshot.getFilePath()));
            }
        });
    }


    @Override
    public boolean onSnapshotLoad(SnapshotReader reader) {

        if (isLeader()) {
            LOGGER.warn("leader can not  load snapshot");
            return false;
        }

        RaftSnapshotFile raftSnapshotFile = new RaftSnapshotFile(reader.getPath());

        if (reader.getFileMeta(raftSnapshotFile.getName()) == null) {
            LOGGER.error("fail to find data file in {}", reader.getPath());
            return false;
        }

        try {
            RaftSnapshotFile.RaftSnapshotData load = raftSnapshotFile.load();
            long snapshotHeight = load.getHeight();
            long blockHeight = this.currentBlockHeight.get();
            if (snapshotHeight <= blockHeight) {
                LOGGER.info("snapshot height: {} less than current block height: {}, ignore it", snapshotHeight, blockHeight);
                return true;
            }

            try {
                catchUp(snapshotHeight);
            } catch (BlockSyncException e) {
                LOGGER.error("node sync block error, please install latest db data");
                throw new IllegalStateException(e);
            }

            this.currentBlockHeight.set(snapshotHeight);

            return true;
        } catch (final IOException e) {
            LOGGER.error("fail to load snapshot from {}", raftSnapshotFile.getFilePath());
            return false;
        }


    }

    private void catchUp(long maxHeight) throws BlockSyncException {
        HashDigest ledger = ledgerRepository.getHash();
        while (this.currentBlockHeight.get() < maxHeight) {
            PeerId leader = null;
            if (RaftNodeServerContext.getInstance().getNode(ledger) == null) {
                // 当本地快照与本地账本不一致时，需连接Leader节点进行同步, 此时本地节点尚无leader节点信息, 需要通过客户端进行同步
                RaftNodeServerContext.getInstance().refreshRouteTable(ledger);
            }
            leader = RaftNodeServerContext.getInstance().getLeader(ledger);
            if (leader == null) {
                throw new BlockSyncException("raft group not ready, current leader is none. retry it.");
            }

            blockSyncer.sync(leader.getEndpoint(), ledger, this.currentBlockHeight.get() + 1);
            this.currentBlockHeight.incrementAndGet();
        }
    }

    @Override
    public void onLeaderStart(final long term) {
        super.onLeaderStart(term);
        this.isLeader.set(true);
    }

    @Override
    public void onLeaderStop(final Status status) {
        super.onLeaderStop(status);
        this.isLeader.set(false);
    }

    @Override
    public void onStartFollowing(LeaderChangeContext ctx) {
        super.onStartFollowing(ctx);
        this.isLeader.set(false);
    }

    @Override
    public void onError(RaftException e) {
        LOGGER.error("raft consensus state machine caught error", e);
    }

    @Override
    public void onStopFollowing(LeaderChangeContext ctx) {
        super.onStopFollowing(ctx);
        this.isLeader.set(false);
    }


    public boolean isLeader() {
        return isLeader.get();
    }

}
