package com.repopilot.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RepoPilot 控制面的 Spring Boot 启动入口。
 * server 模块只负责会话、轨迹、审批、回放等控制面能力，
 * 不直接操作本地代码仓，这个边界从入口类开始就固定下来。
 */
@SpringBootApplication
public class RepoPilotServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RepoPilotServerApplication.class, args);
    }
}
