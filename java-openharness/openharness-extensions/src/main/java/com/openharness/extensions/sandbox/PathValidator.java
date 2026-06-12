package com.openharness.extensions.sandbox;

import java.nio.file.Path;
import java.util.Set;

/**
 * Validates file access against sandbox boundaries.
 * Java equivalent of Python sandbox/path_validator.py.
 */
public class PathValidator {

    private final Set<Path> allowedReadPaths;
    private final Set<Path> allowedWritePaths;

    public PathValidator(Set<Path> allowedReadPaths, Set<Path> allowedWritePaths) {
        this.allowedReadPaths = Set.copyOf(allowedReadPaths);
        this.allowedWritePaths = Set.copyOf(allowedWritePaths);
    }

    public void validate(Path targetPath, FileOperation operation) {
        Set<Path> allowed = operation == FileOperation.READ
                ? allowedReadPaths : allowedWritePaths;

        Path absolute = targetPath.toAbsolutePath().normalize();
        boolean within = allowed.stream().anyMatch(allowedPath ->
                absolute.startsWith(allowedPath.toAbsolutePath()));

        if (!within) {
            throw new SandboxPathViolation(targetPath, operation);
        }
    }

    public void validateRead(Path targetPath) {
        validate(targetPath, FileOperation.READ);
    }

    public void validateWrite(Path targetPath) {
        validate(targetPath, FileOperation.WRITE);
    }

    public enum FileOperation { READ, WRITE }

    public static class SandboxPathViolation extends SecurityException {
        private final Path path;
        private final FileOperation operation;

        public SandboxPathViolation(Path path, FileOperation operation) {
            super("Sandbox path violation: " + operation + " " + path);
            this.path = path;
            this.operation = operation;
        }

        public Path path() { return path; }
        public FileOperation operation() { return operation; }
    }
}
