package com.jd.blockchain.peer.mysql.mapper;

import com.jd.blockchain.peer.mysql.entity.EventInfo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

/**
 * @Author: zhangshuang
 * @Date: 2022/5/9 2:51 PM
 * Version 1.0
 */
@Mapper
public interface EventInfoMapper {
    @Insert("INSERT INTO jdchain_event_accounts (`ledger`, `event_account_address`, `event_account_pubkey`, `event_account_roles`, `event_account_privileges`, `event_account_creator`, `event_account_block_height`, `event_account_tx_hash`) " +
            "VALUES (#{ledger}, #{event_account_address}, #{event_account_pubkey}, #{event_account_roles}, #{event_account_privileges}, #{event_account_creator}, #{event_account_block_height}, #{event_account_tx_hash})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(EventInfo info);

}
