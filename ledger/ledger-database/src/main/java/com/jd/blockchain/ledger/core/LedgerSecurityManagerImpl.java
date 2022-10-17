package com.jd.blockchain.ledger.core;

import com.jd.blockchain.ca.CertificateRole;
import com.jd.blockchain.ca.CertificateUtils;
import com.jd.blockchain.ledger.*;
import utils.Bytes;

import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账本安全管理器；
 *
 * @author huanghaiquan
 */
public class LedgerSecurityManagerImpl implements LedgerSecurityManager {

	private LedgerAdminSettings adminSettings;
	private ParticipantCollection participantsQuery;
	private UserAccountSet userAccountsQuery;

	// 用户的权限配置
	private Map<Bytes, UserRolesPrivileges> userPrivilegesCache = new ConcurrentHashMap<>();

	public LedgerSecurityManagerImpl(
			LedgerAdminSettings adminSettings,
			ParticipantCollection participantsQuery,
			UserAccountSet userAccountsQuery) {
		this.adminSettings = adminSettings;
		this.participantsQuery = participantsQuery;
		this.userAccountsQuery = userAccountsQuery;
	}

	@Override
	public SecurityPolicy getSecurityPolicy(Set<Bytes> endpoints, Set<Bytes> nodes) {
		Map<Bytes, UserRolesPrivileges> endpointPrivilegeMap = new HashMap<>();
		Map<Bytes, UserRolesPrivileges> nodePrivilegeMap = new HashMap<>();

		for (Bytes userAddress : endpoints) {
			UserRolesPrivileges userPrivileges = getUserRolesPrivilegs(userAddress);
			endpointPrivilegeMap.put(userAddress, userPrivileges);
		}

		for (Bytes userAddress : nodes) {
			UserRolesPrivileges userPrivileges = getUserRolesPrivilegs(userAddress);
			nodePrivilegeMap.put(userAddress, userPrivileges);
		}

		return new UserRolesSecurityPolicy(endpointPrivilegeMap, nodePrivilegeMap);
	}

	@Override
	public UserRolesPrivileges getUserRolesPrivilegs(Bytes userAddress) {

		UserAccount account = userAccountsQuery.getAccount(userAddress);

		if (account == null) {
			throw new LedgerSecurityException("This user account does not exist!");
		}

		UserRolesPrivileges userPrivileges = userPrivilegesCache.get(userAddress);
		if (userPrivileges != null) {
			return userPrivileges;
		}

		List<RolePrivileges> privilegesList = new ArrayList<>();

		// 加载用户的角色列表；
		UserRoles userRoles = adminSettings.getAuthorizations().getUserRoles(userAddress);

		// 计算用户的综合权限；
		if (userRoles != null) {
			String[] roles = userRoles.getRoles();
			RolePrivileges privilege = null;
			for (String role : roles) {
				// 先从缓存读取，如果没有再从原始数据源进行加载；
				privilege = adminSettings.getRolePrivileges().getRolePrivilege(role);
				if (privilege == null) {
					// 略过不存在的无效角色；
					continue;
				}
				privilegesList.add(privilege);
			}
		}
		// 如果用户未被授权任何角色，则采用默认角色的权限；
		if (privilegesList.size() == 0) {
			RolePrivileges privilege = getDefaultRolePrivilege();
			privilegesList.add(privilege);
		}

		if (userRoles == null) {
			userPrivileges = new UserRolesPrivileges(userAddress, RolesPolicy.UNION, privilegesList);
		} else {
			userPrivileges = new UserRolesPrivileges(userAddress, userRoles.getPolicy(), privilegesList);
		}
		userPrivilegesCache.put(userAddress, userPrivileges);

		return userPrivileges;
	}

	private RolePrivileges getDefaultRolePrivilege() {
		RolePrivileges privileges = adminSettings.getRolePrivileges().getRolePrivilege(DEFAULT_ROLE);
		if (privileges == null) {
			throw new LedgerSecurityException(
					"This ledger is missing the default role-privilege settings for the users who don't have a role!");
		}
		return privileges;
	}

