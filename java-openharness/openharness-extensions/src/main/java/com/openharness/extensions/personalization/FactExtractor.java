package com.openharness.extensions.personalization;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts environment-specific facts from conversation text using regex patterns.
 * Java equivalent of Python personalization/extractor.py.
 */
public class FactExtractor {

    private static final List<FactPattern> PATTERNS = List.of(
            new FactPattern("ssh_host", "SSH Host", Pattern.compile("ssh\\s+\\w+@([\\w.-]+)"), 0.7),
            new FactPattern("ip_addr", "IP Address", Pattern.compile("\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\b"), 0.5),
            new FactPattern("data_path", "Data Path", Pattern.compile("/(?:data|mnt|storage)/[\\w/]+"), 0.6),
            new FactPattern("conda_env", "Conda Env", Pattern.compile("conda (?:activate|env)\\s+(\\w+)"), 0.5),
            new FactPattern("python_ver", "Python Version", Pattern.compile("Python\\s+(\\d+\\.\\d+)"), 0.8),
            new FactPattern("api_endpoint", "API Endpoint", Pattern.compile("https?://[\\w.-]+/api/[\\w/]+"), 0.6),
            new FactPattern("env_var", "Env Variable", Pattern.compile("\\$([A-Z_]{3,})"), 0.3),
            new FactPattern("git_remote", "Git Remote", Pattern.compile("git@([\\w.-]+):[\\w/]+\\.git"), 0.7),
            new FactPattern("ray_cluster", "Ray Cluster", Pattern.compile("ray://[\\w.-]+:\\d+"), 0.6),
            new FactPattern("cron_sched", "Cron Schedule", Pattern.compile("cron\\s+([*\\d,\\-/]+\\s+[*\\d,\\-/]+\\s+[*\\d,\\-/]+)"), 0.5)
    );

    public List<Fact> extract(String text) {
        List<Fact> facts = new ArrayList<>();
        for (FactPattern fp : PATTERNS) {
            Matcher m = fp.pattern().matcher(text);
            while (m.find()) {
                facts.add(new Fact(m.group(1), fp.type(), fp.label(), m.group(), fp.confidence()));
            }
        }
        return facts;
    }

    public record FactPattern(String type, String label, Pattern pattern, double confidence) {}

    public record Fact(String key, String type, String label, String value, double confidence) {}
}
