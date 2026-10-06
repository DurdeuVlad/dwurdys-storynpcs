package com.storynpcs.entity;

import net.minecraft.nbt.CompoundTag;

/**
 * ADR-007 ModRev-equivalent (issue #122): every NPC NBT save carries a data
 * revision so future format changes can distinguish pre-change saves during
 * migration. The target writes {@code ModRev: 18}; StoryNPCs writes
 * {@code StoryNpcsRev: 1} and bumps {@link #CURRENT} on any breaking
 * persistence-format change.
 */
public final class EntityDataRevision {

    public static final String KEY = "StoryNpcsRev";
    public static final int CURRENT = 1;
    /** Revision carried by saves written before the marker existed. */
    public static final int PRE_MARKER = 0;

    private EntityDataRevision() {}

    public static void write(CompoundTag tag) {
        tag.putInt(KEY, CURRENT);
    }

    /** Read the stored revision; pre-marker saves read as {@link #PRE_MARKER}. */
    public static int read(CompoundTag tag) {
        return tag.contains(KEY) ? tag.getInt(KEY) : PRE_MARKER;
    }
}
