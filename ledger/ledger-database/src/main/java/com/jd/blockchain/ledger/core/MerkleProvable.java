package com.jd.blockchain.ledger.core;

import com.jd.blockchain.ledger.MerkleProof;
import com.jd.blockchain.ledger.MerkleSnapshot;

public interface MerkleProvable<K> extends MerkleSnapshot {

	/**
	 * Get the merkle proof of the latest version of specified key; <br>
	 * 
	 * The proof doesn't represent the latest changes until do
	 * committing({@link #commit()}).
	 * 
	 * @param key
	 * @return Return the {@link MerkleProof} instance, or null if the key doesn't
	 *         exist.
	 */
	MerkleProof getProof(K key);

}