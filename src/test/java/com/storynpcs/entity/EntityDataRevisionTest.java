package com.storynpcs.entity;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntityDataRevisionTest {

    @Test
    void writeThenReadRoundTripsCurrentRevision() {
        CompoundTag tag = new CompoundTag();
        EntityDataRevision.write(tag);

        assertThat(tag.contains(EntityDataRevision.KEY)).isTrue();
        assertThat(tag.getInt(EntityDataRevision.KEY)).isEqualTo(EntityDataRevision.CURRENT);
        assertThat(EntityDataRevision.read(tag)).isEqualTo(EntityDataRevision.CURRENT);
    }

    @Test
    void preMarkerSaveReadsAsRevisionZero() {
        // ADR-007: saves written before the marker existed carry no key —
        // they must read as 0 so migrations can distinguish them.
        CompoundTag tag = new CompoundTag();
        tag.putString("StoryNpcDefinitionId", "storynpcs:legacy_npc");

        assertThat(EntityDataRevision.read(tag)).isEqualTo(EntityDataRevision.PRE_MARKER);
        assertThat(EntityDataRevision.PRE_MARKER).isEqualTo(0);
    }

    @Test
    void futureRevisionReadsVerbatimForForwardCompatibilityChecks() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(EntityDataRevision.KEY, EntityDataRevision.CURRENT + 5);

        assertThat(EntityDataRevision.read(tag)).isEqualTo(EntityDataRevision.CURRENT + 5);
    }
}
