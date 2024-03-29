package com.jd.blockchain.peer.service;

import com.jd.blockchain.consensus.NodeSettings;
import com.jd.blockchain.consensus.bftsmart.BftsmartNodeSettings;
import com.jd.blockchain.consensus.raft.settings.RaftNodeSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import utils.net.NetworkAddress;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConsensusServiceFactory {

    @Autowired
    Map<String, IParticipantManagerService> services = new ConcurrentHashMap<>(3);

    public IParticipantManagerService getService(String providerName) {
        return services.get(providerName);
    }


    public NetworkAddress getNetWorkAddress(NodeSettings nodeSettings){
        if(nodeSettings instanceof BftsmartNodeSettings){
            return ((BftsmartNodeSettings)nodeSettings).getNetworkAddress();
        }

        if(nodeSettings instanceof RaftNodeSettings){
            return ((RaftNodeSettings)nodeSettings).getNetworkAddress();
        }

        return null;
    }

}