	private class UserRolesSecurityPolicy implements SecurityPolicy {

		/** 终端用户的权限表； */
		private Map<Bytes, UserRolesPrivileges> endpointPrivilegeMap;

		/** 节点参与方的权限表； */
		private Map<Bytes, UserRolesPrivileges> nodePrivilegeMap;

		private IdentityMode identityMode;
		private X509Certificate[] ledgerCerts;

		public UserRolesSecurityPolicy(
				Map<Bytes, UserRolesPrivileges> endpointPrivilegeMap,
				Map<Bytes, UserRolesPrivileges> nodePrivilegeMap) {
			this.endpointPrivilegeMap = endpointPrivilegeMap;
			this.nodePrivilegeMap = nodePrivilegeMap;
			this.identityMode = adminSettings.getMetadata().getIdentityMode();
			if (identityMode.equals(IdentityMode.CA)) {
				this.ledgerCerts =
						CertificateUtils.parseCertificates(adminSettings.getMetadata().getLedgerCertificates());
			}
		}

		@Override
		public boolean isEndpointEnable(LedgerPermission permission, MultiIDsPolicy midPolicy) {
			if (MultiIDsPolicy.AT_LEAST_ONE == midPolicy) {
				// 至少一个；
				for (UserRolesPrivileges p : endpointPrivilegeMap.values()) {
					if (p.getLedgerPrivilegesBitset().isEnable(permission)) {
						return true;
					}
				}
				return false;
			} else if (MultiIDsPolicy.ALL == midPolicy) {
				// 全部；
				for (UserRolesPrivileges p : endpointPrivilegeMap.values()) {
					if (!p.getLedgerPrivilegesBitset().isEnable(permission)) {
						return false;
					}
				}
				return true;
			} else {
				throw new IllegalArgumentException("Unsupported MultiIdsPolicy[" + midPolicy + "]!");
			}
		}

		@Override
		public boolean isEndpointEnable(TransactionPermission permission, MultiIDsPolicy midPolicy) {
			if (MultiIDsPolicy.AT_LEAST_ONE == midPolicy) {
				// 至少一个；
				for (UserRolesPrivileges p : endpointPrivilegeMap.values()) {
					if (p.getTransactionPrivilegesBitset().isEnable(permission)) {
						return true;
					}
				}
				return false;
			} else if (MultiIDsPolicy.ALL == midPolicy) {
				// 全部；
				for (UserRolesPrivileges p : endpointPrivilegeMap.values()) {
					if (!p.getTransactionPrivilegesBitset().isEnable(permission)) {
						return false;
					}
				}
				return true;
			} else {
				throw new IllegalArgumentException("Unsupported MultiIdsPolicy[" + midPolicy + "]!");
			}
		}

		@Override
		public boolean isNodeEnable(LedgerPermission permission, MultiIDsPolicy midPolicy) {
			if (MultiIDsPolicy.AT_LEAST_ONE == midPolicy) {
				// 至少一个；
				for (UserRolesPrivileges p : nodePrivilegeMap.values()) {
					if (p.getLedgerPrivilegesBitset().isEnable(permission)) {
						return true;
					}
				}
				return false;
			} else if (MultiIDsPolicy.ALL == midPolicy) {
				// 全部；
				for (UserRolesPrivileges p : nodePrivilegeMap.values()) {
					if (!p.getLedgerPrivilegesBitset().isEnable(permission)) {
						return false;
					}
				}
				return true;
			} else {
				throw new IllegalArgumentException("Unsupported MultiIdsPolicy[" + midPolicy + "]!");
			}
		}

		@Override
		public boolean isNodeEnable(TransactionPermission permission, MultiIDsPolicy midPolicy) {
			if (MultiIDsPolicy.AT_LEAST_ONE == midPolicy) {
				// 至少一个；
				for (UserRolesPrivileges p : nodePrivilegeMap.values()) {
					if (p.getTransactionPrivilegesBitset().isEnable(permission)) {
						return true;
					}
				}
				return false;
			} else if (MultiIDsPolicy.ALL == midPolicy) {
				// 全部；
				for (UserRolesPrivileges p : nodePrivilegeMap.values()) {
					if (!p.getTransactionPrivilegesBitset().isEnable(permission)) {
						return false;
					}
				}
				return true;
			} else {
				throw new IllegalArgumentException("Unsupported MultiIdsPolicy[" + midPolicy + "]!");
			}
		}

