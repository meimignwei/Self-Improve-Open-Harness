package com.openharness.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Picocli MainCommand.
 */
class MainCommandIT {

    @Test
    void version() {
        int exitCode = new CommandLine(new MainCommand()).execute("--version");
        assertEquals(0, exitCode);
    }

    @Test
    void help() {
        var baos = new ByteArrayOutputStream();
        var out = System.out;
        System.setOut(new PrintStream(baos));
        try {
            new CommandLine(new MainCommand()).execute("--help");
        } finally {
            System.setOut(out);
        }
        assertTrue(baos.toString().contains("OpenHarness"));
    }

    @Test
    void doctorRunsWithoutError() {
        int exitCode = new CommandLine(new MainCommand()).execute("doctor");
        assertEquals(0, exitCode);
    }

    @Test
    void configShowRunsWithoutError() {
        int exitCode = new CommandLine(new MainCommand()).execute("config", "--show");
        assertEquals(0, exitCode);
    }

    @Test
    void initCreatesOpenHarnessDir(@TempDir Path tempDir) {
        int exitCode = new CommandLine(new MainCommand())
                .execute("init", "-d", tempDir.toString());
        assertEquals(0, exitCode);

        Path ohDir = tempDir.resolve(".openharness");
        assertTrue(java.nio.file.Files.exists(ohDir));
        assertTrue(java.nio.file.Files.exists(ohDir.resolve("skills")));
        assertTrue(java.nio.file.Files.exists(ohDir.resolve("memory")));
        assertTrue(java.nio.file.Files.exists(ohDir.resolve("autopilot")));
    }

    @Test
    void gatewayStatusPrintsMessage() {
        var baos = new ByteArrayOutputStream();
        var out = System.out;
        System.setOut(new PrintStream(baos));
        try {
            int exitCode = new CommandLine(new MainCommand()).execute("gateway", "--status");
            assertEquals(0, exitCode);
        } finally {
            System.setOut(out);
        }
        assertTrue(baos.toString().contains("Gateway"));
    }
}
