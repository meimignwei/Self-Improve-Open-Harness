package io.openharness.cli.commands;

import io.openharness.core.commands.OhCommand;
import io.openharness.core.config.Settings;
import reactor.core.publisher.Mono;

import java.util.List;

public class ConfigCommand implements OhCommand {

    private final Settings settings;

    public ConfigCommand(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String name() { return "config"; }

    @Override
    public String description() { return "View or change configuration. Usage: /config [key] [value]"; }

    @Override
    public Mono<Void> execute(List<String> args) {
        if (args.isEmpty()) {
            System.out.println("model: " + settings.getModel());
            System.out.println("maxTurns: " + settings.getMaxTurns());
            System.out.println("apiBaseUrl: " + settings.getApiBaseUrl());
            System.out.println("logLevel: " + settings.getLogLevel());
            return Mono.empty();
        }

        String key = args.get(0);
        if (args.size() < 2) {
            System.out.println(key + ": " + getValue(key));
            return Mono.empty();
        }

        String value = args.get(1);
        setValue(key, value);
        System.out.println("Set " + key + " = " + value);
        return Mono.empty();
    }

    private String getValue(String key) {
        return switch (key) {
            case "model" -> settings.getModel();
            case "apiBaseUrl" -> settings.getApiBaseUrl();
            case "logLevel" -> settings.getLogLevel();
            case "maxTurns" -> String.valueOf(settings.getMaxTurns());
            default -> "unknown key: " + key;
        };
    }

    private void setValue(String key, String value) {
        switch (key) {
            case "model" -> settings.setModel(value);
            case "logLevel" -> settings.setLogLevel(value);
            case "maxTurns" -> settings.setMaxTurns(Integer.parseInt(value));
            case "apiBaseUrl" -> settings.setApiBaseUrl(value);
        }
    }
}
