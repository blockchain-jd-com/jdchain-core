package test.com.jd.blockchain.ledger.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import com.jd.blockchain.ledger.*;
import com.jd.blockchain.ledger.cache.AdminLRUCache;
import com.jd.blockchain.ledger.core.*;
import org.junit.Test;
import org.mockito.Mockito;

import com.jd.blockchain.crypto.Crypto;
import com.jd.blockchain.crypto.CryptoAlgorithm;
import com.jd.blockchain.crypto.CryptoProvider;
import com.jd.blockchain.crypto.service.classic.ClassicCryptoService;
import com.jd.blockchain.crypto.service.sm.SMCryptoService;
import com.jd.blockchain.storage.service.utils.MemoryKVStorage;

import utils.Bytes;

public class LedgerSecurityManagerTest {

	private static final String[] SUPPORTED_PROVIDER_NAMES = { ClassicCryptoService.class.getName(),
			SMCryptoService.class.getName() };

	private static final CryptoAlgorithm HASH_ALGORITHM = Crypto.getAlgorithm("SHA256");

	private static final CryptoProvider[] SUPPORTED_PROVIDERS = new CryptoProvider[SUPPORTED_PROVIDER_NAMES.length];

	private static final CryptoSetting CRYPTO_SETTINGS;

	static {
		for (int i = 0; i < SUPPORTED_PROVIDER_NAMES.length; i++) {
			SUPPORTED_PROVIDERS[i] = Crypto.getProvider(SUPPORTED_PROVIDER_NAMES[i]);
		}

		CryptoConfig cryptoConfig = new CryptoConfig();
		cryptoConfig.setAutoVerifyHash(true);
		cryptoConfig.setSupportedProviders(SUPPORTED_PROVIDERS);
		cryptoConfig.setHashAlgorithm(HASH_ALGORITHM);

		CRYPTO_SETTINGS = cryptoConfig;
	}

	private RolePrivilegeDataset createRolePrivilegeDataset(MemoryKVStorage testStorage) {
		String prefix = "role-privilege/";
		RolePrivilegeDataset rolePrivilegeDataset = new RolePrivilegeDataset(CRYPTO_SETTINGS, prefix, testStorage,
				testStorage, LedgerDataStructure.MERKLE_TREE, new AdminLRUCache());

		return rolePrivilegeDataset;
	}

	private UserRoleDatasetEditor createUserRoleDataset(MemoryKVStorage testStorage) {
		String prefix = "user-roles/";
		UserRoleDatasetEditor userRolesDataset = new UserRoleDatasetEditor(CRYPTO_SETTINGS, prefix, testStorage, testStorage, LedgerDataStructure.MERKLE_TREE, new AdminLRUCache());

		return userRolesDataset;
	}

