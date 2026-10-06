package com.storynpcs.runtime.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import com.storynpcs.api.event.EventPublisher;
import com.storynpcs.api.event.P85OrchestrationEvents.SceneLifecycleEvent;
import com.storynpcs.creator.scene.SceneDefinition;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.entity.StoryNpcEntity;
import com.storynpcs.service.StoryNpcsApplicationService;
import com.storynpcs.yaml.DefinitionRegistry;

/**
 * Bounded scene sessions (P8-5). A scene spawns participants from templates
 * through the canonical create path (same as the spawner driver), advances
 * its stage markers in game ticks, and always terminates: natural end,
 * {@code maxDurationTicks} budget exhaustion, explicit cancel, or world
 * unload — each resolved through the definition's {@code CancelRecovery}.
 */
public final class SceneRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(SceneRuntime.class);

    private final Supplier<DefinitionRegistry> registry;
    private final Supplier<StoryNpcsApplicationService> service;
    private final Supplier<EventPublisher> events;
    private final Map<NamespacedId, Active> active = new LinkedHashMap<>();

    /** One participant's origin for RESTORE_POSITIONS recovery. */
    private record Participant(UUID uuid, double ox, double oy, double oz) {}

    private static final class Active {
        final SceneDefinition def;
        final ServerLevel level;
        final long startTick;
        final List<Participant> participants = new ArrayList<>();
        int stageCursor = -1;
        long stageBoundary;

        Active(SceneDefinition def, ServerLevel level, long startTick) {
            this.def = def;
            this.level = level;
            this.startTick = startTick;
        }
    }

    public SceneRuntime(Supplier<DefinitionRegistry> registry,
            Supplier<StoryNpcsApplicationService> service,
            Supplier<EventPublisher> events) {
        this.registry = registry;
        this.service = service;
        this.events = events;
    }

    public enum StartOutcome {
        STARTED, MISSING_TEMPLATE, OVER_BUDGET, ALREADY_RUNNING, SPAWN_FAILED
    }

    public record StartResult(StartOutcome outcome, NamespacedId missingTemplate) {
        public boolean started() {
            return outcome == StartOutcome.STARTED;
        }
    }

    /** Spawn participants, register the session, play stage 0. */
    public StartResult start(SceneDefinition def, ServerLevel level,
                             BlockPos center, long nowTick) {
        if (active.containsKey(def.getId())) {
            return new StartResult(StartOutcome.ALREADY_RUNNING, null);
        }
        var svc = service.get();
        if (svc == null) {
            return new StartResult(StartOutcome.SPAWN_FAILED, null);
        }
        if (def.getParticipantTemplateIds().size() > def.getMaxEntities()) {
            return new StartResult(StartOutcome.OVER_BUDGET, null);
        }
        var session = new Active(def, level, nowTick);
        int idx = 0;
        for (NamespacedId templateId : def.getParticipantTemplateIds()) {
            var template = registry.get().getTemplate(templateId).orElse(null);
            if (template == null || template.getDefinition() == null) {
                rollbackSpawned(session);
                return new StartResult(StartOutcome.MISSING_TEMPLATE, templateId);
            }
            NamespacedId defId = NamespacedId.of("storynpcs",
                    "scene/" + def.getId().getPath().replace('/', '_') + "_" + idx);
            if (registry.get().getNpc(defId).isEmpty()) {
                var definition = template.instantiate(defId);
                var request = new com.storynpcs.service.MutationRequest(
                        "npc.create", "system", "npc.mutate", defId,
                        svc.currentRevision("npc", defId), UUID.randomUUID());
                var result = svc.createNpc(request, definition);
                if (result != null && !result.applied()) {
                    LOGGER.warn("Scene {} participant create rejected: {}",
                            def.getId(), result.formatReport(5));
                    rollbackSpawned(session);
                    return new StartResult(StartOutcome.SPAWN_FAILED, templateId);
                }
            }
            var entity = com.storynpcs.entity.StoryNpcRegistry.STORY_NPC.get().create(level);
            if (entity == null || !place(entity, level, defId, center, idx)) {
                rollbackSpawned(session);
                return new StartResult(StartOutcome.SPAWN_FAILED, templateId);
            }
            session.participants.add(new Participant(entity.getUUID(),
                    center.getX() + 0.5, center.getY(), center.getZ() + 0.5));
            idx++;
        }
        active.put(def.getId(), session);
        publish(def.getId(), SceneLifecycleEvent.Status.STARTED,
                session.participants.size() + " participants");
        if (!def.getStages().isEmpty()) {
            advanceStage(session, nowTick);
        }
        return new StartResult(StartOutcome.STARTED, null);
    }

    private boolean place(StoryNpcEntity entity, ServerLevel level,
                          NamespacedId defId, BlockPos center, int idx) {
        entity.setDefinitionId(defId.toString());
        var pos = center.offset((idx % 3) - 1, 0, (idx / 3) % 3 - 1);
        entity.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        entity.getPersistentData().putString("storynpcs:scene_owner", defId.toString());
        return level.addFreshEntity(entity);
    }

    /** Advance stages each tick; end or budget exhaustion finishes the scene. */
    public int tick(ServerLevel level, long nowTick) {
        int advanced = 0;
        for (var e : new ArrayList<>(active.entrySet())) {
            var session = e.getValue();
            if (!session.level.equals(level)) {
                continue;
            }
            if (nowTick - session.startTick >= session.def.getMaxDurationTicks()) {
                cancel(e.getKey(), "duration budget exceeded");
                advanced++;
                continue;
            }
            if (session.stageCursor >= 0 && nowTick >= session.stageBoundary) {
                if (session.stageCursor + 1 >= session.def.getStages().size()) {
                    finish(e.getKey());
                } else {
                    advanceStage(session, nowTick);
                }
                advanced++;
            }
        }
        return advanced;
    }

    private void advanceStage(Active session, long nowTick) {
        session.stageCursor++;
        var stage = session.def.getStages().get(session.stageCursor);
        session.stageBoundary = nowTick + stage.durationTicks();
        publish(session.def.getId(), SceneLifecycleEvent.Status.STAGE,
                stage.name() + (stage.cueText() == null ? "" : ": " + stage.cueText()));
    }

    /** Natural completion — participants stay in the world. */
    private void finish(NamespacedId sceneId) {
        active.remove(sceneId);
        publish(sceneId, SceneLifecycleEvent.Status.COMPLETED, null);
    }

    /** Cancel with the definition's recovery policy; returns false if not running. */
    public boolean cancel(NamespacedId sceneId, String reason) {
        var session = active.remove(sceneId);
        if (session == null) {
            return false;
        }
        recover(session);
        publish(sceneId, SceneLifecycleEvent.Status.CANCELLED, reason);
        return true;
    }

    /** World unload — cancel every session in that level with recovery. */
    /** Cancel every active session whose level matches; {@code null} cancels all. */
    public int cancelAll(ServerLevel level) {
        int cancelled = 0;
        for (var e : new ArrayList<>(active.entrySet())) {
            if ((level == null || e.getValue().level.equals(level))
                    && cancel(e.getKey(), "world unload")) {
                cancelled++;
            }
        }
        return cancelled;
    }

    private void recover(Active session) {
        switch (session.def.getCancelRecovery()) {
            case LEAVE_IN_PLACE -> { /* participants keep their current state */ }
            case RESTORE_POSITIONS -> {
                for (var p : session.participants) {
                    var entity = session.level.getEntity(p.uuid());
                    if (entity != null && !entity.isRemoved()) {
                        entity.teleportTo(p.ox(), p.oy(), p.oz());
                    }
                }
            }
            case RESPAWN_PRISTINE -> {
                for (var p : session.participants) {
                    var entity = session.level.getEntity(p.uuid());
                    if (entity != null && !entity.isRemoved()) {
                        entity.teleportTo(p.ox(), p.oy(), p.oz());
                        if (entity instanceof StoryNpcEntity npc) {
                            npc.setHealth(npc.getMaxHealth());
                        }
                    }
                }
            }
        }
    }

    /** Despawn anything already spawned when a start fails mid-loop. */
    private void rollbackSpawned(Active session) {
        for (var p : session.participants) {
            Entity entity = session.level.getEntity(p.uuid());
            if (entity != null) {
                entity.discard();
            }
        }
    }

    public boolean isRunning(NamespacedId sceneId) {
        return active.containsKey(sceneId);
    }

    public int activeCount() {
        return active.size();
    }

    private void publish(NamespacedId sceneId, SceneLifecycleEvent.Status status, String detail) {
        var pub = events.get();
        if (pub != null) {
            pub.publish(new SceneLifecycleEvent(sceneId, status, detail));
        }
    }
}
