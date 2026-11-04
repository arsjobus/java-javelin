package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LoopbackEndpointTest {
    @Test void thisComputersAddressesAreAccepted() throws Exception {
        for (String ok : new String[] {"http://127.0.0.1:11434", "http://localhost:11434", "HTTP://LocalHost", "http://127.5.6.7:80/", "http://[::1]:11434", "https://127.0.0.1:8443"})
            assertNotNull(LoopbackEndpoint.require(ok), ok);
        assertEquals("127.0.0.1", LoopbackEndpoint.require(" http://127.0.0.1:11434 ").getHost());
        assertEquals(11434, LoopbackEndpoint.require("http://localhost:11434/").getPort());
        assertEquals("[::1]", LoopbackEndpoint.require("http://[::1]:1").getHost());
    }

    @Test void everythingElseIsRefusedWithAnExplanation() {
        for (String bad : new String[] {"http://example.com", "http://10.0.0.5:11434", "http://192.168.1.2", "http://127.0.0.256", "http://localhost.evil.com",
            "http://127.0.0.1.evil.com", "http://0.0.0.0:11434", "http://[::2]", "ftp://127.0.0.1", "127.0.0.1:11434", "http://user:pw@127.0.0.1", "http://127.0.0.1/api",
            "http://127.0.0.1?x=1", "http://127.0.0.1#x", "http://", "", "   ", "not a url at all"}) {
            AiException ex = assertThrows(AiException.class, () -> LoopbackEndpoint.require(bad), bad);
            assertEquals(AiException.Kind.REFUSED, ex.kind(), bad);
            assertFalse(ex.getMessage().isBlank());
        }
        assertThrows(AiException.class, () -> LoopbackEndpoint.require(null));
        assertTrue(assertThrows(AiException.class, () -> LoopbackEndpoint.require("http://example.com")).getMessage().contains("this computer"));
    }

    @Test void hostCheckDoesNotLookAnythingUp() {
        assertTrue(LoopbackEndpoint.isLoopbackHost("localhost"));
        assertFalse(LoopbackEndpoint.isLoopbackHost("my-machine.local"));
    }
}
