package com.storynpcs.domain.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandParityEntryTest {

    @Test
    void supportedEntryRequiresAnEquivalent() {
        assertThatThrownBy(() -> new CommandParityEntry(
                "target.commands.0001", "/noppes/x", "op",
                CommandParityStatus.SUPPORTED, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new CommandParityEntry(
                "target.commands.0001", "/noppes/x", "op",
                CommandParityStatus.SUPPORTED, "   ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supportedEntryWithAnEquivalentIsAccepted() {
        CommandParityEntry entry = new CommandParityEntry(
                "target.commands.0001", "/noppes/x", "op",
                CommandParityStatus.SUPPORTED, "/storynpcs x", null);
        assertThat(entry.getStorynpcsEquivalent()).isEqualTo("/storynpcs x");
        assertThat(entry.getRationale()).isNull();
    }

    @Test
    void unverifiedEntryRequiresARationale() {
        assertThatThrownBy(() -> new CommandParityEntry(
                "target.commands.0001", "/noppes/x", "op",
                CommandParityStatus.UNVERIFIED, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new CommandParityEntry(
                "target.commands.0001", "/noppes/x", "op",
                CommandParityStatus.UNVERIFIED, null, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void intentionalDeviationRequiresARationale() {
        assertThatThrownBy(() -> new CommandParityEntry(
                "target.commands.0001", "/noppes/x", "op",
                CommandParityStatus.INTENTIONAL_DEVIATION, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unverifiedEntryWithARationaleIsAccepted() {
        CommandParityEntry entry = new CommandParityEntry(
                "target.commands.0001", "/noppes/x", "op",
                CommandParityStatus.UNVERIFIED, null, "no equivalent exists yet");
        assertThat(entry.getRationale()).isEqualTo("no equivalent exists yet");
        assertThat(entry.getStorynpcsEquivalent()).isNull();
    }

    @Test
    void blankRequiredFieldsAreRejected() {
        assertThatThrownBy(() -> new CommandParityEntry(
                "", "/noppes/x", "op", CommandParityStatus.UNVERIFIED, null, "reason"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandParityEntry(
                "target.commands.0001", "", "op", CommandParityStatus.UNVERIFIED, null, "reason"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandParityEntry(
                "target.commands.0001", "/noppes/x", "", CommandParityStatus.UNVERIFIED, null, "reason"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
