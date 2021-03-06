package com.jd.blockchain.tools.initializer.web;

import java.io.IOException;
import java.io.OutputStream;

import com.jd.binaryproto.BinaryProtocol;
import com.jd.blockchain.ledger.core.LedgerInitDecision;
import com.jd.httpservice.RequestBodyConverter;

public class DecisionRequestBodyConverter implements RequestBodyConverter {

	@Override
	public void write(Object param, OutputStream out) throws IOException {
		if (param instanceof LedgerInitDecision) {
			BinaryProtocol.encode(param, LedgerInitDecision.class, out);
			return;
		}
	}

}