package com.storynpcs.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcItemStack;
import com.storynpcs.domain.quest.QuestMailStore;
import com.storynpcs.persistence.ProgressionRepository;
import com.storynpcs.yaml.DefinitionRegistry;

/**
 * Regression tests for the quest-reward/mail inventory-overflow remainder
 * contract. {@code Inventory.add} leaves the uninserted remainder in the stack
 * when it returns false — overflow mail must carry only that remainder, so the
 * placed portion is never duplicated and the remainder is never truncated or
 * silently destroyed.
 */
class QuestMailRemainderTest {

    @TempDir
    Path tempDir;

    private StoryNpcsApplicationService service;

    @BeforeEach
    void setUp() {
        service = new StoryNpcsApplicationService(new DefinitionRegistry(),
                new ProgressionRepository(tempDir.resolve("progression")), new EventPublisher());
    }

    @Test
    void remainderMailItems_preservesFullRemainder_inMaxCountChunks() {
        var itemId = NamespacedId.of("minecraft:diamond");
        var items = StoryNpcsApplicationService.remainderMailItems(itemId, 250, "");
        // 99 + 99 + 52 — an over-large remainder is chunked, never truncated.
        assertThat(items).hasSize(3);
        assertThat(items.stream().mapToInt(NpcItemStack::count).sum()).isEqualTo(250);
        assertThat(items).allSatisfy(i -> {
            assertThat(i.count()).isBetween(1, NpcItemStack.MAX_COUNT);
            assertThat(i.itemId()).isEqualTo(itemId);
        });
        assertThat(StoryNpcsApplicationService.remainderMailItems(itemId, 0, "")).isEmpty();
        assertThat(StoryNpcsApplicationService.remainderMailItems(itemId, -5, "")).isEmpty();
    }

    @Test
    void enqueueItemRemainderMail_persistsOnlyRemainder_withNoExperience() throws IOException {
        var mailStore = new QuestMailStore(tempDir.resolve("remainder_mail"),
                new com.fasterxml.jackson.databind.ObjectMapper());
        mailStore.open();
        service.setQuestMailStore(mailStore);
        UUID player = UUID.randomUUID();
        var questId = NamespacedId.of("storynpcs:reward_quest");
        var itemId = NamespacedId.of("minecraft:emerald");

        // Simulates Inventory.add placing part of a 64-item reward: the mail
        // must carry only the 30-item remainder, never the authored amount.
        service.enqueueItemRemainderMail(player, questId,
                StoryNpcsApplicationService.remainderMailItems(itemId, 30, ""), 42L);

        var pending = mailStore.pendingFor(player);
        assertThat(pending).hasSize(1);
        var mail = pending.get(0);
        assertThat(mail.items()).hasSize(1);
        assertThat(mail.items().get(0).itemId()).isEqualTo(itemId);
        assertThat(mail.items().get(0).count()).isEqualTo(30);
        // XP rides the delivery path itself — re-queued mail must not double-grant it.
        assertThat(mail.experience()).isZero();
        assertThat(mail.reason()).isEqualTo("INVENTORY_FULL");
        assertThat(mail.claimed()).isFalse();
    }

    @Test
    void enqueueItemRemainderMail_emptyRemainderWritesNoPhantomMail() throws IOException {
        var mailStore = new QuestMailStore(tempDir.resolve("remainder_mail_empty"),
                new com.fasterxml.jackson.databind.ObjectMapper());
        mailStore.open();
        service.setQuestMailStore(mailStore);
        UUID player = UUID.randomUUID();

        service.enqueueItemRemainderMail(player, NamespacedId.of("storynpcs:q"),
                List.of(), 1L);
        service.enqueueItemRemainderMail(player, NamespacedId.of("storynpcs:q"), null, 1L);

        assertThat(mailStore.pendingFor(player)).isEmpty();
    }

    @Test
    void enqueueItemRemainderMail_withoutMailStoreIsNoOp() throws IOException {
        // No store wired: the no-op must not throw — callers that require the
        // store check it themselves (deliverQuestReward throws FAIL first).
        service.enqueueItemRemainderMail(UUID.randomUUID(), NamespacedId.of("storynpcs:q"),
                List.of(NpcItemStack.of(NamespacedId.of("minecraft:diamond"), 5, "")), 1L);
    }
}
