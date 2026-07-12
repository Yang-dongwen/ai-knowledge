package com.dwcode.okxbot.auth.service;

import com.dwcode.okxbot.auth.config.AuthProperties;
import com.dwcode.okxbot.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮件发送。consoleMode=true 时仅打日志，便于本地开发。
 */
@Slf4j
@Service
public class MailService {

    private final AuthProperties authProperties;
    private final JavaMailSender mailSender;

    public MailService(AuthProperties authProperties, ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.authProperties = authProperties;
        this.mailSender = mailSenderProvider.getIfAvailable();
    }

    public void sendText(String to, String subject, String text) {
        if (authProperties.getMail().isConsoleMode() || mailSender == null) {
            log.info("[MAIL-CONSOLE] to={} subject={} body=\n{}", to, subject, text);
            if (!authProperties.getMail().isConsoleMode() && mailSender == null) {
                log.warn("未配置 spring.mail，已降级为控制台输出验证码");
            }
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(authProperties.getMail().getFrom());
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(text);
            mailSender.send(msg);
            log.info("邮件已发送: to={}, subject={}", to, subject);
        } catch (Exception e) {
            log.error("邮件发送失败: {}", e.getMessage());
            throw new BusinessException("邮件发送失败，请稍后重试");
        }
    }
}
