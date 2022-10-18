/**
 * Copyright: Copyright 2016-2020 JD.COM All Right Reserved
 * FileName: com.jd.blockchain.consensus.mq.config.MsgQueueClientIncomingSettings
 * Author: shaozhuguang
 * Department: 区块链研发部
 * Date: 2018/12/13 下午4:35
 * Description:
 */
package com.jd.blockchain.consensus.mq.settings;

import com.jd.binaryproto.DataContract;
import com.jd.binaryproto.DataField;
import com.jd.binaryproto.PrimitiveType;
import com.jd.blockchain.consensus.ClientIncomingSettings;
import com.jd.blockchain.consts.DataCodes;
import com.jd.blockchain.crypto.PubKey;

/**
 *
 * @author shaozhuguang
 * @create 2018/12/13
 * @since 1.0.0
 */
@DataContract(code = DataCodes.CONSENSUS_MQ_CLI_INCOMING_SETTINGS)
public interface MQClientIncomingSettings extends ClientIncomingSettings {

    @DataField(order = 1, primitiveType=PrimitiveType.BYTES)
    PubKey getPubKey();
}