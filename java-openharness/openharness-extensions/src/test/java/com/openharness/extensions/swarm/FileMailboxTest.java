package com.openharness.extensions.swarm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileMailboxTest {

    private static String uniqueTeam() { return "ft-team-" + System.nanoTime(); }
    private static String uniqueAgent() { return "ft-agent-" + System.nanoTime(); }

    @Test
    void writeShouldNotThrow() {
        var mailbox = new FileMailbox(uniqueTeam(), uniqueAgent());
        var msg = FileMailbox.createUserMessage("sender", "recipient", "Hello");
        assertDoesNotThrow(() -> mailbox.write(msg));
    }

    @Test
    void readAllShouldReturnWrittenMessages() {
        var mailbox = new FileMailbox(uniqueTeam(), uniqueAgent());
        mailbox.write(FileMailbox.createUserMessage("sender", "recipient", "Message A"));
        mailbox.write(FileMailbox.createUserMessage("sender", "recipient", "Message B"));

        var messages = mailbox.readAll(true);
        assertEquals(2, messages.size());
    }

    @Test
    void markReadShouldHideFromUnreadQueries() {
        var mailbox = new FileMailbox(uniqueTeam(), uniqueAgent());
        var msg = FileMailbox.createUserMessage("sender", "recipient", "Test");
        mailbox.write(msg);

        var before = mailbox.readAll(true);
        assertEquals(1, before.size());

        mailbox.markRead(before.getFirst().id);

        var after = mailbox.readAll(true);
        assertTrue(after.isEmpty());
    }

    @Test
    void createUserMessageShouldHaveCorrectFields() {
        var msg = FileMailbox.createUserMessage("from-agent", "to-agent", "Hello world");

        assertEquals("user_message", msg.type);
        assertEquals("from-agent", msg.sender);
        assertEquals("to-agent", msg.recipient);
        assertTrue(msg.getContent().contains("Hello world"));
        assertNotNull(msg.id);
    }

    @Test
    void createShutdownRequestShouldHaveCorrectType() {
        var msg = FileMailbox.createShutdownRequest("from-agent", "to-agent");
        assertEquals("shutdown", msg.type);
        assertEquals("from-agent", msg.sender);
    }

    @Test
    void createIdleNotificationShouldHaveCorrectType() {
        var msg = FileMailbox.createIdleNotification("from-agent", "to-agent", "Task completed");
        assertEquals("idle_notification", msg.type);
        assertEquals("from-agent", msg.sender);
    }
}
