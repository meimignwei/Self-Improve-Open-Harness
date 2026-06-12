package com.openharness.extensions.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * AST-based code intelligence: symbol search, go-to-definition, find references.
 * Java equivalent of Python services/lsp/__init__.py.
 */
public class CodeIntelligence {

    private static final Pattern SYMBOL_PATTERN = Pattern.compile(
            "^(?:public |private |protected |static |final |abstract )*"
                    + "(?:class|interface|enum|record)\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern DEF_PATTERN = Pattern.compile(
            "(?:def|function|func|fn)\\s+(\\w+)");
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "^\\s*(?:public |private |protected |static |final )*"
                    + "(?:void |\\w+(?:<[^>]+>)?\\s+)?(\\w+)\\s*\\([^)]*\\)\\s*(?:throws|\\{)", Pattern.MULTILINE);

    private final Path workspaceRoot;

    public CodeIntelligence(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public List<SymbolLocation> searchSymbols(String query) {
        String lower = query.toLowerCase();
        List<SymbolLocation> results = new ArrayList<>();
        for (Path file : iterSourceFiles()) {
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                extractSymbols(content, file).stream()
                        .filter(s -> s.name().toLowerCase().contains(lower))
                        .forEach(results::add);
            } catch (IOException ignored) {}
        }
        return results;
    }

    public Optional<SymbolLocation> goToDefinition(String name) {
        for (Path file : iterSourceFiles()) {
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                Optional<SymbolLocation> found = extractSymbols(content, file).stream()
                        .filter(s -> s.name().equals(name)).findFirst();
                if (found.isPresent()) return found;
            } catch (IOException ignored) {}
        }
        return Optional.empty();
    }

    public List<SymbolLocation> findReferences(String name) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
        List<SymbolLocation> results = new ArrayList<>();
        for (Path file : iterSourceFiles()) {
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                int lineNum = 0;
                for (String line : content.lines().toList()) {
                    lineNum++;
                    Matcher m = p.matcher(line);
                    while (m.find()) {
                        results.add(new SymbolLocation(name, "reference",
                                file, lineNum, m.start(), "", ""));
                    }
                }
            } catch (IOException ignored) {}
        }
        return results;
    }

    private List<SymbolLocation> extractSymbols(String source, Path file) {
        List<SymbolLocation> symbols = new ArrayList<>();

        extractPattern(SYMBOL_PATTERN, "class", source, file, symbols);
        extractPattern(METHOD_PATTERN, "method", source, file, symbols);
        extractPattern(DEF_PATTERN, "function", source, file, symbols);

        return symbols;
    }

    private void extractPattern(Pattern pattern, String kind, String source,
                                 Path file, List<SymbolLocation> symbols) {
        Matcher m = pattern.matcher(source);
        while (m.find()) {
            String name = m.group(1);
            int pos = m.start();
            int line = source.substring(0, pos).split("\n", -1).length;
            symbols.add(new SymbolLocation(name, kind, file, line, 0, "", ""));
        }
    }

    private List<Path> iterSourceFiles() {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(workspaceRoot)) {
            s.filter(f -> {
                String name = f.getFileName().toString();
                return name.matches(".*\\.(java|py|ts|tsx|js|go|rs|kt)$");
            })
                    .filter(f -> {
                        String path = f.toString();
                        return !path.contains("/target/") && !path.contains("/node_modules/")
                                && !path.contains("/venv/") && !path.contains("/.git/");
                    })
                    .forEach(files::add);
        } catch (IOException ignored) {}
        return files;
    }

    public record SymbolLocation(String name, String kind, Path path,
                                  int line, int character, String signature, String docstring) {}
}
