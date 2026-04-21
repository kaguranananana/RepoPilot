package com.repopilot.server.session;

/**
 * 查询不到指定计划时抛出。
 */
public class PlanNotFoundException extends RuntimeException {

    public PlanNotFoundException(String planId) {
        super("Plan not found: " + planId);
    }
}
