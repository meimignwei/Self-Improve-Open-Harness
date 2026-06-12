package com.openharness.extensions.swarm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.OpenHarnessObjectMapper;
import com.openharness.config.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * File-based mailbox for agent-to-agent communication.
 * Agents exchange messages by writing/reading JSON files in a shared directory.
 * Java equivalent of Python swarm/mailbox.py.
 */
public class FileMailbox {

    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    private final Path mailboxDir;

    public FileMailbox(Path mailboxDir) {
        this.mailboxDir = mailboxDir;
        try {
            Files.createDirectories(mailboxDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create mailbox dir: " + mailboxDir, e);
        }
    }

    public Path dir() {
        return mailboxDir;
    }

    public void send(String recipientId, MailboxMessage message) {
        String filename = recipientId + "_" + UUID.randomUUID() + ".json";
        Path msgFile = mailboxDir.resolve(filename);
        AtomicFileWriter.writeJson(msgFile, message);
    }

    public List<MailboxMessage> receive(String recipientId) {
        List<MailboxMessage> messages = new ArrayList<>();
        try (var files = Files.newDirectoryStream(mailboxDir, recipientId + "_*.json")) {
            for (Path f : files) {
                try {
                    MailboxMessage msg = MAPPER.readValue(f.toFile(), MailboxMessage.class);
                    messages.add(msg);
                    Files.delete(f);
                } catch (IOException e) {
                    System.err.println("Failed to read mailbox message: " + f + " - " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to receive messages for: " + recipientId, e);
        }
        return messages;
    }

    public int pendingCount(String recipientId) {
        try (var files = Files.newDirectoryStream(mailboxDir, recipientId + "_*.json")) {
            int count = 0;
            for (var ignored : files) count++;
            return count;
        } catch (IOException e) {
            return 0;
        }
    }

    public void cleanup() {
        try (var files = Files.newDirectoryStream(mailboxDir, "*.json")) {
            for (Path f : files) {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    /**
     * Message envelope for agent-to-agent communication.
     */
    public record MailboxMessage(
            String senderId,
            String recipientId,
            String type,
            JsonNode payload,
            Instant timestamp
    ) {
        public MailboxMessage {
            if (timestamp == null) timestamp = Instant.now();
        }

        public static MailboxMessage of(String senderId, String recipientId,
                                         String type, JsonNode payload) {
            return new MailboxMessage(senderId, recipientId, type, payload, Instant.now());
        }
    }
}
