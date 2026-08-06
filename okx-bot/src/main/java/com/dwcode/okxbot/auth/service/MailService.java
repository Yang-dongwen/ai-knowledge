package com.dwcode.okxbot.auth.service;

import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.auth.mail.AgentMailClient;
import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 统一邮件发送入口（注册验证码、找回密码等）。
 * <p>
 * 提供方由 {@code auth.mail.provider} 控制：
 * <ul>
 *   <li>{@code console} — 仅打日志（本地开发默认）</li>
 *   <li>{@code agentmail} — AgentMail HTTP API</li>
 *   <li>{@code smtp} — Spring JavaMailSender（如 QQ 邮箱 SMTP）</li>
 * </ul>
 * 兼容旧配置：{@code auth.mail.console-mode=true} 时强制 console。
 */
@Slf4j
@Service
public class MailService {

    private final AuthProperties authProperties;
    private final JavaMailSender mailSender;
    private final AgentMailClient agentMailClient;

    public MailService(AuthProperties authProperties,
                       ObjectProvider<JavaMailSender> mailSenderProvider,
                       AgentMailClient agentMailClient) {
        this.authProperties = authProperties;
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.agentMailClient = agentMailClient;
    }

    public void sendText(String to, String subject, String text) {
        String provider = resolveProvider();
        switch (provider) {
            case "agentmail" -> agentMailClient.sendText(to, subject, text);
            case "smtp" -> sendViaSmtp(to, subject, text);
            default -> sendViaConsole(to, subject, text, provider);
        }
    }

    /**
     * 解析实际提供方。console-mode=true 优先（兼容本地默认）。
     */
    String resolveProvider() {
        if (authProperties.getMail().isConsoleMode()) {
            return "console";
        }
        String p = authProperties.getMail().getProvider();
        if (p == null || p.isBlank()) {
            return "smtp";
        }
        return p.trim().toLowerCase();
    }

    private void sendViaConsole(String to, String subject, String text, String provider) {
        // 不打印完整验证码正文，避免日志泄露导致账号接管
        log.info("[MAIL-CONSOLE] provider={} to={} subject={} bodyLen={}",
                provider, to, subject, text == null ? 0 : text.length());
        if (!"console".equals(provider) && mailSender == null) {
            log.warn("邮件提供方为 {} 但未就绪，已降级为控制台（验证码未写入日志正文）", provider);
        }
    }

    private void sendViaSmtp(String to, String subject, String text) {
        if (mailSender == null) {
            log.warn("未配置 spring.mail，已降级为控制台输出验证码");
            sendViaConsole(to, subject, text, "smtp");
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(authProperties.getMail().getFrom());
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(text);
            mailSender.send(msg);
            log.info("SMTP 邮件已发送: to={}, subject={}", to, subject);
        } catch (Exception e) {
            log.error("SMTP 邮件发送失败: {}", e.getMessage());
            throw new BusinessException("邮件发送失败，请稍后重试");
        }
    }
}
