package com.jd.blockchain.peer.mysql.mapper;

import com.jd.blockchain.peer.mysql.entity.ContractInfo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Update;

/**
 * @Author: zhangshuang
 * @Date: 2022/5/9 2:55 PM
 * Version 1.0
 */
@Mapper
public interface ContractInfoMapper {

    @Insert("INSERT INTO jdchain_contracts (`ledger`, `contract_address`, `contract_pubkey`, `contract_roles`, `contract_priviledges`, `contract_version`, " +
            "`contract_lang`, `contract_status`, `contract_creator`, `contract_content`, `contract_block_height`, `contract_tx_hash`) " +
            "VALUES (#{ledger}, #{contract_address}, #{contract_pubkey}, #{contract_roles}, #{contract_priviledges}, " +
            "#{contract_version}, #{contract_lang}, #{contract_status}, #{contract_creator}, #{contract_content}, #{contract_block_height}, #{contract_tx_hash})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(ContractInfo info);

    @Update("UPDATE jdchain_contracts SET `contract_status` = #{contract_status} where `ledger` = #{ledger} and `contract_address` = #{contract_address}")
    void updateStatus(String ledger, String contract_address, String contract_status);

//    ContractInfo getContractInfoById(int id);

//    ContractInfo getContractInfoByHeight(long height);
//
//    ContractInfo getContractInfoByHash(String hash);

//    List<ContractInfo> getContractInfosByLimit(int limit);
//
//    List<ContractInfo> getAllContractInfos();
}