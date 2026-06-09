package io.openharness.cli.commands;

import io.openharness.core.commands.OhCommand;
import io.openharness.core.observability.HealthChecker;
import reactor.core.publisher.Mono;

import java.util.List;

public class HealthCommand implements OhCommand {

    private final HealthChecker checker;

    public HealthCommand(HealthChecker checker) {
        this.checker = checker;
    }

    @Override
    public String name() { return "health"; }

    @Override
    public String description() { return "Run health checks"; }

    @Override
    public Mono<Void> execute(List<String> args) {
        HealthChecker.HealthReport report = checker.check();

        System.out.println("OpenHarness Health Check");
        System.out.println("========================");
        report.checks().forEach((name, status) -> {
            String icon = status == HealthChecker.Status.UP ? " OK " :
                    status == HealthChecker.Status.DOWN ? "FAIL" : "WARN";
            System.out.printf("  [%s] %s%n", icon, name);
        });
        System.out.println("------------------------");
        System.out.println("Overall: " + report.overall());
        return Mono.empty();
    }
}
