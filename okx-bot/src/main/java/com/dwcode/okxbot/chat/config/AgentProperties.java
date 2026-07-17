package com.dwcode.okxbot.chat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 对话 Agent 配置。
 *
 * <pre>
 * ai:
 *   agent:
 *     enabled: true
 *     decision-max-tokens: 512
 *     summary-max-tokens: 1200
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.agent")
public class AgentProperties {

    /** 总开关；false 时 agentMode 请求按普通聊天处理 */
    private boolean enabled = true;

    /** 决策轮 max_tokens */
    private int decisionMaxTokens = 512;

    /** 工具结果总结轮 max_tokens */
    private int summaryMaxTokens = 1200;

    /** 决策轮温度（偏低更稳） */
    private double decisionTemperature = 0.2;

    /** 写工具确认草案 TTL（秒） */
    private int confirmTtlSeconds = 900;

    /**
     * 单轮最多工具调用次数（防死循环）。
     * 当前编排为「决策 → 至多 1 次 tool」；预留多轮时生效。
     */
    private int maxToolRounds = 2;
}
