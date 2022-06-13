package com.jd.blockchain.tools.initializer;

import com.jd.blockchain.ca.CertificateUtils;
import com.jd.blockchain.crypto.*;
import com.jd.blockchain.ledger.IdentityMode;
import com.jd.blockchain.ledger.LedgerInitProperties;
import com.jd.blockchain.ledger.LedgerInitProperties.ParticipantProperties;
import com.jd.blockchain.ledger.core.LedgerManager;
import com.jd.blockchain.tools.initializer.LedgerBindingConfig.BindingConfig;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.NumberUtils;
import utils.ArgumentSet;
import utils.ArgumentSet.ArgEntry;
import utils.ArgumentSet.Setting;
import utils.ConsoleUtils;
import utils.StringUtils;
import utils.codec.Base58Utils;
import utils.io.FileUtils;
import utils.net.NetworkAddress;

import java.io.File;
import java.security.cert.X509Certificate;
import java.util.UUID;

/**
 * 账本初始化器；
 * 
 * @author huanghaiquan
 *
 */
@SpringBootApplication
@EnableConfigurationProperties
public class LedgerInitCommand {

	private static final String LEDGER_BINDING_FILE_NAME = "ledger-binding.conf";

	// 当前参与方的本地配置文件的路径(local.conf)；
	private static final String LOCAL_ARG = "-l";

	// 账本的初始化配置文件的路径(ledger.init)；
	private static final String INI_ARG = "-i";

	private static final String MONITOR_OPT = "-monitor";

	private static final Prompter DEFAULT_PROMPTER = new ConsolePrompter();

	private static final Prompter LOG_PROMPTER = new LogPrompter();

