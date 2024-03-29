package com.jd.blockchain.gateway;

import java.io.File;
import java.io.InputStream;
import java.util.*;

import utils.net.SSLClientAuth;
import utils.StringUtils;
import utils.io.FileUtils;
import utils.net.NetworkAddress;

public class GatewayConfigProperties {

	// HTTP协议相关配置项的键的前缀；
	public static final String HTTP_PREFIX = "http.";
	// HTTP协议相关配置项的键的前缀；
	public static final String HTTPS_PREFIX = "https.";
	// 网关的HTTP服务地址；
	public static final String HTTP_HOST = HTTP_PREFIX + "host";
	// 网关的HTTP服务端口；
	public static final String HTTP_PORT = HTTP_PREFIX + "port";
	// 网关服务是否启用安全证书；
	public static final String HTTP_SECURE = HTTP_PREFIX + "secure";
	// 网关服务TLS客户端认证模式；
	public static final String HTTP_CLIENT_AUTH = HTTPS_PREFIX + "client-auth";
	// 网关的HTTP服务上下文路径，可选；
	public static final String HTTP_CONTEXT_PATH = HTTP_PREFIX + "context-path";

	// 共识相关配置项的键的前缀；
	public static final String PEER_PREFIX = "peer.";
	// 共识节点的服务地址；
	public static final String PEER_HOST_FORMAT = PEER_PREFIX + "host";
	// 共识节点的服务端口；
	public static final String PEER_PORT_FORMAT = PEER_PREFIX + "port";
	// 共识节点的服务是否启用安全证书；
	public static final String PEER_SECURE_FORMAT = PEER_PREFIX + "secure";
	// 共识节点的共识服务是否启用安全证书；
	public static final String PEER_CONSENSUS_SECURE = PEER_PREFIX + "consensus.secure";
	// 支持共识的Provider列表，以英文逗号分隔
	public static final String PEER_PROVIDERS = PEER_PREFIX + "providers";

	// 账本配置拓扑信息落盘，默认false
	public static final String TOPOLOGY_STORE = "topology.store";
	// 开启动态感知，默认true 1.5.0版本新增
	public static final String TOPOLOGY_AWARE = "topology.aware";
	// 共识节点自动感知间隔（毫秒），0及负值表示仅感知一次
	public static final String TOPOLOGY_AWARE_INTERVAL = "topology.aware.interval";
	// 节点连接心跳（毫秒），及时感知连接有效性，0及负值表示关闭
	public static final String PEER_CONNECTION_PING = "peer.connection.ping";
	// 节点连接认证（毫秒），及时感知连接合法性，0及负值表示关闭。对于不存在节点变更的场景可关闭
	public static final String PEER_CONNECTION_AUTH = "peer.connection.auth";

	// 数据检索服务URL地址
	public static final String DATA_RETRIEVAL_URL="data.retrieval.url";
	public static final String SCHEMA_RETRIEVAL_URL="schema.retrieval.url";

	// 密钥相关配置项的键的前缀；
	public static final String KEYS_PREFIX = "keys.";
	// 默认密钥相关配置项的键的前缀；
	public static final String DEFAULT_KEYS_PREFIX = KEYS_PREFIX + "default.";
	// 默认私钥的内容；
	public static final String DEFAULT_PUBKEY = DEFAULT_KEYS_PREFIX + "pubkey";
	// 默认证书路径；
	public static final String DEFAULT_CA_PATH = DEFAULT_KEYS_PREFIX + "ca-path";
	// 默认私钥的文件存储路径；
	public static final String DEFAULT_PRIVKEY_PATH = DEFAULT_KEYS_PREFIX + "privkey-path";
	// 默认私钥的内容；
	public static final String DEFAULT_PRIVKEY = DEFAULT_KEYS_PREFIX + "privkey";
	// 默认私钥的密码；
	public static final String DEFAULT_PK_PWD = DEFAULT_KEYS_PREFIX + "privkey-password";


	private HttpConfig http = new HttpConfig();

	private NetworkAddress masterPeerAddress = null;
	private SSLClientAuth masterPeerClientAuth = SSLClientAuth.NONE;
	private boolean consensusSecure;
	private boolean storeTopology;
	private boolean awareTopology;
	private int awareTopologyInterval;
	private int peerConnectionPing;
	private int peerConnectionAuth;

	private String dataRetrievalUrl;
	private String schemaRetrievalUrl;

	private KeysConfig keys = new KeysConfig();

	public HttpConfig http() {
		return http;
	}

