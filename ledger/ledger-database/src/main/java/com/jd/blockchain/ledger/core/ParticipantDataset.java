package com.jd.blockchain.ledger.core;

import com.jd.binaryproto.BinaryProtocol;
import com.jd.binaryproto.DataContractRegistry;
import com.jd.blockchain.crypto.HashDigest;
import com.jd.blockchain.ledger.CryptoSetting;
import com.jd.blockchain.ledger.LedgerException;
import com.jd.blockchain.ledger.MerkleProof;
import com.jd.blockchain.ledger.ParticipantNode;
import com.jd.blockchain.storage.service.ExPolicyKVStorage;
import com.jd.blockchain.storage.service.VersioningKVStorage;

import utils.Bytes;
import utils.DataEntry;
import utils.Mapper;
import utils.SkippingIterator;
import utils.Transactional;

public class ParticipantDataset implements Transactional, ParticipantCollection {

	static {
		DataContractRegistry.register(ParticipantNode.class);
	}

	private MerkleDataset<Bytes, byte[]> dataset;

	public ParticipantDataset(CryptoSetting cryptoSetting, String prefix, ExPolicyKVStorage exPolicyStorage,
			VersioningKVStorage verStorage) {
		dataset = new MerkleHashDataset(cryptoSetting, prefix, exPolicyStorage, verStorage);
	}

	public ParticipantDataset(HashDigest merkleRootHash, CryptoSetting cryptoSetting, String prefix,
			ExPolicyKVStorage exPolicyStorage, VersioningKVStorage verStorage, boolean readonly) {
		dataset = new MerkleHashDataset(merkleRootHash, cryptoSetting, Bytes.fromString(prefix), exPolicyStorage,
				verStorage, readonly);
	}

	@Override
	public HashDigest getRootHash() {
		return dataset.getRootHash();
	}

	@Override
	public MerkleProof getProof(Bytes key) {
		return dataset.getProof(key);
	}

	@Override
	public boolean isUpdated() {
		return dataset.isUpdated();
	}

	@Override
	public void commit() {
		dataset.commit();
	}

	@Override
	public void cancel() {
		dataset.cancel();
	}

	@Override
	public long getParticipantCount() {
		return dataset.getDataCount();
	}

	/**
	 * 加入新的共识参与方； <br>
	 * 如果指定的共识参与方已经存在，则引发 {@link LedgerException} 异常；
	 * 
	 * @param participant
	 */
	public void addConsensusParticipant(ParticipantNode participant) {
		Bytes key = encodeKey(participant.getAddress());
		byte[] participantBytes = BinaryProtocol.encode(participant, ParticipantNode.class);
		long nv = dataset.setValue(key, participantBytes, -1);
		if (nv < 0) {
			throw new LedgerException("Participant already exist! --[id=" + key + "]");
		}
	}

	/**
	 * 更新共识参与方的状态信息； <br>
	 *
	 * @param participant
	 */
	public void updateConsensusParticipant(ParticipantNode participant) {
		Bytes key = encodeKey(participant.getAddress());
		byte[] participantBytes = BinaryProtocol.encode(participant, ParticipantNode.class);
		long version = dataset.getVersion(key);
		if (version < 0) {
			throw new LedgerException("Participant not exist, update failed!");
		}

		long nv = dataset.setValue(key, participantBytes, version);
		if (nv < 0) {
			throw new LedgerException("Participant update failed!");
		}
	}

	private Bytes encodeKey(Bytes address) {
		return address;
	}

	@Override
	public boolean contains(Bytes address) {
		Bytes key = encodeKey(address);
		long latestVersion = dataset.getVersion(key);
		return latestVersion > -1;
	}

	/**
	 * 返回指定地址的参与方凭证；
	 * 
	 * <br>
	 * 如果不存在，则返回 null；
	 * 
	 * @param address
	 * @return
	 */
	@Override
	public ParticipantNode getParticipant(Bytes address) {
		Bytes key = encodeKey(address);
		byte[] bytes = dataset.getValue(key);
		if (bytes == null) {
			return null;
		}
		return BinaryProtocol.decode(bytes);
	}

	@Deprecated
	@Override
	public ParticipantNode[] getParticipants() {
		SkippingIterator<ParticipantNode> nodesIterator = getAllParticipants();
		ParticipantNode[] nodes = new ParticipantNode[(int) nodesIterator.getCount()];
		nodesIterator.next(nodes);

		return nodes;
	}

	@Override
	public SkippingIterator<ParticipantNode> getAllParticipants() {
		SkippingIterator<DataEntry<Bytes, byte[]>> dataIterator = dataset.iterator();
		return dataIterator.iterateAs(new Mapper<DataEntry<Bytes, byte[]>, ParticipantNode>() {

			@Override
			public ParticipantNode from(DataEntry<Bytes, byte[]> source) {
				return source == null ? null : BinaryProtocol.decode(source.getValue());
			}
		});
	}

}
