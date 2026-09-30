package com.monitor.platform.collector.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 凭证加解密测试。
 */
class CredentialCipherTest {

    @Test
    void shouldEncryptAndDecryptPassword() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        CredentialCipher cipher = new CredentialCipher(Base64.getEncoder().encodeToString(key));

        CredentialCipher.EncryptedCredential encrypted = cipher.encrypt("password-123");

        assertEquals(CredentialCipher.ALGORITHM, encrypted.algorithm());
        assertNotEquals("password-123", encrypted.encryptedPayload());
        assertEquals("password-123", cipher.decrypt(
                encrypted.encryptedPayload(),
                encrypted.initializationVector(),
                encrypted.algorithm(),
                encrypted.keyVersion()
        ));
    }

    @Test
    void shouldRejectMissingKey() {
        CredentialCipher cipher = new CredentialCipher("");

        assertThrows(IllegalStateException.class, () -> cipher.encrypt("password"));
    }
}