		@Override
		public void checkEndpointPermission(LedgerPermission permission, MultiIDsPolicy midPolicy)
				throws LedgerSecurityException {
			if (!isEndpointEnable(permission, midPolicy)) {
				throw new LedgerSecurityException(
						String.format(
								"The security policy [Permission=%s, Policy=%s] for endpoints rejected the current operation!",
								permission, midPolicy));
			}
		}

		@Override
		public void checkEndpointPermission(TransactionPermission permission, MultiIDsPolicy midPolicy)
				throws LedgerSecurityException {
			if (!isEndpointEnable(permission, midPolicy)) {
				throw new LedgerSecurityException(
						String.format(
								"The security policy [Permission=%s, Policy=%s] for endpoints rejected the current operation!",
								permission, midPolicy));
			}
		}

		@Override
		public void checkNodePermission(LedgerPermission permission, MultiIDsPolicy midPolicy)
				throws LedgerSecurityException {
			if (!isNodeEnable(permission, midPolicy)) {
				throw new LedgerSecurityException(
						String.format(
								"The security policy [Permission=%s, Policy=%s] for nodes rejected the current operation!",
								permission, midPolicy));
			}
		}

		@Override
		public void checkNodePermission(TransactionPermission permission, MultiIDsPolicy midPolicy)
				throws LedgerSecurityException {
			if (!isNodeEnable(permission, midPolicy)) {
				throw new LedgerSecurityException(
						String.format(
								"The security policy [Permission=%s, Policy=%s] for nodes rejected the current operation!",
								permission, midPolicy));
			}
		}

		@Override
		public void checkEndpointState(MultiIDsPolicy midPolicy) throws LedgerSecurityException {
			if (MultiIDsPolicy.AT_LEAST_ONE == midPolicy) {
				// 至少一个；
				for (Bytes address : getEndpoints()) {
					UserAccount account = userAccountsQuery.getAccount(address);
					try {
						if (account.getState() != AccountState.NORMAL) {
							continue;
						}
						if (identityMode.equals(IdentityMode.CA)) {
							X509Certificate cert = CertificateUtils.parseCertificate(account.getCertificate());
							CertificateUtils.checkCertificateRolesAny(
									cert, CertificateRole.PEER, CertificateRole.GW, CertificateRole.USER);
							CertificateUtils.checkValidity(cert);
							X509Certificate[] issuers = CertificateUtils.findIssuers(cert, ledgerCerts);
							Arrays.stream(issuers).forEach(issuer -> CertificateUtils.checkCACertificate(issuer));
							CertificateUtils.checkValidityAny(issuers);
						}
						return;
					} catch (Exception e) {
					}
				}
				throw new LedgerSecurityException("Invalid endpoint users!");
			} else if (MultiIDsPolicy.ALL == midPolicy) {
				// 全部；
				try {
					for (Bytes address : getEndpoints()) {
						UserAccount account = userAccountsQuery.getAccount(address);
						if (account.getState() != AccountState.NORMAL) {
							throw new LedgerSecurityException("Invalid endpoint user!");
						}
						if (identityMode.equals(IdentityMode.CA)) {
							X509Certificate cert = CertificateUtils.parseCertificate(account.getCertificate());
							CertificateUtils.checkCertificateRolesAny(
									cert, CertificateRole.PEER, CertificateRole.GW, CertificateRole.USER);
							CertificateUtils.checkValidity(cert);
							X509Certificate[] issuers = CertificateUtils.findIssuers(cert, ledgerCerts);
							Arrays.stream(issuers).forEach(issuer -> CertificateUtils.checkCACertificate(issuer));
							CertificateUtils.checkValidityAny(issuers);
						}
					}
				} catch (Exception e) {
					throw new LedgerSecurityException("Invalid endpoint user!");
				}
			} else {
				throw new IllegalArgumentException("Unsupported MultiIdsPolicy[" + midPolicy + "]!");
			}
		}

