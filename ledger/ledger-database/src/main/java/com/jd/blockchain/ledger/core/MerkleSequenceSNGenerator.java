package com.jd.blockchain.ledger.core;

import java.util.concurrent.atomic.AtomicLong;

import com.jd.blockchain.ledger.proof.MerkleSequenceTree;

import utils.Bytes;

public class MerkleSequenceSNGenerator implements SNGenerator {
	
	private AtomicLong sn;
	
	public MerkleSequenceSNGenerator(MerkleSequenceTree merkleTree) {
		this.sn = new AtomicLong(merkleTree.getMaxSn() + 1);
	}

	@Override
	public long generate(Bytes key) {
		return sn.getAndIncrement();
	}

}
