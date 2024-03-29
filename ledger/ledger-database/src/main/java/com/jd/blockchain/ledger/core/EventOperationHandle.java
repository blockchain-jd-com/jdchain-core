package com.jd.blockchain.ledger.core;

import com.jd.blockchain.ledger.BlockchainIdentity;
import com.jd.blockchain.ledger.BytesValue;
import com.jd.blockchain.ledger.EventPublishOperation;
import utils.Bytes;

/**
 * 事件交易处理
 */
public interface EventOperationHandle {

    /**
     * 获取事件账户
     *
     * @param address
     * @return
     */
    EventAccount getAccount(Bytes address);

    /**
     * 注册事件账户
     *
     * @param identity
     * @return
     */
    EventAccount registerAccount(BlockchainIdentity identity);

    /**
     * 发布用户自定义事件
     *
     * @param address
     * @param events
     * @return
     */
    void publish(Bytes address, EventPublishOperation.EventEntry[] events);

    /**
     * 发布系统事件；<br>
     *
     * @param eventName      事件名；
     * @param content        消息内容；
     * @param latestSequence 该事件序列的最新序号；
     * @return
     */
    long publish(String eventName, BytesValue content, long latestSequence);

}
