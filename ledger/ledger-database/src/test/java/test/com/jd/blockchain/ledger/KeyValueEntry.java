package test.com.jd.blockchain.ledger;

import com.jd.binaryproto.DataContract;
import com.jd.binaryproto.DataField;
import com.jd.binaryproto.PrimitiveType;

@DataContract(code = 0x4010)
public interface KeyValueEntry {

	@DataField(order = 1, primitiveType = PrimitiveType.TEXT)
	String getKey();

	@DataField(order = 2, primitiveType = PrimitiveType.TEXT)
	String getValue();

	@DataField(order = 3, primitiveType = PrimitiveType.INT64)
	long getVersion();

}