	public NetworkAddress masterPeerAddress() {
		return masterPeerAddress;
	}

	public SSLClientAuth getMasterPeerClientAuth() {
		return masterPeerClientAuth;
	}

	public boolean isConsensusSecure() {
		return consensusSecure;
	}

	public void setConsensusSecure(boolean consensusSecure) {
		this.consensusSecure = consensusSecure;
	}

	public String dataRetrievalUrl() {
		return this.dataRetrievalUrl;
	}

	public void setDataRetrievalUrl(String dataRetrievalUrl) {
		this.dataRetrievalUrl = dataRetrievalUrl;
	}

	public String getSchemaRetrievalUrl() {
		return schemaRetrievalUrl;
	}

	public void setSchemaRetrievalUrl(String schemaRetrievalUrl) {
		this.schemaRetrievalUrl = schemaRetrievalUrl;
	}

	public void setMasterPeerAddress(NetworkAddress peerAddress) {
		if (peerAddress == null) {
			throw new IllegalArgumentException("peerAddress is null!");
		}
		this.masterPeerAddress = peerAddress;
	}

	public KeysConfig keys() {
		return keys;
	}

	public GatewayConfigProperties() {
	}

	public static GatewayConfigProperties resolve(String file) {
		Properties props = FileUtils.readProperties(file, "UTf-8");
		return resolve(props);
	}

	public static GatewayConfigProperties resolve(File file) {
		Properties props = FileUtils.readProperties(file, "UTf-8");
		return resolve(props);
	}

	public static GatewayConfigProperties resolve(InputStream in) {
		Properties props = FileUtils.readProperties(in, "UTf-8");
		return resolve(props);
	}

	public static GatewayConfigProperties resolve(Properties props) {
		GatewayConfigProperties configProps = new GatewayConfigProperties();
		configProps.http.host = getProperty(props, HTTP_HOST, true);
		configProps.http.port = getInt(props, HTTP_PORT, true);
		configProps.http.secure = getBoolean(props, HTTP_SECURE, false, false);
		if(configProps.http.secure) {
			configProps.http.clientAuth = SSLClientAuth.valueOf(getProperty(props, HTTP_CLIENT_AUTH, false, "none").toUpperCase());
		}
		configProps.http.contextPath = getProperty(props, HTTP_CONTEXT_PATH, false);

		String peerHost = getProperty(props, PEER_HOST_FORMAT, true);
		int peerPort = getInt(props, PEER_PORT_FORMAT, true);
		boolean peerSecure = getBoolean(props, PEER_SECURE_FORMAT, false, false);
		configProps.setMasterPeerAddress(new NetworkAddress(peerHost, peerPort, peerSecure));

		configProps.consensusSecure = getBoolean(props, PEER_CONSENSUS_SECURE, false, false);

		configProps.setStoreTopology(getBoolean(props, TOPOLOGY_STORE, false));
		configProps.setAwareTopology(getBoolean(props, TOPOLOGY_AWARE, false, true));
		configProps.setAwareTopologyInterval(getInt(props, TOPOLOGY_AWARE_INTERVAL, false));

		configProps.setPeerConnectionPing(getInt(props, PEER_CONNECTION_PING, false));
		configProps.setPeerConnectionAuth(getInt(props, PEER_CONNECTION_AUTH, false));

		String dataRetrievalUrl = getProperty(props, DATA_RETRIEVAL_URL, false);
		configProps.dataRetrievalUrl = dataRetrievalUrl;

		String schemaRetrievalUrl = getProperty(props, SCHEMA_RETRIEVAL_URL, false);
		configProps.schemaRetrievalUrl = schemaRetrievalUrl;

		String pubkeyString = getProperty(props, DEFAULT_PUBKEY, false);
		String capath = getProperty(props, DEFAULT_CA_PATH, false);
		if(StringUtils.isEmpty(pubkeyString) && StringUtils.isEmpty(capath)) {
			throw new IllegalArgumentException("Miss both of pubkey and certificate!");
		}
		configProps.keys.defaultPK.pubKeyValue = pubkeyString;
		configProps.keys.defaultPK.caPath = capath;
		configProps.keys.defaultPK.privKeyPath = getProperty(props, DEFAULT_PRIVKEY_PATH, false);
		configProps.keys.defaultPK.privKeyValue = getProperty(props, DEFAULT_PRIVKEY, false);
		if (configProps.keys.defaultPK.privKeyPath == null && configProps.keys.defaultPK.privKeyValue == null) {
			throw new IllegalArgumentException("Miss both of pk-path and pk content!");
		}
		configProps.keys.defaultPK.privKeyPassword = getProperty(props, DEFAULT_PK_PWD, false);

		return configProps;
	}

