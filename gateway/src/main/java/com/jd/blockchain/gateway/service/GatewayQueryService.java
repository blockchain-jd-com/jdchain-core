package com.jd.blockchain.gateway.service;

import com.jd.blockchain.crypto.HashDigest;
import com.jd.blockchain.ledger.ParticipantNode;
import com.jd.blockchain.sdk.DecompliedContractInfo;
import com.jd.blockchain.sdk.LedgerInitAttributes;

/**
 * queryService only for gateway;
 * @Author zhaogw
 * @Date 2019/2/22 10:37
 */
public interface GatewayQueryService {
    /**
     * get all ledgers hashs;
     * @param fromIndex
     * @param count
     */
    HashDigest[] getLedgersHash(int fromIndex, int count);

    /**
     * get the participants by range;
     * @param ledgerHash
     * @param fromIndex
     * @param count
     * @return
     */
    ParticipantNode[] getConsensusParticipants(HashDigest ledgerHash, int fromIndex, int count);

    /**
     * 获取账本初始化配置信息
     *
     * @param ledgerHash
     *     账本Hash
     * @return
     */
    LedgerInitAttributes getLedgerBaseSettings(HashDigest ledgerHash);

    /**
     * 获取账本指定合约信息
     *
     * @param ledgerHash
     *     账本Hash
     * @param address
     *     合约地址
     * @return
     */
    DecompliedContractInfo getContractSettings(HashDigest ledgerHash, String address);
}
