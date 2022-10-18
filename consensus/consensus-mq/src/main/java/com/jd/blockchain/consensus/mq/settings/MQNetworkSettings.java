/**
 * Copyright: Copyright 2016-2020 JD.COM All Right Reserved
 * FileName: com.jd.blockchain.mq.config.MsgQueueNetworkSettings
 * Author: shaozhuguang
 * Department: 区块链研发部
 * Date: 2018/12/12 上午11:43
 * Description:
 */
package com.jd.blockchain.consensus.mq.settings;

import com.jd.binaryproto.DataContract;
import com.jd.binaryproto.DataField;
import com.jd.binaryproto.PrimitiveType;
import com.jd.blockchain.consts.DataCodes;

/**
 * @author shaozhuguang
 * @create 2018/12/12
 * @since 1.0.0
 */
@DataContract(code = DataCodes.CONSENSUS_MQ_NETWORK_SETTINGS)
public interface MQNetworkSettings {

    @DataField(order = 0, primitiveType = PrimitiveType.TEXT)
    String getServer();

    @DataField(order = 1, primitiveType = PrimitiveType.TEXT)
    String getTxTopic();

    @DataField(order = 2, primitiveType = PrimitiveType.TEXT)
    String getTxResultTopic();

    @DataField(order = 3, primitiveType = PrimitiveType.TEXT)
    String getMsgTopic();

    @DataField(order = 4, primitiveType = PrimitiveType.TEXT)
    String getBlockTopic();

    @DataField(order = 5, primitiveType = PrimitiveType.TEXT)
    String getMsgResultTopic();
}