package com.repopilot.server.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一提供时间源。
 * 这样服务层不会直接写死 Instant.now()，
 * 后续如果要做固定时钟测试或时区治理，只需要替换这个 Bean。
 */
@Configuration
public class TimeConfiguration {

    @Bean
    public Clock systemUtcClock() {
        return Clock.systemUTC();
    }
}

