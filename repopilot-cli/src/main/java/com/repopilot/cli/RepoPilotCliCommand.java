package com.repopilot.cli;

import com.repopilot.cli.command.EvalCommand;
import com.repopilot.cli.command.RunCommand;
import com.repopilot.cli.interactive.InteractiveCliSession;
import java.util.Objects;
import picocli.CommandLine.Command;

/**
 * CLI 模块根命令。
 * 默认直接进入交互模式，
 * 同时继续保留显式 `run` 子命令，方便做单次运行与对照测试。
 */
@Command(
        name = "repopilot",
        mixinStandardHelpOptions = true,
        description = "RepoPilot 智能编码代理平台的命令行入口。",
        subcommands = {
                EvalCommand.class,
                RunCommand.class
        }
)
public class RepoPilotCliCommand implements Runnable {

    private final InteractiveCliStarter interactiveCliStarter;

    public RepoPilotCliCommand() {
        this(() -> InteractiveCliSession.createDefault().start());
    }

    RepoPilotCliCommand(InteractiveCliStarter interactiveCliStarter) {
        this.interactiveCliStarter = Objects.requireNonNull(
                interactiveCliStarter,
                "interactiveCliStarter must not be null."
        );
    }

    @Override
    public void run() {
        interactiveCliStarter.start();
    }

    @FunctionalInterface
    interface InteractiveCliStarter {

        void start();
    }
}
