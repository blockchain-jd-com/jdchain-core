package com.jd.blockchain.consensus.raft.rpc;

import com.alipay.remoting.exception.CodecException;
import com.alipay.remoting.serialization.SerializerManager;
import com.alipay.sofa.jraft.Status;
import com.jd.blockchain.consensus.raft.server.RaftNodeServerService;
import com.jd.blockchain.consensus.raft.settings.RaftNodeSettings;
import com.jd.blockchain.runtime.RuntimeConstant;
import utils.net.NetworkAddress;

import java.io.Serializable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

public class QueryManagerInfoRequestProcessor extends BaseRpcProcessor<QueryManagerInfoRequest> {

    private static final AtomicReference<byte[]> manager_info_cache = new AtomicReference<>();
    private static final Object LOCK = new Object();

    public QueryManagerInfoRequestProcessor(RaftNodeServerService nodeServerService, Executor executor) {
        super(nodeServerService, executor);
    }

    @Override
    protected void processRequest(QueryManagerInfoRequest request, RpcResponseClosure done) throws Exception {

        if (manager_info_cache.get() == null) {
            synchronized (LOCK) {
                if (manager_info_cache.get() == null) {
                    RaftNodeSettings raftNodeSettings = getNodeServerService().getNodeServer().getServerSettings().getRaftNodeSettings();
                    NetworkAddress raftNodeNetworkAddress = raftNodeSettings.getNetworkAddress();

                    ManagerInfoResponse response = new ManagerInfoResponse();
                    response.setManagerPort(RuntimeConstant.getMonitorPort());
                    response.setManagerSSLEnabled(RuntimeConstant.isMonitorSecure());
                    response.setHost(raftNodeNetworkAddress.getHost());
                    response.setConsensusPort(raftNodeNetworkAddress.getPort());
                    response.setConsensusSSLEnabled(raftNodeNetworkAddress.isSecure());

                    manager_info_cache.set(response.toBytes());
                }
            }
        }

        done.setResponse(RpcResponse.success(manager_info_cache.get()));
        done.run(Status.OK());
    }


    public static class ManagerInfoResponse implements Serializable {

        private static final long serialVersionUID = -7023845363840817072L;

        private String host;
        private int managerPort;
        private boolean managerSSLEnabled;
        private int consensusPort;
        private boolean consensusSSLEnabled;

        public byte[] toBytes() throws CodecException {
            return SerializerManager.getSerializer(SerializerManager.Hessian2).serialize(this);
        }

        public static ManagerInfoResponse fromBytes(byte[] bytes) throws CodecException {
            return SerializerManager.getSerializer(SerializerManager.Hessian2).deserialize(bytes, ManagerInfoResponse.class.getName());
        }

        public int getManagerPort() {
            return managerPort;
        }

        public void setManagerPort(int managerPort) {
            this.managerPort = managerPort;
        }

        public boolean isManagerSSLEnabled() {
            return managerSSLEnabled;
        }

        public void setManagerSSLEnabled(boolean managerSSLEnabled) {
            this.managerSSLEnabled = managerSSLEnabled;
        }

        public int getConsensusPort() {
            return consensusPort;
        }

        public void setConsensusPort(int consensusPort) {
            this.consensusPort = consensusPort;
        }

        public boolean isConsensusSSLEnabled() {
            return consensusSSLEnabled;
        }

        public void setConsensusSSLEnabled(boolean consensusSSLEnabled) {
            this.consensusSSLEnabled = consensusSSLEnabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }
    }

}
