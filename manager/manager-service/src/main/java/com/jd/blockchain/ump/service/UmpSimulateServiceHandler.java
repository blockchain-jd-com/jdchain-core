package com.jd.blockchain.ump.service;

import com.jd.blockchain.ump.model.config.PeerLocalConfig;
import com.jd.blockchain.ump.model.config.PeerSharedConfigVv;
import com.jd.blockchain.ump.model.user.UserKeys;
import com.jd.blockchain.ump.model.user.UserKeysVv;
import com.jd.blockchain.ump.service.consensus.providers.BftsmartConsensusConfigProvider;
import org.apache.commons.codec.binary.Hex;
import org.springframework.stereotype.Service;

import java.util.Random;

@Service
public class UmpSimulateServiceHandler implements UmpSimulateService {

    private static final Random RANDOM_ROCKSDB = new Random();

    private static final String SHARED_KEY = "JDChain";

    private static final int TOTAL_SIZE = 4;

    private static final String LOCALHOST = "127.0.0.1";

    private static final String CONSENSUS_PROVIDER = BftsmartConsensusConfigProvider.BFTSMART_PROVIDER;

    private static final String CONSENSUS_CONF = BftsmartConsensusConfigProvider.BFTSMART_CONFIG_FILE;

    private static final int INIT_PORT_START = 9000;

    private static final String[] PUBKEYS = new String[]{
            "7VeRLdGtSz1Y91gjLTqEdnkotzUfaAqdap3xw6fQ1yKHkvVq",
            "7VeRBsHM2nsGwP8b2ufRxz36hhNtSqjKTquzoa4WVKWty5sD",
            "7VeRAr3dSbi1xatq11ZcF7sEPkaMmtZhV9shonGJWk9T4pLe",
            "7VeRKoM5RE6iFXr214Hsiic2aoqCQ7MEU1dHQFRnjXQcReAS"};

    private static final String[] PRIVKEYS = new String[]{
            "177gjzHTznYdPgWqZrH43W3yp37onm74wYXT4v9FukpCHBrhRysBBZh7Pzdo5AMRyQGJD7x",
            "177gju9p5zrNdHJVEQnEEKF4ZjDDYmAXyfG84V5RPGVc5xFfmtwnHA7j51nyNLUFffzz5UT",
            "177gjtwLgmSx5v1hFb46ijh7L9kdbKUpJYqdKVf9afiEmAuLgo8Rck9yu5UuUcHknWJuWaF",
            "177gk1pudweTq5zgJTh8y3ENCTwtSFsKyX7YnpuKPo7rKgCkCBXVXh5z2syaTCPEMbuWRns"};

    private static final String ENCODE_PWD = "DYu3G8aGTMBW1WrTw76zxQJQU4DHLw9MLyy7peG4LKkY";

    private static final String BINDING_OUT = "../";

    private static final String[] DB_URIS = new String[]{
            "rocksdb:///Users/shaozhuguang/Documents/simulate/peer0/rocksdb",
            "rocksdb:///Users/shaozhuguang/Documents/simulate/peer1/rocksdb",
            "rocksdb:///Users/shaozhuguang/Documents/simulate/peer2/rocksdb",
            "rocksdb:///Users/shaozhuguang/Documents/simulate/peer3/rocksdb"};

    private static final String DB_PWD = "";

    private static final String DB_NAME = "rocksdb_";

    private static final String[] PEER_PATHS = new String[]{
            "/Users/shaozhuguang/Documents/simulate/peer0",
            "/Users/shaozhuguang/Documents/simulate/peer1",
            "/Users/shaozhuguang/Documents/simulate/peer2",
            "/Users/shaozhuguang/Documents/simulate/peer3"};

    private static final String[] CONSENSUS_NODES = new String[]{
            "127.0.0.1:6000",
            "127.0.0.1:6010",
            "127.0.0.1:6020",
            "127.0.0.1:6030"};


    @Override
    public UserKeysVv userKeysVv(int nodeId) {

        UserKeys userKeys = userKeys(nodeId);

        return userKeys.toUserKeysVv();
    }

    @Override
    public UserKeys userKeys(int nodeId) {

        return new UserKeys("Peer-" + nodeId, PRIVKEYS[nodeId], PUBKEYS[nodeId], ENCODE_PWD);
    }

    @Override
    public PeerLocalConfig nodePeerLocalConfig(int nodeId, boolean isMaster) {

        UserKeys userKeys = userKeys(nodeId);

        return peerSharedConfigVv(nodeId, isMaster).toPeerLocalConfig(userKeys);
    }

    @Override
    public PeerSharedConfigVv peerSharedConfigVv(int nodeId, boolean isMaster) {

        PeerSharedConfigVv sharedConfigVv = new PeerSharedConfigVv();

        sharedConfigVv.setSharedKey(SHARED_KEY);
        sharedConfigVv.setName(SHARED_KEY + "-" + nodeId);
        sharedConfigVv.setInitAddr(LOCALHOST);
        sharedConfigVv.setInitPort(INIT_PORT_START + nodeId * 10);
        sharedConfigVv.setConsensusNode(CONSENSUS_NODES[nodeId]);
        sharedConfigVv.setPubKey(PUBKEYS[nodeId]);
        sharedConfigVv.setUserId(nodeId);
        sharedConfigVv.setPeerPath(PEER_PATHS[nodeId]);
        sharedConfigVv.setDbName(dbName());

        if (isMaster) {
            sharedConfigVv.setLedgerName(ledgerName());
            sharedConfigVv.setNodeSize(TOTAL_SIZE);
        } else {
            sharedConfigVv.setMasterAddr(LOCALHOST);
            sharedConfigVv.setMasterPort(8080);
        }

        return sharedConfigVv;
    }

    private String ledgerName() {

        byte[] nameBytes = new byte[4];

        RANDOM_ROCKSDB.nextBytes(nameBytes);

        return Hex.encodeHexString(nameBytes);
    }

    private String dbName() {

        byte[] nameBytes = new byte[4];

        RANDOM_ROCKSDB.nextBytes(nameBytes);

        return DB_NAME + Hex.encodeHexString(nameBytes);
    }
}
