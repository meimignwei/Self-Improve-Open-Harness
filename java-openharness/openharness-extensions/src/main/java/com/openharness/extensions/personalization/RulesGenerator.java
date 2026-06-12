package com.openharness.extensions.personalization;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts extracted facts into Markdown rules documentation.
 * Java equivalent of Python personalization/rules.py.
 */
public class RulesGenerator {

    public String generate(List<FactExtractor.Fact> facts) {
        StringBuilder sb = new StringBuilder("# Local Environment Rules\n\n");

        Map<String, List<FactExtractor.Fact>> grouped = facts.stream()
                .collect(Collectors.groupingBy(FactExtractor.Fact::type));

        for (var entry : grouped.entrySet()) {
            sb.append("## ").append(entry.getKey()).append("\n\n");
            for (FactExtractor.Fact fact : entry.getValue()) {
                sb.append("- ").append(fact.label()).append(": `")
                        .append(fact.value()).append("`\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
