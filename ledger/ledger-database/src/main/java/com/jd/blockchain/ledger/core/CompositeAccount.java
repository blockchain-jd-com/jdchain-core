package com.jd.blockchain.ledger.core;

import com.jd.blockchain.ledger.AccountSnapshot;
import com.jd.blockchain.ledger.TypedValue;

import utils.Dataset;

public interface CompositeAccount extends Account, AccountSnapshot, HashProvable{

	Dataset<String, TypedValue> getHeaders();
	
}
