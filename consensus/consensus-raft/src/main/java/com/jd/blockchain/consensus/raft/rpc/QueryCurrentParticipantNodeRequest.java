package com.jd.blockchain.consensus.raft.rpc;

import java.io.Serializable;

public class QueryCurrentParticipantNodeRequest implements Serializable {
    private static final long serialVersionUID = 5619373098318874151L;

    private String ledgerHash;

    public String getLedgerHash() {
        return ledgerHash;
    }

    public void setLedgerHash(String ledgerHash) {
        this.ledgerHash = ledgerHash;
    }
}
