package com.jd.blockchain.ump.service;

import com.jd.blockchain.crypto.AsymmetricKeypair;
import com.jd.blockchain.crypto.Crypto;
import com.jd.blockchain.crypto.PrivKey;
import com.jd.blockchain.crypto.PubKey;
import com.jd.blockchain.crypto.service.classic.ClassicAlgorithm;
import com.jd.blockchain.ump.model.user.UserKeyBuilder;
import com.jd.blockchain.ump.model.user.UserKeys;

import utils.codec.Base58Utils;
import utils.crypto.classic.ED25519Utils;
import utils.crypto.classic.SHA256Utils;
import utils.security.ShaUtils;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.prng.FixedSecureRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;

import static com.jd.blockchain.crypto.KeyGenUtils.encodePrivKey;
import static com.jd.blockchain.crypto.KeyGenUtils.encodePubKey;

@Service
public class UtilServiceHandler implements UtilService {

    private static final String UTF_8 = "UTF-8";

    @Autowired
    private UmpStateService umpStateService;

    @Override
    public UserKeys create(UserKeyBuilder builder) {

        return create(builder.getName(), builder.getSeed(), builder.getPwd());
    }

    @Override
    public UserKeys create(String name, String seed, String pwd) {
//        AsymmetricCipherKeyPair keyPair = ED25519Utils.generateKeyPair(
//                new FixedSecureRandom(seed.getBytes(Charset.forName(UTF_8))));
//
//        PubKey pubKey = new PubKey(ClassicAlgorithm.ED25519,
//                ((Ed25519PublicKeyParameters) keyPair.getPublic()).getEncoded());
//
//        PrivKey privKey = new PrivKey(ClassicAlgorithm.ED25519,
//                ((Ed25519PrivateKeyParameters) keyPair.getPrivate()).getEncoded());
        
        byte[] seedBytes = seed.getBytes(Charset.forName(UTF_8));
        AsymmetricKeypair keypair = Crypto.getSignatureFunction(ClassicAlgorithm.ED25519).generateKeypair(seedBytes);

        return create(name, keypair.getPubKey(), keypair.getPrivKey(), pwd);
    }

    @Override
    public UserKeys read(int userId) {

        return umpStateService.readUserKeys(userId);
    }

    @Override
    public boolean verify(UserKeys userKeys, String pwd) {

        String encodePwd = Base58Utils.encode((SHA256Utils.hash(pwd.getBytes(Charset.forName(UTF_8)))));

        if (encodePwd.equals(userKeys.getEncodePwd())) {
            return true;
        }
        return false;
    }

    private UserKeys create(String name, PubKey pubKey, PrivKey privKey, String pwd) {

        byte[] pwdBytes = SHA256Utils.hash(pwd.getBytes(Charset.forName(UTF_8)));

        return new UserKeys(
                name,
                encodePrivKey(privKey, pwdBytes),
                encodePubKey(pubKey),
//                pwd,  // 密码不保存到数据库，防止泄露
                Base58Utils.encode(pwdBytes));
    }
}
