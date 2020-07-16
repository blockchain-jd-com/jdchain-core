package com.jd.blockchain.ledger.core;

import java.util.Collection;

import com.jd.blockchain.ledger.LedgerPermission;
import com.jd.blockchain.ledger.LedgerPrivilege;
import com.jd.blockchain.ledger.LedgerPrivilegeBitset;
import com.jd.blockchain.ledger.PrivilegeBitset;
import com.jd.blockchain.ledger.RolePrivileges;
import com.jd.blockchain.ledger.RolesPolicy;
import com.jd.blockchain.ledger.TransactionPermission;
import com.jd.blockchain.ledger.TransactionPrivilege;
import com.jd.blockchain.ledger.UserPrivilegeSet;
import com.jd.blockchain.utils.Bytes;

/**
 * {@link UserRolesPrivileges} 表示多角色用户的综合权限；
 *
 * @author huanghaiquan
 *
 */
public class UserRolesPrivileges implements UserPrivilegeSet {

	private Bytes userAddress;

	private LedgerPrivilegeBitset ledgerPrivileges;

	private TransactionPrivilege transactionPrivileges;

	public UserRolesPrivileges(Bytes userAddress, RolesPolicy policy, Collection<RolePrivileges> privilegesList) {
		this.userAddress = userAddress;
		LedgerPrivilegeBitset[] ledgerPrivileges = privilegesList.stream().map(p -> p.getLedgerPrivilege())
				.toArray(LedgerPrivilege[]::new);
		TransactionPrivilege[] transactionPrivileges = privilegesList.stream().map(p -> p.getTransactionPrivilege())
				.toArray(TransactionPrivilege[]::new);

		this.ledgerPrivileges = ledgerPrivileges[0].clone();
		this.transactionPrivileges = transactionPrivileges[0].clone();

		if (policy == RolesPolicy.UNION) {
			this.ledgerPrivileges.union(ledgerPrivileges, 1, ledgerPrivileges.length - 1);
			this.transactionPrivileges.union(transactionPrivileges, 1, transactionPrivileges.length - 1);

		} else if (policy == RolesPolicy.INTERSECT) {
			this.ledgerPrivileges.intersect(ledgerPrivileges, 1, ledgerPrivileges.length - 1);
			this.transactionPrivileges.intersect(transactionPrivileges, 1, transactionPrivileges.length - 1);
		} else {
			throw new IllegalStateException("Unsupported roles policy[" + policy.toString() + "]!");
		}

	}

	public Bytes getUserAddress() {
		return userAddress;
	}

	public PrivilegeBitset<LedgerPermission> getLedgerPrivileges() {
		return ledgerPrivileges;
	}

	public PrivilegeBitset<TransactionPermission> getTransactionPrivileges() {
		return transactionPrivileges;
	}

}
