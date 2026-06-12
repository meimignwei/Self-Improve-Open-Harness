package com.openharness.permissions;

/**
 * A glob-based path permission rule.
 * Java equivalent of Python's PathRule dataclass.
 */
public record PathRule(String pattern, boolean allow) {

    public static final PathRule[] SENSITIVE_PATHS = {
            new PathRule("*/.ssh/*", false),
            new PathRule("*/.aws/credentials", false),
            new PathRule("*/.aws/config", false),
            new PathRule("*/.config/gcloud/*", false),
            new PathRule("*/.azure/*", false),
            new PathRule("*/.gnupg/*", false),
            new PathRule("*/.docker/config.json", false),
            new PathRule("*/.kube/config", false),
            new PathRule("*/.openharness/credentials.json", false),
            new PathRule("*/.openharness/copilot_auth.json", false),
    };
}
