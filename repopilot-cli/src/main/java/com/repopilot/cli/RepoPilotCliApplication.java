package com.repopilot.cli;

import picocli.CommandLine;

/**
 * CLI 模块启动类。
 * 这里只做一件事：把命令对象交给 picocli 执行，
 * 让后续参数解析和子命令扩展都能收敛到同一个入口。
 */
public final class RepoPilotCliApplication {

    private RepoPilotCliApplication() {
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RepoPilotCliCommand()).execute(args);
        System.exit(exitCode);
    }
}