	private static String getProperty(Properties props, String key, boolean required) {
		String value = props.getProperty(key);
		if (value != null) {
			value = value.trim();
		}
		if (value == null || value.length() == 0) {
			if (required) {
				throw new IllegalArgumentException("Miss property[" + key + "]!");
			}
			return null;
		}
		return value;
	}

	private static String getProperty(Properties props, String key, boolean required, String defaultValue) {
		String str = getProperty(props, key, required);
		if (str == null) {
			return defaultValue;
		}
		return str.toUpperCase();
	}

	private static boolean getBoolean(Properties props, String key, boolean required) {
		String strBool = getProperty(props, key, required);
		if (strBool == null) {
			return false;
		}
		return Boolean.parseBoolean(strBool);
	}

	private static boolean getBoolean(Properties props, String key, boolean required, boolean defaultValue) {
		String strBool = getProperty(props, key, required);
		if (strBool == null) {
			return defaultValue;
		}
		return Boolean.parseBoolean(strBool);
	}

	private static int getInt(Properties props, String key, boolean required) {
		String strInt = getProperty(props, key, required);
		if (strInt == null) {
			return 0;
		}
		return getInt(strInt);
	}

	private static int getInt(String strInt) {
		return Integer.parseInt(strInt.trim());
	}

	public boolean isStoreTopology() {
		return storeTopology;
	}

	public void setStoreTopology(boolean storeTopology) {
		this.storeTopology = storeTopology;
	}

	public boolean isAwareTopology() {
		return awareTopology;
	}

	public void setAwareTopology(boolean awareTopology) {
		this.awareTopology = awareTopology;
	}

	public int getAwareTopologyInterval() {
		return awareTopologyInterval;
	}

	public void setAwareTopologyInterval(int awareTopologyInterval) {
		this.awareTopologyInterval = awareTopologyInterval;
	}

	public int getPeerConnectionPing() {
		return peerConnectionPing;
	}

	public void setPeerConnectionPing(int peerConnectionPing) {
		this.peerConnectionPing = peerConnectionPing;
	}

	public int getPeerConnectionAuth() {
		return peerConnectionAuth;
	}

	public void setPeerConnectionAuth(int peerConnectionAuth) {
		this.peerConnectionAuth = peerConnectionAuth;
	}

	// ------------------------------------------------------------

	public static class HttpConfig {

		private String host;
		private int port;
		private String contextPath;
		private boolean secure;
		private SSLClientAuth clientAuth;

		private HttpConfig() {
		}

		public String getHost() {
			return host;
		}

		public void setHost(String host) {
			this.host = host;
		}

		public int getPort() {
			return port;
		}

		public void setPort(int port) {
			this.port = port;
		}

		public String getContextPath() {
			return contextPath;
		}

		public void setContextPath(String contextPath) {
			this.contextPath = contextPath;
		}

		public boolean isSecure() {
			return secure;
		}

		public void setSecure(boolean secure) {
			this.secure = secure;
		}

		public SSLClientAuth getClientAuth() {
			return clientAuth;
		}

		public void setClientAuth(SSLClientAuth clientAuth) {
			this.clientAuth = clientAuth;
		}
	}

	public static class KeysConfig {

		private KeyPairConfig defaultPK = new KeyPairConfig();

		public KeyPairConfig getDefault() {
			return defaultPK;
		}
	}

	public static class KeyPairConfig {

		private String pubKeyValue;

		private String caPath;

		private String privKeyPath;

		private String privKeyValue;

		private String privKeyPassword;

		public String getPrivKeyPath() {
			return privKeyPath;
		}

		public String getPrivKeyValue() {
			return privKeyValue;
		}

		public String getPrivKeyPassword() {
			return privKeyPassword;
		}

		public String getPubKeyValue() {
			return pubKeyValue;
		}

		public void setPubKeyValue(String pubKeyValue) {
			this.pubKeyValue = pubKeyValue;
		}

		public void setPrivKeyPath(String privKeyPath) {
			this.privKeyPath = privKeyPath;
		}

		public void setPrivKeyValue(String privKeyValue) {
			this.privKeyValue = privKeyValue;
		}

		public void setPrivKeyPassword(String privKeyPassword) {
			this.privKeyPassword = privKeyPassword;
		}

		public String getCaPath() {
			return caPath;
		}

		public void setCaPath(String caPath) {
			this.caPath = caPath;
		}
	}

}
