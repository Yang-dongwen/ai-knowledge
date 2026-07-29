package com.dwcode.okxbot.auth.service;

import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.auth.mail.AgentMailClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailServiceTest {

    @Test
    void consoleMode_forcesConsole_evenIfProviderAgentmail() {
        AuthProperties props = new AuthProperties();
        props.getMail().setConsoleMode(true);
        props.getMail().setProvider("agentmail");

        AgentMailClient agent = mock(AgentMailClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        MailService service = new MailService(props, provider, agent);
        assertEquals("console", service.resolveProvider());

        service.sendText("a@b.com", "s", "body");
        verify(agent, never()).sendText("a@b.com", "s", "body");
    }

    @Test
    void agentmailProvider_delegatesToClient() {
        AuthProperties props = new AuthProperties();
        props.getMail().setConsoleMode(false);
        props.getMail().setProvider("agentmail");

        AgentMailClient agent = mock(AgentMailClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        MailService service = new MailService(props, provider, agent);
        assertEquals("agentmail", service.resolveProvider());

        service.sendText("user@example.com", "验证码", "123456");
        verify(agent).sendText("user@example.com", "验证码", "123456");
    }
}
