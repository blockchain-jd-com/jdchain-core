/**
 * Copyright: Copyright 2016-2020 JD.COM All Right Reserved FileName: MsgQueueConsumer Author:
 * shaozhuguang Department: 区块链研发部 Date: 2018/11/5 下午10:38 Description:
 */
package com.jd.blockchain.consensus.mq.consumer;

import java.io.Closeable;

/**
 * @author shaozhuguang
 * @create 2018/11/5
 * @since 1.0.0
 */
public interface MQConsumer extends Closeable {

  void connect(MQHandler msgQueueHandler) throws Exception;

  void start() throws Exception;
}
