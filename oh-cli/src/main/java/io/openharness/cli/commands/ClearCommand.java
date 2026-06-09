package io.openharness.cli.commands;

import io.openharness.core.commands.OhCommand;
import reactor.core.publisher.Mono;

import java.util.List;

public class ClearCommand implements OhCommand {

    @Override
    public String name() { return "clear"; }

    @Override
    public String description() { return "Clear the terminal screen"; }

    @Override
    public Mono<Void> execute(List<String> args) {
        System.out.print("\033[H\033[2J");
        System.out.flush();
        return Mono.empty();
    }
}
