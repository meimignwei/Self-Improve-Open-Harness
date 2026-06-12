package com.openharness.extensions.voice;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Extracts key terms from transcribed speech.
 * Java equivalent of Python voice/keyterms.py.
 */
public final class KeyTerms {

    private static final Pattern WORD_PATTERN = Pattern.compile("\\b[a-zA-Z]{4,}\\b");

    public static List<String> extract(String transcript) {
        return WORD_PATTERN.matcher(transcript)
                .results()
                .map(r -> r.group().toLowerCase())
                .distinct()
                .toList();
    }

    private KeyTerms() {}
}
