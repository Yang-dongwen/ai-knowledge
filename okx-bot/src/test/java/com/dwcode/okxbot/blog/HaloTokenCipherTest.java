package com.dwcode.okxbot.blog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HaloTokenCipherTest {

    @Test
    void roundTrip() {
        HaloTokenCipher c = new HaloTokenCipher("unit-test-secret");
        String enc = c.encrypt("pat_hello");
        assertTrue(enc.startsWith("v1."));
        assertNotEquals("pat_hello", enc);
        assertEquals("pat_hello", c.decrypt(enc));
    }

    @Test
    void maskKeepsEnds() {
        assertEquals("pat_****oken", HaloTokenCipher.mask("pat_secret_token"));
    }

    @Test
    void patSubjectFromJwt() throws Exception {
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"alice\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String pat = "hdr." + payload + ".sig";
        assertEquals("alice", HaloConnectionProbe.subjectFromPat(pat, new com.fasterxml.jackson.databind.ObjectMapper()));
    }
}