		@Override
		public void checkNodeState(MultiIDsPolicy midPolicy) throws LedgerSecurityException {
			if (MultiIDsPolicy.AT_LEAST_ONE == midPolicy) {
				// 至少一个；
				for (Bytes address : getNodes()) {
					try {
						UserAccount account = userAccountsQuery.getAccount(address);
						if (account.getState() != AccountState.NORMAL) {
							continue;
						}
						if (identityMode.equals(IdentityMode.CA)) {
							X509Certificate cert = CertificateUtils.parseCertificate(account.getCertificate());
							CertificateUtils.checkCertificateRolesAny(
									cert, CertificateRole.PEER, CertificateRole.GW);
							CertificateUtils.checkValidity(cert);
							X509Certificate[] issuers = CertificateUtils.findIssuers(cert, ledgerCerts);
							Arrays.stream(issuers).forEach(issuer -> CertificateUtils.checkCACertificate(issuer));
							CertificateUtils.checkValidityAny(issuers);
						}
						return;
					} catch (Exception e) {
					}
				}
				throw new LedgerSecurityException("Invalid node signer!");
			} else if (MultiIDsPolicy.ALL == midPolicy) {
				// 全部；
				try {
					for (Bytes address : getNodes()) {
						UserAccount account = userAccountsQuery.getAccount(address);
						if (account.getState() != AccountState.NORMAL) {
							throw new LedgerSecurityException("Invalid node signer!");
						}
						if (identityMode.equals(IdentityMode.CA)) {
							X509Certificate cert = CertificateUtils.parseCertificate(account.getCertificate());
							CertificateUtils.checkValidity(cert);
							CertificateUtils.checkCertificateRolesAny(
									cert, CertificateRole.PEER, CertificateRole.GW);
							X509Certificate[] issuers = CertificateUtils.findIssuers(cert, ledgerCerts);
							Arrays.stream(issuers).forEach(issuer -> CertificateUtils.checkCACertificate(issuer));
							CertificateUtils.checkValidityAny(issuers);
						}
					}
				} catch (Exception e) {
					throw new LedgerSecurityException("Invalid node signer!");
				}
			} else {
				throw new IllegalArgumentException("Unsupported MultiIdsPolicy[" + midPolicy + "]!");
			}
		}

		@Override
		public void checkDataPermission(DataPermission permission, DataPermissionType permissionType)
				throws LedgerSecurityException {
			AccountModeBits modeBits = permission.getModeBits();

			for (Bytes address : endpointPrivilegeMap.keySet()) {

				boolean isOwner = Arrays.stream(permission.getOwners()).anyMatch(x -> x.equals(address));

				// check owner's data permission
				if (isOwner && modeBits.get(AccountModeBits.BitGroup.OWNER, permissionType.CODE)) {
					return;
				}

				if (isOwner) {
					continue;
				}

				UserRoles userRoles = adminSettings.getAuthorizations().getUserRoles(address);

				boolean isGroup =
						userRoles != null
								&& Arrays.stream(userRoles.getRoles()).anyMatch(permission.getRole()::equals);

				// isGroup: check group's user data permission
				// Others:  check others  user data permission
				boolean passed =
						modeBits.get(
								isGroup ? AccountModeBits.BitGroup.GROUP : AccountModeBits.BitGroup.OTHERS,
								permissionType.CODE);
				if (passed) {
					return;
				}
			}

			throw new LedgerSecurityException("Data permission deny!");
		}

