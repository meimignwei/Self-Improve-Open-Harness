package io.openharness.unit;

import io.openharness.cli.OhMain;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class OhMainTest {

    @Test
    void shouldParseModelOption() {
        OhMain app = new OhMain();
        CommandLine cmd = new CommandLine(app);
        cmd.parseArgs("-m", "sonnet");
        assertThat(app.getModel()).isEqualTo("sonnet");

        cmd.parseArgs("--model", "opus");
        assertThat(app.getModel()).isEqualTo("opus");
    }

    @Test
    void shouldParseContinueFlag() {
        OhMain app = new OhMain();
        CommandLine cmd = new CommandLine(app);
        cmd.parseArgs("-c");
        assertThat(app.isContinueLast()).isTrue();

        app = new OhMain();
        cmd = new CommandLine(app);
        cmd.parseArgs("--continue");
        assertThat(app.isContinueLast()).isTrue();
    }

    @Test
    void shouldParsePromptOption() {
        OhMain app = new OhMain();
        CommandLine cmd = new CommandLine(app);
        cmd.parseArgs("-p", "Hello, world!");
        assertThat(app.getInitialPrompt()).isEqualTo("Hello, world!");
    }

    @Test
    void shouldParseLogLevelOption() {
        OhMain app = new OhMain();
        CommandLine cmd = new CommandLine(app);
        cmd.parseArgs("--log-level", "DEBUG");
        assertThat(app.getLogLevel()).isEqualTo("DEBUG");

        app = new OhMain();
        cmd = new CommandLine(app);
        cmd.parseArgs();
        assertThat(app.getLogLevel()).isEqualTo("INFO");
    }
}
