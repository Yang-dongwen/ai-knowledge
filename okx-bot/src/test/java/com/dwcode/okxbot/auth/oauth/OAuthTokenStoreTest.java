package com.dwcode.okxbot.auth.oauth;

import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OAuthTokenStoreTest {

    private OAuthTokenStore store;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties();
        props.getJwt().setSecret("test-jwt-secret-key-for-oauth-token-store-32b");
        props.getOauth().setTicketTtlSeconds(60);
        store = new OAuthTokenStore(props);
    }

    @Test
    void stateRoundTrip() {
        String state = store.createState(OAuthProvider.GOOGLE, "/video-extract");
        OAuthTokenStore.StatePayload payload = store.consumeState(state, OAuthProvider.GOOGLE);
        assertEquals(OAuthProvider.GOOGLE, payload.provider());
        assertEquals("/video-extract", payload.redirectPath());
    }

    @Test
    void stateCannotReuse() {
        String state = store.createState(OAuthProvider.GITHUB, "/home");
        store.consumeState(state, OAuthProvider.GITHUB);
        assertThrows(BusinessException.class, () -> store.consumeState(state, OAuthProvider.GITHUB));
    }

    @Test
    void ticketRoundTrip() {
        String ticket = store.createTicket(12345L);
        assertEquals(12345L, store.consumeTicket(ticket));
        assertThrows(BusinessException.class, () -> store.consumeTicket(ticket));
    }

    @Test
    void providerMismatchFails() {
        String state = store.createState(OAuthProvider.GOOGLE, "/");
        assertThrows(BusinessException.class, () -> store.consumeState(state, OAuthProvider.GITHUB));
    }
}
