package com.jd.blockchain.ledger.core.handles;

import com.jd.blockchain.ledger.LedgerPermission;
import com.jd.blockchain.ledger.RolePrivileges;
import com.jd.blockchain.ledger.RolesConfigureOperation;
import com.jd.blockchain.ledger.RolesConfigureOperation.RolePrivilegeEntry;
import com.jd.blockchain.ledger.core.EventManager;
import com.jd.blockchain.ledger.core.LedgerQuery;
import com.jd.blockchain.ledger.core.LedgerTransactionContext;
import com.jd.blockchain.ledger.core.MultiIDsPolicy;
import com.jd.blockchain.ledger.core.OperationHandleContext;
import com.jd.blockchain.ledger.core.RolePrivilegeDataset;
import com.jd.blockchain.ledger.core.SecurityContext;
import com.jd.blockchain.ledger.core.SecurityPolicy;
import com.jd.blockchain.ledger.core.TransactionRequestExtension;

public class RolesConfigureOperationHandle extends AbstractLedgerOperationHandle<RolesConfigureOperation> {

	public RolesConfigureOperationHandle() {
		super(RolesConfigureOperation.class);
	}

	@Override
	protected void doProcess(RolesConfigureOperation operation, LedgerTransactionContext transactionContext,
							 TransactionRequestExtension request, LedgerQuery ledger, OperationHandleContext handleContext, EventManager manager) {
		// 权限校验；
		SecurityPolicy securityPolicy = SecurityContext.getContextUsersPolicy();
		securityPolicy.checkEndpointPermission(LedgerPermission.CONFIGURE_ROLES, MultiIDsPolicy.AT_LEAST_ONE);

		// 操作账本；
		RolePrivilegeEntry[] rpcfgs = operation.getRoles();
		RolePrivilegeDataset rpSettings = transactionContext.getDataset().getAdminDataset().getRolePrivileges();
		if (rpcfgs != null) {
			for (RolePrivilegeEntry rpcfg : rpcfgs) {
				RolePrivileges rp = rpSettings.getRolePrivilege(rpcfg.getRoleName());
				if (rp == null) {
					rpSettings.addRolePrivilege(rpcfg.getRoleName(), rpcfg.getEnableLedgerPermissions(),
							rpcfg.getEnableTransactionPermissions());
				} else {
					rp.enable(rpcfg.getEnableLedgerPermissions());
					rp.enable(rpcfg.getEnableTransactionPermissions());

					rp.disable(rpcfg.getDisableLedgerPermissions());
					rp.disable(rpcfg.getDisableTransactionPermissions());
					rpSettings.updateRolePrivilege(rp);
				}
			}
		}
	}

}
