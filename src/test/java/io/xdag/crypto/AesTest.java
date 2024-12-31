/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.xdag.crypto;

import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

public class AesTest {

    SecureRandom random = new SecureRandom();

    @Test
    public void testEncryptAndDecrypt() {
        // Prepare test data
        String originalText = "Hello XDAG!";
        byte[] data = originalText.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[32]; // 256-bit key
        byte[] iv = new byte[16];  // 128-bit IV
        random.nextBytes(key);
        random.nextBytes(iv);

        // Encrypt
        byte[] encrypted = Aes.encrypt(data, key, iv);
        assertNotNull(encrypted);
        assertNotEquals(originalText, new String(encrypted, StandardCharsets.UTF_8));

        // Decrypt
        byte[] decrypted = Aes.decrypt(encrypted, key, iv);
        assertNotNull(decrypted);
        assertEquals(originalText, new String(decrypted, StandardCharsets.UTF_8));
    }

    @Test
    public void testEmptyInput() {
        byte[] data = new byte[0];
        byte[] key = new byte[32];
        byte[] iv = new byte[16];
        random.nextBytes(key);
        random.nextBytes(iv);

        byte[] encrypted = Aes.encrypt(data, key, iv);
        assertNotNull(encrypted);

        byte[] decrypted = Aes.decrypt(encrypted, key, iv);
        assertNotNull(decrypted);
        assertEquals(0, decrypted.length);
    }

    @Test
    public void testLargeInput() {
        byte[] data = new byte[1024 * 1024]; // 1MB of data
        random.nextBytes(data);
        byte[] key = new byte[32];
        byte[] iv = new byte[16];
        random.nextBytes(key);
        random.nextBytes(iv);

        byte[] encrypted = Aes.encrypt(data, key, iv);
        assertNotNull(encrypted);

        byte[] decrypted = Aes.decrypt(encrypted, key, iv);
        assertNotNull(decrypted);
        assertArrayEquals(data, decrypted);
    }

    @Test(expected = RuntimeException.class)
    public void testInvalidKeySize() {
        byte[] data = "Test data".getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[15]; // Invalid key size
        byte[] iv = new byte[16];
        random.nextBytes(key);
        random.nextBytes(iv);

        Aes.encrypt(data, key, iv);
    }

    @Test(expected = RuntimeException.class)
    public void testInvalidIVSize() {
        byte[] data = "Test data".getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[32];
        byte[] iv = new byte[8]; // Invalid IV size
        random.nextBytes(key);
        random.nextBytes(iv);

        Aes.encrypt(data, key, iv);
    }

    @Test(expected = RuntimeException.class)
    public void testDecryptionWithWrongKey() {
        String originalText = "Secret message";
        byte[] data = originalText.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[32];
        byte[] wrongKey = new byte[32];
        byte[] iv = new byte[16];
        random.nextBytes(key);
        random.nextBytes(wrongKey);
        random.nextBytes(iv);

        byte[] encrypted = Aes.encrypt(data, key, iv);
        // This should throw RuntimeException due to padding error
        Aes.decrypt(encrypted, wrongKey, iv);
    }
} 