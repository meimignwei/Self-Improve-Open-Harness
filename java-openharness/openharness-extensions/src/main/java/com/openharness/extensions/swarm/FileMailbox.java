package com.openharness.extensions.swarm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.OpenHarnessObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * File-based mailbox for agent-to-agent communication.
 * Java equivalent of Python swarm/mailbox.py TeammateMailbox + MailboxMessage.
 */
public class FileMailbox {

    private static final Logger logger = LoggerFactory.getLogger(FileMailbox.class);
    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    private final String teamName;
    private final String agentId;
    private final Path mailboxDir;

    /**
     * Create a mailbox for a specific agent in a team.
     */
    public FileMailbox(String teamName, String agentId) {
        this.teamName = teamName;
        this.agentId = agentId;
        this.mailboxDir = getAgentMailboxDir(teamName, agentId).resolve("messages");
        try {
            Files.createDirectories(mailboxDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create mailbox dir: " + mailboxDir, e);
        }
    }

    public Path dir() {
        return mailboxDir;
    }

    /**
     * Write a message to the mailbox.
     */
    public void write(MailboxMessage message) {
        String filename = message.id + ".json";
        Path msgFile = mailboxDir.resolve(filename);
        try {
            MAPPER.writeValue(msgFile.toFile(), message);
            logger.debug("[FileMailbox] {} wrote message {} to {}", agentId, message.id, msgFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write mailbox message: " + msgFile, e);
        }
    }

    /**
     * Read all messages, optionally only unread ones.
     */
    public List<MailboxMessage> readAll(boolean unreadOnly) {
        List<MailboxMessage> messages = new ArrayList<>();
        if (!Files.exists(mailboxDir)) return messages;

        try (Stream<Path> files = Files.list(mailboxDir)) {
            files.filter(f -> f.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(f -> {
                        try {
                            MailboxMessage msg = MAPPER.readValue(f.toFile(), MailboxMessage.class);
                            if (!unreadOnly || !msg.read) {
                                messages.add(msg);
                            }
                        } catch (IOException e) {
                            logger.warn("[FileMailbox] Failed to read message file: {}", f, e);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("Failed to list mailbox dir: " + mailboxDir, e);
        }
        return messages;
    }

    public List<MailboxMessage> readAll() {
        return readAll(false);
    }

    /**
     * Mark a message as read.
     */
    public void markRead(String messageId) {
        Path msgFile = mailboxDir.resolve(messageId + ".json");
        if (!Files.exists(msgFile)) return;
        try {
            MailboxMessage msg = MAPPER.readValue(msgFile.toFile(), MailboxMessage.class);
            msg.read = true;
            MAPPER.writeValue(msgFile.toFile(), msg);
        } catch (IOException e) {
            logger.warn("[FileMailbox] Failed to mark message {} as read", messageId, e);
        }
    }

    /**
     * Get the count of pending (unread) messages.
     */
    public int getPendingCount() {
        if (!Files.exists(mailboxDir)) return 0;
        try (Stream<Path> files = Files.list(mailboxDir)) {
            return (int) files.filter(f -> f.toString().endsWith(".json"))
                    .filter(f -> {
                        try {
                            MailboxMessage msg = MAPPER.readValue(f.toFile(), MailboxMessage.class);
                            return !msg.read;
                        } catch (IOException e) {
                            return false;
                        }
                    }).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Delete all messages from this mailbox.
     */
    public void cleanup() {
        if (!Files.exists(mailboxDir)) return;
        try (Stream<Path> files = Files.list(mailboxDir)) {
            files.filter(f -> f.toString().endsWith(".json"))
                    .forEach(f -> {
                        try {
                            Files.deleteIfExists(f);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            logger.warn("[FileMailbox] Failed to clean up mailbox dir: {}", mailboxDir, e);
        }
    }

    // ------------------------------------------------------------------
    // Static helpers (Python create_*_notification functions)
    // ------------------------------------------------------------------

    public static MailboxMessage createIdleNotification(String sender, String recipient, String summary) {
        return new MailboxMessage(
                UUID.randomUUID().toString(),
                "idle_notification",
                sender,
                recipient,
                Map.of("summary", summary));
    }

    public static MailboxMessage createShutdownRequest(String sender, String recipient) {
        return new MailboxMessage(
                UUID.randomUUID().toString(),
                "shutdown",
                sender,
                recipient,
                Map.of("reason", "shutdown requested"));
    }

    public static MailboxMessage createUserMessage(String sender, String recipient, String content) {
        return new MailboxMessage(
                UUID.randomUUID().toString(),
                "user_message",
                sender,
                recipient,
                Map.of("content", content));
    }

    // ------------------------------------------------------------------
    // Path helpers (Python get_agent_mailbox_dir / get_team_dir)
    // ------------------------------------------------------------------

    public static Path getAgentMailboxDir(String teamName, String agentId) {
        return getTeamDir(teamName).resolve(agentId);
    }

    public static Path getTeamDir(String teamName) {
        return Path.of(System.getProperty("user.home"), ".openharness", "teams", teamName);
    }

    // ------------------------------------------------------------------
    // MailboxMessage (Python MailboxMessage dataclass)
    // ------------------------------------------------------------------

    public static class MailboxMessage {
        @JsonProperty("id")
        public String id;

        @JsonProperty("type")
        public String type;

        @JsonProperty("sender")
        public String sender;

        @JsonProperty("recipient")
        public String recipient;

        @JsonProperty("payload")
        public Object payload;

        @JsonProperty("timestamp")
        public double timestamp;

        @JsonProperty("read")
        public boolean read;

        public MailboxMessage() {}

        public MailboxMessage(String id, String type, String sender, String recipient,
                              Object payload) {
            this.id = id;
            this.type = type;
            this.sender = sender;
            this.recipient = recipient;
            this.payload = payload;
            this.timestamp = System.currentTimeMillis() / 1000.0;
            this.read = false;
        }

        public MailboxMessage(String id, String type, String sender, String recipient,
                              Object payload, double timestamp) {
            this.id = id;
            this.type = type;
            this.sender = sender;
            this.recipient = recipient;
            this.payload = payload;
            this.timestamp = timestamp;
            this.read = false;
        }

        public String getContent() {
            if (payload instanceof Map<?, ?> m) {
                Object content = m.get("content");
                return content != null ? content.toString() : "";
            }
            return payload != null ? payload.toString() : "";
        }
    }
}
