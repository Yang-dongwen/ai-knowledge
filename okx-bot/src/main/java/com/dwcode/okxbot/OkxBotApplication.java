package com.dwcode.okxbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OKX 自动交易助手启动类。
 */
@SpringBootApplication
@EnableScheduling
public class OkxBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(OkxBotApplication.class, args);
    }
}
