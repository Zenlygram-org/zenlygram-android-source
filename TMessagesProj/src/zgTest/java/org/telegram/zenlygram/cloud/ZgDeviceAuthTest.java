package org.telegram.zenlygram.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Map;

/** DeviceAuth byte format (docs/entitlement.md §2.2): LF-joined input, standard base64 signature, fresh nonce. */
public class ZgDeviceAuthTest {

    @Test
    public void headersVerifyAndNeverRepeatANonce() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        final KeyPair pair = generator.generateKeyPair();
        final String deviceId = ZgDeviceAuth.newDeviceId();
        assertTrue(deviceId.matches("[0-9a-f]{64}"));

        final Map<String, String> a = ZgDeviceAuth.headers(deviceId, pair.getPrivate(), 1794510715L, 0);
        final Map<String, String> b = ZgDeviceAuth.headers(deviceId, pair.getPrivate(), 1794510715L, 7);
        assertNotEquals(a.get("X-Device-Nonce"), b.get("X-Device-Nonce"));
        assertEquals("1794510715", a.get("X-Device-Ts"));
        assertEquals("1794510722", b.get("X-Device-Ts"));
        final String sig = a.get("X-Device-Sig");
        assertTrue(sig.matches("[A-Za-z0-9+/]+=*"));

        final Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(pair.getPublic());
        verifier.update((deviceId + "\n" + a.get("X-Device-Nonce") + "\n" + a.get("X-Device-Ts")).getBytes("UTF-8"));
        assertTrue(verifier.verify(ZgBase64.decodeStandard(sig)));
    }
}
