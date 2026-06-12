package com.openharness.ohmo;

import java.nio.file.Path;

/**
 * Entry point for ohmo CLI commands.
 * Java equivalent of Python ohmo/cli.py.
 */
public class OhmoCLI {

    private final Path workspaceRoot;
    private final WorkspaceManager wm;

    public OhmoCLI(String workspace) {
        this.wm = new WorkspaceManager();
        this.workspaceRoot = wm.resolve(workspace);
        wm.initialize(workspaceRoot);
    }

    public void memoryList() {
        OhmoMemoryBackend mem = new OhmoMemoryBackend(workspaceRoot.resolve("memory"));
        var entries = mem.listEntries();
        if (entries.isEmpty()) {
            System.out.println("No memories found.");
        } else {
            entries.forEach(e -> System.out.println("  [" + e.name() + "] " + e.content()));
        }
    }

    public void memoryAdd(String name, String content) {
        OhmoMemoryBackend mem = new OhmoMemoryBackend(workspaceRoot.resolve("memory"));
        mem.addEntry(name, content);
        System.out.println("Memory saved: " + name);
    }

    public void memoryRemove(String name) {
        OhmoMemoryBackend mem = new OhmoMemoryBackend(workspaceRoot.resolve("memory"));
        mem.removeEntry(name);
        System.out.println("Memory removed: " + name);
    }

    public void soul() {
        System.out.println("Workspace: " + workspaceRoot);
        System.out.println("Soul: " + workspaceRoot.resolve("soul.md"));
    }

    public void gatewayStart() {
        OhmoGatewayService service = new OhmoGatewayService(
                System.getProperty("user.dir"), workspaceRoot.toString());
        service.start();
        System.out.println("Gateway started.");
    }

    public void gatewayStatus() {
        OhmoGatewayService service = new OhmoGatewayService(
                System.getProperty("user.dir"), workspaceRoot.toString());
        GatewayState state = service.getState();
        System.out.println("Gateway: " + (state.running() ? "running" : "stopped"));
        System.out.println("  Active sessions: " + state.activeSessions());
        System.out.println("  Channels: " + String.join(", ", state.enabledChannels()));
    }

    public void healthCheck() {
        var results = wm.healthCheck(workspaceRoot);
        results.forEach((k, v) -> System.out.println("  " + k + ": " + (v ? "OK" : "MISSING")));
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("ohmo: personal AI agent built on OpenHarness");
            System.out.println("Usage: ohmo <memory|soul|gateway|health>");
            return;
        }
        OhmoCLI cli = new OhmoCLI(null);
        switch (args[0]) {
            case "memory" -> {
                if (args.length >= 3 && "--add".equals(args[1]))
                    cli.memoryAdd(args[2], args.length > 3 ? args[3] : "");
                else if (args.length >= 3 && "--remove".equals(args[1]))
                    cli.memoryRemove(args[2]);
                else cli.memoryList();
            }
            case "soul" -> cli.soul();
            case "gateway" -> {
                if (args.length >= 2 && "--status".equals(args[1])) cli.gatewayStatus();
                else cli.gatewayStart();
            }
            case "health" -> cli.healthCheck();
            default -> System.out.println("Unknown command: " + args[0]);
        }
    }
}