	@Test
	public void testGetSecurityPolicy() {
		MemoryKVStorage testStorage = new MemoryKVStorage();

		// 定义不同角色用户的 keypair；
		final BlockchainKeypair kpManager = BlockchainKeyGenerator.getInstance().generate();
		final BlockchainKeypair kpEmployee = BlockchainKeyGenerator.getInstance().generate();
		final BlockchainKeypair kpDevoice = BlockchainKeyGenerator.getInstance().generate();
		final BlockchainKeypair kpPlatform = BlockchainKeyGenerator.getInstance().generate();

		// 定义角色和权限；
		final String ROLE_ADMIN = "ID_ADMIN";
		final String ROLE_OPERATOR = "OPERATOR";
		final String ROLE_DATA_COLLECTOR = "DATA_COLLECTOR";
		final String ROLE_PLATFORM = "PLATFORM";

		// 定义管理员角色的权限：【账本权限只允许：注册用户、注册数据账户】【交易权限只允许：调用账本直接操作】
		final Privileges PRIVILEGES_ADMIN = Privileges.configure()
				.enable(LedgerPermission.REGISTER_USER, LedgerPermission.REGISTER_DATA_ACCOUNT)
				.enable(TransactionPermission.DIRECT_OPERATION);

		// 定义操作员角色的权限：【账本权限只允许：写入数据账户】【交易权限只允许：调用合约】
		final Privileges PRIVILEGES_OPERATOR = Privileges.configure().enable(LedgerPermission.WRITE_DATA_ACCOUNT)
				.enable(TransactionPermission.CONTRACT_OPERATION);

		// 定义数据收集器角色的权限：【账本权限只允许：写入数据账户】【交易权限只允许：调用账本直接操作】
		final Privileges PRIVILEGES_DATA_COLLECTOR = Privileges.configure().enable(LedgerPermission.WRITE_DATA_ACCOUNT)
				.enable(TransactionPermission.DIRECT_OPERATION);

		// 定义平台角色的权限：【账本权限只允许：签署合约】 (只允许作为节点签署交易，不允许作为终端发起交易指令)
		final Privileges PRIVILEGES_PLATFORM = Privileges.configure().enable(LedgerPermission.APPROVE_TX);

		RolePrivilegeDataset rolePrivilegeDataset = createRolePrivilegeDataset(testStorage);
		long v = rolePrivilegeDataset.addRolePrivilege(ROLE_ADMIN, PRIVILEGES_ADMIN);
		assertTrue(v > -1);
		v = rolePrivilegeDataset.addRolePrivilege(ROLE_OPERATOR, PRIVILEGES_OPERATOR);
		assertTrue(v > -1);
		v = rolePrivilegeDataset.addRolePrivilege(ROLE_DATA_COLLECTOR, PRIVILEGES_DATA_COLLECTOR);
		assertTrue(v > -1);
		v = rolePrivilegeDataset.addRolePrivilege(ROLE_PLATFORM, PRIVILEGES_PLATFORM);
		assertTrue(v > -1);
		rolePrivilegeDataset.commit();

		// 为用户分配角色；
		String[] managerRoles = new String[] { ROLE_ADMIN, ROLE_OPERATOR };
		String[] employeeRoles = new String[] { ROLE_OPERATOR };
		String[] devoiceRoles = new String[] { ROLE_DATA_COLLECTOR };
		String[] platformRoles = new String[] { ROLE_PLATFORM };
		UserRoleDatasetEditor userRolesDataset = createUserRoleDataset(testStorage);
		userRolesDataset.addUserRoles(kpManager.getAddress(), RolesPolicy.UNION, managerRoles);
		userRolesDataset.addUserRoles(kpEmployee.getAddress(), RolesPolicy.UNION, employeeRoles);
		userRolesDataset.addUserRoles(kpDevoice.getAddress(), RolesPolicy.UNION, devoiceRoles);
		userRolesDataset.addUserRoles(kpPlatform.getAddress(), RolesPolicy.UNION, platformRoles);
		userRolesDataset.commit();

		ParticipantCollection partisQuery = Mockito.mock(ParticipantCollection.class);
		UserAccountSet usersQuery = Mockito.mock(UserAccountSet.class);
		Mockito.doReturn(new UserAccount(null)).when(usersQuery).getAccount(kpManager.getAddress());
		Mockito.doReturn(new UserAccount(null)).when(usersQuery).getAccount(kpEmployee.getAddress());
		Mockito.doReturn(new UserAccount(null)).when(usersQuery).getAccount(kpDevoice.getAddress());
		Mockito.doReturn(new UserAccount(null)).when(usersQuery).getAccount(kpPlatform.getAddress());
		LedgerAdminDataSetEditor.LedgerMetadataInfo  metadataInfo = new LedgerAdminDataSetEditor.LedgerMetadataInfo();
		metadataInfo.setIdentityMode(IdentityMode.KEYPAIR);

		// 创建安全管理器；
		LedgerSecurityManager securityManager = new LedgerSecurityManagerImpl(new LedgerAdminSettingsHolder(rolePrivilegeDataset, userRolesDataset, metadataInfo),
				partisQuery, usersQuery);

		// 定义终端用户列表；终端用户一起共同具有 ADMIN、OPERATOR 角色；
		final Map<Bytes, BlockchainKeypair> endpoints = new HashMap<>();
		endpoints.put(kpManager.getAddress(), kpManager);
		endpoints.put(kpEmployee.getAddress(), kpEmployee);

		// 定义节点参与方列表；
		final Map<Bytes, BlockchainKeypair> nodes = new HashMap<>();
		nodes.put(kpPlatform.getAddress(), kpPlatform);

		// 创建一项与指定的终端用户和节点参与方相关的安全策略；
		SecurityPolicy policy = securityManager.getSecurityPolicy(endpoints.keySet(), nodes.keySet());

		// 校验安全策略的正确性；
		LedgerPermission[] ledgerPermissions = LedgerPermission.values();
		for (LedgerPermission p : ledgerPermissions) {
			// 终端节点有 ADMIN 和 OPERATOR 两种角色的合并权限；
			if (p == LedgerPermission.REGISTER_USER || p == LedgerPermission.REGISTER_DATA_ACCOUNT
					|| p == LedgerPermission.WRITE_DATA_ACCOUNT) {
				assertTrue(policy.isEndpointEnable(p, MultiIDsPolicy.AT_LEAST_ONE));
			} else {
				assertFalse(policy.isEndpointEnable(p, MultiIDsPolicy.AT_LEAST_ONE));
			}

			if (p == LedgerPermission.APPROVE_TX) {
				// 共识参与方只有 PLATFORM 角色的权限：核准交易；
				assertTrue(policy.isNodeEnable(p, MultiIDsPolicy.AT_LEAST_ONE));
			} else {
				assertFalse(policy.isNodeEnable(p, MultiIDsPolicy.AT_LEAST_ONE));
			}
		}

		TransactionPermission[] transactionPermissions = TransactionPermission.values();
		for (TransactionPermission p : transactionPermissions) {
			// 终端节点有 ADMIN 和 OPERATOR 两种角色的合并权限；
			if (p == TransactionPermission.DIRECT_OPERATION || p == TransactionPermission.CONTRACT_OPERATION) {
				assertTrue(policy.isEndpointEnable(p, MultiIDsPolicy.AT_LEAST_ONE));
			} else {
				assertFalse(policy.isEndpointEnable(p, MultiIDsPolicy.AT_LEAST_ONE));
			}

			assertFalse(policy.isNodeEnable(p, MultiIDsPolicy.AT_LEAST_ONE));
		}
	}

}