		@Override
		public void checkDataOwners(DataPermission permission, MultiIDsPolicy midPolicy)
				throws LedgerSecurityException {
			if (null == permission || permission.getOwners().length == 0) {
				return;
			}
			if (MultiIDsPolicy.AT_LEAST_ONE == midPolicy) {
				// 至少一个；
				for (Bytes address : permission.getOwners()) {
					if (endpointPrivilegeMap.containsKey(address)) {
						return;
					}
				}
				throw new LedgerSecurityException("No endpoint signers in account permission owners!");
			} else if (MultiIDsPolicy.ALL == midPolicy) {
				// 全部；
				for (Bytes address : permission.getOwners()) {
					if (!endpointPrivilegeMap.containsKey(address)) {
						throw new LedgerSecurityException(
								"Endpoint signers do not contain all the account permission owners!");
					}
				}
				return;
			} else {
				throw new IllegalArgumentException("Unsupported MultiIdsPolicy[" + midPolicy + "]!");
			}
		}

		@Override
		public Set<Bytes> getEndpoints() {
			return endpointPrivilegeMap.keySet();
		}

		@Override
		public Set<Bytes> getNodes() {
			return nodePrivilegeMap.keySet();
		}

		@Override
		public boolean isEndpointValid(MultiIDsPolicy midPolicy) {
			if (MultiIDsPolicy.AT_LEAST_ONE == midPolicy) {
				// 至少一个；
				for (Bytes address : getEndpoints()) {
					if (userAccountsQuery.contains(address)) {
						return true;
					}
				}
				return false;
			} else if (MultiIDsPolicy.ALL == midPolicy) {
				// 全部；
				for (Bytes address : getEndpoints()) {
					if (!userAccountsQuery.contains(address)) {
						return false;
					}
				}
				return true;
			} else {
				throw new IllegalArgumentException("Unsupported MultiIdsPolicy[" + midPolicy + "]!");
			}
		}

		@Override
		public boolean isNodeValid(MultiIDsPolicy midPolicy) {
			if (MultiIDsPolicy.AT_LEAST_ONE == midPolicy) {
				// 至少一个；
				for (Bytes address : getNodes()) {
					if (participantsQuery.contains(address)) {
						return true;
					}
				}
				return false;
			} else if (MultiIDsPolicy.ALL == midPolicy) {
				// 全部；
				for (Bytes address : getNodes()) {
					if (!participantsQuery.contains(address)) {
						return false;
					}
				}
				return true;
			} else {
				throw new IllegalArgumentException("Unsupported MultiIdsPolicy[" + midPolicy + "]!");
			}
		}

		@Override
		public void checkEndpointValidity(MultiIDsPolicy midPolicy) throws LedgerSecurityException {
			if (MultiIDsPolicy.AT_LEAST_ONE == midPolicy) {
				// 至少一个；
				for (Bytes address : getEndpoints()) {
					if (userAccountsQuery.contains(address)) {
						return;
					}
				}
				throw new UserDoesNotExistException("All endpoint signers were not registered!");
			} else if (MultiIDsPolicy.ALL == midPolicy) {
				// 全部；
				for (Bytes address : getEndpoints()) {
					if (!userAccountsQuery.contains(address)) {
						throw new UserDoesNotExistException(
								"The endpoint signer[" + address + "] was not registered!");
					}
				}
				return;
			} else {
				throw new IllegalArgumentException("Unsupported MultiIdsPolicy[" + midPolicy + "]!");
			}
		}

		@Override
		public void checkNodeValidity(MultiIDsPolicy midPolicy) throws LedgerSecurityException {
			if (MultiIDsPolicy.AT_LEAST_ONE == midPolicy) {
				// 至少一个；
				for (Bytes address : getNodes()) {
					if (participantsQuery.contains(address)) {
						return;
					}
				}
				throw new ParticipantDoesNotExistException(
						"All node signers were not registered as participant!");
			} else if (MultiIDsPolicy.ALL == midPolicy) {
				// 全部；
				for (Bytes address : getNodes()) {
					if (!participantsQuery.contains(address)) {
						throw new ParticipantDoesNotExistException(
								"The node signer[" + address + "] was not registered as participant!");
					}
				}
			} else {
				throw new IllegalArgumentException("Unsupported MultiIdsPolicy[" + midPolicy + "]!");
			}
		}
	}
}