	/**
	 * 入口；
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		Prompter prompter = DEFAULT_PROMPTER;

		Setting argSetting = ArgumentSet.setting().prefix(LOCAL_ARG, INI_ARG).option(MONITOR_OPT);
		ArgumentSet argSet = ArgumentSet.resolve(args, argSetting);

		try {
			if (argSet.hasOption(MONITOR_OPT)) {
				prompter = LOG_PROMPTER;
			}

			ArgEntry localArg = argSet.getArg(LOCAL_ARG);
			if (localArg == null) {
				prompter.info("Miss local config file which can be specified with arg [%s]!!!", LOCAL_ARG);

			}
			LocalConfig localConf = LocalConfig.resolve(localArg.getValue());

			ArgEntry iniArg = argSet.getArg(INI_ARG);
			if (iniArg == null) {
				prompter.info("Miss ledger initializing config file which can be specified with arg [%s]!!!", INI_ARG);
				return;
			}

			// load ledger init setting;
			LedgerInitProperties ledgerInitProperties = LedgerInitProperties.resolve(iniArg.getValue());
			// 加载当前节点的私钥；
			// 根据 identity-mode 验证 local.conf 参数的正确性
			String base58Pwd = localConf.getLocal().getPassword();
			PubKey localNodePubKey;
			PrivKey privKey;
			if(ledgerInitProperties.getIdentityMode() == IdentityMode.CA) {
				X509Certificate certificate = CertificateUtils.parseCertificate(FileUtils.readText(localConf.getLocal().getCaPath()));
				localNodePubKey = CertificateUtils.resolvePubKey(certificate);
				if(StringUtils.isEmpty(base58Pwd)) {
					privKey = CertificateUtils.parsePrivKey(localNodePubKey.getAlgorithm(), FileUtils.readText(localConf.getLocal().getPrivKeyPath()));
				} else {
					privKey = CertificateUtils.parsePrivKey(localNodePubKey.getAlgorithm(), FileUtils.readText(localConf.getLocal().getPrivKeyPath()), base58Pwd);
				}
				if (!StringUtils.isEmpty(base58Pwd)) {
					base58Pwd = Base58Utils.encode(base58Pwd.getBytes());
				}
			} else {
				if (StringUtils.isEmpty(base58Pwd)) {
					base58Pwd = KeyGenUtils.readPasswordString();
				}
				localNodePubKey = KeyGenUtils.decodePubKey(localConf.getLocal().getPubKeyString());
				privKey = KeyGenUtils.decodePrivKey(localConf.getLocal().getPrivKeyString(), base58Pwd);
			}
			// 地址根据公钥生成
			String localNodeAddress = AddressEncoding.generateAddress(localNodePubKey).toBase58();

			// 加载全部公钥;
			int currId = -1;
			for (int i = 0; i < ledgerInitProperties.getConsensusParticipantCount(); i++) {
				ParticipantProperties partiConf = ledgerInitProperties.getConsensusParticipant(i);
				if (localNodeAddress.equals(partiConf.getAddress().toBase58())) {
					currId = i;
				}
			}
			if (currId == -1) {
				throw new IllegalStateException("The current node specified in local.conf is not found in ledger.init!");
			}

			// Output ledger binding config of peer;
			if (!FileUtils.existDirectory(localConf.getBindingOutDir())) {
				FileUtils.makeDirectory(localConf.getBindingOutDir());
			}
			File ledgerBindingFile = new File(localConf.getBindingOutDir(), LEDGER_BINDING_FILE_NAME);
			LedgerBindingConfig conf;
			if (ledgerBindingFile.exists()) {
				conf = LedgerBindingConfig.resolve(ledgerBindingFile);
			} else {
				conf = new LedgerBindingConfig();
			}

			// 启动初始化；
			LedgerInitCommand initCommand = new LedgerInitCommand();
			HashDigest newLedgerHash = initCommand.startInit(currId, privKey, base58Pwd, ledgerInitProperties,
					localConf, prompter, conf);

			if (newLedgerHash != null) {
				// success;
				// so save ledger binding config to file system;
				conf.store(ledgerBindingFile);
				prompter.info("\r\n------ Update Ledger binding configuration success! ------[%s]",
						ledgerBindingFile.getAbsolutePath());
			}

		} catch (Exception e) {
			e.printStackTrace();

			prompter.error("\r\n Ledger init process has been broken by error!");
		}
		prompter.confirm(InitializingStep.LEDGER_INIT_COMPLETED.toString(), "\r\n\r\n Press any key to quit. :>");

		if (argSet.hasOption(MONITOR_OPT)) {
			// 管理工具启动的方式下，需自动退出
			System.exit(0);
		}
	}

	private LedgerManager ledgerManager;

	public LedgerManager getLedgerManager() {
		return ledgerManager;
	}

	public LedgerInitCommand() {
	}

	public HashDigest startInit(int currId, PrivKey privKey, String base58Pwd,
			LedgerInitProperties ledgerInitProperties, DBConnectionConfig dbConnConfig, Prompter prompter,
			LedgerBindingConfig conf, Object... extBeans) {
		if(StringUtils.isEmpty(base58Pwd)) {
			base58Pwd = Base58Utils.encode(UUID.randomUUID().toString().getBytes());
			prompter.info("Your base58 encode private key password : [%s]!!!", base58Pwd);
		}
		if (currId < 0 || currId >= ledgerInitProperties.getConsensusParticipantCount()) {
			prompter.info(
					"Your participant id is illegal which is less than 1 or great than the total participants count[%s]!!!",
					ledgerInitProperties.getConsensusParticipantCount());
			return null;
		}

		// generate binding config;
		BindingConfig bindingConf = new BindingConfig();

		// 设置账本名称
		bindingConf.setLedgerName(ledgerInitProperties.getLedgerName());
		bindingConf.setDataStructure(ledgerInitProperties.getLedgerDataStructure());

		bindingConf.getParticipant()
				.setAddress(ledgerInitProperties.getConsensusParticipant(currId).getAddress().toBase58());
		// 设置参与方名称
		bindingConf.getParticipant().setName(ledgerInitProperties.getConsensusParticipant(currId).getName());

		String encodedPrivKey = KeyGenUtils.encodePrivKey(privKey, base58Pwd);
		bindingConf.getParticipant().setPk(encodedPrivKey);
		bindingConf.getParticipant().setPassword(base58Pwd);

		bindingConf.getDbConnection().setConnectionUri(dbConnConfig.getUri());
		bindingConf.getDbConnection().setPassword(dbConnConfig.getPassword());

		// confirm continue；
		prompter.info("\r\n\r\n This is participant [%s], the ledger initialization is ready to start!\r\n", currId);
//		ConsoleUtils.confirm("Press any key to continue... ");
//		prompter.confirm("Press any key to continue... ");

		// start the web controller of Ledger Initializer;
		NetworkAddress serverAddress = ledgerInitProperties.getConsensusParticipant(currId).getInitializerAddress();

		//for dockers binding the 0.0.0.0;
		//if ledger-init.sh set up the -DhostPort=xxx -DhostIp=xxx, then get it;
		String preHostPort = System.getProperty("hostPort");
		if(!StringUtils.isEmpty(preHostPort)){
			int port = NumberUtils.parseNumber(preHostPort, Integer.class);
			serverAddress.setPort(port);
			ConsoleUtils.info("###ledger-init.sh###,set up the -DhostPort="+port);
		}
		String preHostIp = System.getProperty("hostIp");
		if(!StringUtils.isEmpty(preHostIp)){
			serverAddress.setHost(preHostIp);
			ConsoleUtils.info("###ledger-init.sh###,set up the -DhostIp="+preHostIp);
		}

		String argServerAddress = String.format("--server.address=%s", serverAddress.getHost());
		String argServerPort = String.format("--server.port=%s", serverAddress.getPort());
		String[] innerArgs = { argServerAddress, argServerPort };

		SpringApplication app = new SpringApplication(LedgerInitCommand.class);
		if (extBeans != null && extBeans.length > 0) {
			app.addInitializers((ApplicationContextInitializer<ConfigurableApplicationContext>) applicationContext -> {
				ConfigurableListableBeanFactory beanFactory = applicationContext.getBeanFactory();
				for (Object bean : extBeans) {
					beanFactory.registerSingleton(bean.toString(), bean);
				}
			});
		}
		ConfigurableApplicationContext ctx = app.run(innerArgs);
		this.ledgerManager = ctx.getBean(LedgerManager.class);

		prompter.info("\r\n------ Web controller of Ledger Initializer[%s:%s] was started. ------\r\n",
				serverAddress.getHost(), serverAddress.getPort());

		try {
			LedgerInitProcess initProc = ctx.getBean(LedgerInitProcess.class);
			HashDigest ledgerHash = initProc.initialize(currId, privKey, ledgerInitProperties,
					bindingConf.getDbConnection(), prompter);

			if (ledgerHash == null) {
				// ledger init fail;
				prompter.error("\r\n------ Ledger initialize fail! ------\r\n");
				return null;
			} else {
				prompter.info("\r\n------ Ledger initialize success! ------");
				prompter.info("New Ledger Hash is :[%s]", ledgerHash.toBase58());

				if (conf == null) {
					conf = new LedgerBindingConfig();
				}
				conf.addLedgerBinding(ledgerHash, bindingConf);

				return ledgerHash;

			}
		} finally {
			ctx.close();
			prompter.info("\r\n------ Web listener[%s:%s] was closed. ------\r\n", serverAddress.getHost(),
					serverAddress.getPort());
		}
	}

	public HashDigest startInit(int currId, PrivKey privKey, String base58Pwd,
			LedgerInitProperties ledgerInitProperties, LocalConfig localConfig, Prompter prompter,
			LedgerBindingConfig conf, Object... extBeans) {
		if (currId < 0 || currId >= ledgerInitProperties.getConsensusParticipantCount()) {
			prompter.info(
					"Your participant id is illegal which is less than 1 or great than the total participants count[%s]!!!",
					ledgerInitProperties.getConsensusParticipantCount());
			return null;
		}

		// generate binding config;
		BindingConfig bindingConf = new BindingConfig();

		// 设置账本名称
		bindingConf.setLedgerName(ledgerInitProperties.getLedgerName());

		//设置账本存储数据库的锚定类型
		bindingConf.setDataStructure(ledgerInitProperties.getLedgerDataStructure());

		// 设置额外参数
		bindingConf.setExtraProperties(localConfig.getExtraProperties());

		bindingConf.getParticipant()
				.setAddress(ledgerInitProperties.getConsensusParticipant(currId).getAddress().toBase58());
		// 设置参与方名称
		bindingConf.getParticipant().setName(ledgerInitProperties.getConsensusParticipant(currId).getName());

		// 证书模式下私钥处理
		if(ledgerInitProperties.getIdentityMode() == IdentityMode.CA) {
			bindingConf.getParticipant().setPkPath(localConfig.getLocal().getPrivKeyPath());
		} else {
			String encodedPrivKey = KeyGenUtils.encodePrivKey(privKey, base58Pwd);
			bindingConf.getParticipant().setPk(encodedPrivKey);
		}
		if(!StringUtils.isEmpty(base58Pwd)) {
			bindingConf.getParticipant().setPassword(base58Pwd);
		}

		// 共识服务TLS相关参数
		bindingConf.getParticipant().setSslKeyStore(localConfig.getLocal().getSslKeyStore());
		bindingConf.getParticipant().setSslKeyStorePassword(localConfig.getLocal().getSslKeyStorePassword());
		bindingConf.getParticipant().setSslKeyStoreType(localConfig.getLocal().getSslKeyStoreType());
		bindingConf.getParticipant().setSslKeyAlias(localConfig.getLocal().getSslKeyAlias());
		bindingConf.getParticipant().setSslTrustStore(localConfig.getLocal().getSslTrustStore());
		bindingConf.getParticipant().setSslTrustStorePassword(localConfig.getLocal().getSslTrustStorePassword());
		bindingConf.getParticipant().setSslTrustStoreType(localConfig.getLocal().getSslTrustStoreType());
		bindingConf.getParticipant().setProtocol(localConfig.getLocal().getSslProtocol());
		bindingConf.getParticipant().setEnabledProtocols(localConfig.getLocal().getSslEnabledProtocols());
		bindingConf.getParticipant().setCiphers(localConfig.getLocal().getSslCiphers());

		bindingConf.getDbConnection().setConnectionUri(localConfig.getStoragedDb().getUri());
		bindingConf.getDbConnection().setPassword(localConfig.getStoragedDb().getPassword());

		bindingConf.getArchiveDbConnection().setConnectionUri(localConfig.getArchiveStoragedDb().getUri());
		bindingConf.getArchiveDbConnection().setPassword(localConfig.getArchiveStoragedDb().getPassword());

		// confirm continue；
		prompter.info("\r\n\r\n This is participant [%s], the ledger initialization is ready to start!\r\n", currId);

		// start the web controller of Ledger Initializer;
		NetworkAddress serverAddress = ledgerInitProperties.getConsensusParticipant(currId).getInitializerAddress();

		//for dockers binding the 0.0.0.0;
		//if ledger-init.sh set up the -DhostPort=xxx -DhostIp=xxx, then get it;
		String preHostPort = System.getProperty("hostPort");
		if(!StringUtils.isEmpty(preHostPort)){
			int port = NumberUtils.parseNumber(preHostPort, Integer.class);
			serverAddress.setPort(port);
			ConsoleUtils.info("###ledger-init.sh###,set up the -DhostPort="+port);
		}
		String preHostIp = System.getProperty("hostIp");
		if(!StringUtils.isEmpty(preHostIp)){
			serverAddress.setHost(preHostIp);
			ConsoleUtils.info("###ledger-init.sh###,set up the -DhostIp="+preHostIp);
		}

		String argServerAddress = String.format("--server.address=%s", serverAddress.getHost());
		String argServerPort = String.format("--server.port=%s", serverAddress.getPort());
		String[] innerArgs = { argServerAddress, argServerPort };

		SpringApplication app = new SpringApplication(LedgerInitCommand.class);
		if (extBeans != null && extBeans.length > 0) {
			app.addInitializers((ApplicationContextInitializer<ConfigurableApplicationContext>) applicationContext -> {
				ConfigurableListableBeanFactory beanFactory = applicationContext.getBeanFactory();
				for (Object bean : extBeans) {
					beanFactory.registerSingleton(bean.toString(), bean);
				}
			});
		}
		ConfigurableApplicationContext ctx = app.run(innerArgs);
		this.ledgerManager = ctx.getBean(LedgerManager.class);

		prompter.info("\r\n------ Web controller of Ledger Initializer[%s:%s] was started. ------\r\n",
				serverAddress.getHost(), serverAddress.getPort());

		try {
			LedgerInitProcess initProc = ctx.getBean(LedgerInitProcess.class);
			HashDigest ledgerHash = initProc.initialize(currId, privKey, ledgerInitProperties,
					bindingConf.getDbConnection(), prompter);

			if (ledgerHash == null) {
				// ledger init fail;
				prompter.error("\r\n------ Ledger initialize fail! ------\r\n");
				return null;
			} else {
				prompter.info("\r\n------ Ledger initialize success! ------");
				prompter.info("New Ledger Hash is :[%s]", ledgerHash.toBase58());

				if (conf == null) {
					conf = new LedgerBindingConfig();
				}
				conf.addLedgerBinding(ledgerHash, bindingConf);

				return ledgerHash;

			}
		} finally {
			ctx.close();
			prompter.info("\r\n------ Web listener[%s:%s] was closed. ------\r\n", serverAddress.getHost(),
					serverAddress.getPort());
		}
	}

}
