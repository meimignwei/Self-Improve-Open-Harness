package io.openharness.cli.commands;

import io.openharness.core.commands.OhCommand;
import io.openharness.core.session.SessionContext;
import reactor.core.publisher.Mono;

import java.util.List;

public class ModelCommand implements OhCommand {

    private final SessionContext ctx;

    public ModelCommand(SessionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String name() { return "model"; }

    @Override
    public String description() { return "Switch model. Usage: /model <sonnet|opus|haiku>"; }

    @Override
    public Mono<Void> execute(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("Current model: " + ctx.getModel());
            return Mono.empty();
        }

        String name = args.get(0).toLowerCase();
        String model = switch (name) {
            case "sonnet" -> "claude-sonnet-4-6";
            case "opus" -> "claude-opus-4-7";
            case "haiku" -> "claude-haiku-4-5";
            default -> null;
        };

        if (model == null) {
            System.out.println("Unknown model: " + name + ". Use: sonnet | opus | haiku");
            return Mono.empty();
        }

        ctx.setModel(model);
        System.out.println("Model switched to: " + model);
        return Mono.empty();
    }
}
