package io.openharness.core.commands;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * /slash 命令接口。所有内置命令实现此接口，由 CommandRegistry 统一管理。
 */
public interface OhCommand {
    String name();
    String description();
    Mono<Void> execute(List<String> args);
}
