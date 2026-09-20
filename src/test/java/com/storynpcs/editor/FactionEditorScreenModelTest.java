package com.storynpcs.editor;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.domain.faction.FactionSerde;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FactionEditorScreenModelTest {

    private FactionEditorScreenModel model;

    @BeforeEach
    void setUp() {
        model = new FactionEditorScreenModel();
        model.loadFactions(List.of(
                new Faction(NamespacedId.of("storynpcs:town_guard"), "Town Guard", 1000, 400, 1600),
                new Faction(NamespacedId.of("storynpcs:river_pirates"), "River Pirates", 800, 300, 1200)));
    }

    @Test
    void listModeSortsFactions() {
        assertThat(model.getMode()).isEqualTo(FactionEditorScreenModel.Mode.LIST);
        assertThat(model.factionCount()).isEqualTo(2);
        assertThat(model.getFactions().get(0).getId().toString()).isEqualTo("storynpcs:river_pirates");
    }

    @Test
    void beginEditIsolatesTheWorkingCopy() {
        assertThat(model.beginEdit(NamespacedId.of("storynpcs:town_guard"))).isTrue();
        model.setName("Renamed Guard");
        assertThat(model.isDirty()).isTrue();
        assertThat(model.getFactions().get(1).getName()).isEqualTo("Town Guard");
    }

    @Test
    void beginNewScaffoldsDomainDefaults() {
        assertThat(model.beginNew(NamespacedId.of("storynpcs:new_f"), "New F")).isTrue();
        Faction f = model.getEditing();
        assertThat(f.getDefaultPoints()).isEqualTo(1000);
        assertThat(f.getHostileThreshold()).isEqualTo(500);
        assertThat(f.getFriendlyThreshold()).isEqualTo(1500);

        model.backToList();
        assertThat(model.beginNew(NamespacedId.of("storynpcs:town_guard"), "Dup")).isFalse();
        assertThat(model.isStatusError()).isTrue();
    }

    @Test
    void validateForSaveEnforcesThresholdConsistency() {
        model.beginEdit(NamespacedId.of("storynpcs:town_guard"));
        assertThat(model.validateForSave()).isNull();

        model.setFriendlyThreshold(300); // inverts hostile(400) < friendly
        assertThat(model.validateForSave()).contains("below friendly");

        model.setFriendlyThreshold(1600);
        assertThat(model.validateForSave()).isNull();
    }

    @Test
    void saveJsonRoundTripsWorkingCopy() {
        model.beginEdit(NamespacedId.of("storynpcs:river_pirates"));
        model.setHostileThreshold(250);
        Faction back = FactionSerde.fromJson(model.saveJson()).orElseThrow();
        assertThat(back.getHostileThreshold()).isEqualTo(250);
        assertThat(back.getId().toString()).isEqualTo("storynpcs:river_pirates");
    }

    @Test
    void deleteIsTwoClickAndResultDropsToList() {
        model.beginEdit(NamespacedId.of("storynpcs:town_guard"));
        assertThat(model.confirmDeleteClick()).isFalse();
        assertThat(model.confirmDeleteClick()).isTrue();

        model.onSaveResult(true, "Faction deleted.", List.of(
                new Faction(NamespacedId.of("storynpcs:river_pirates"), "River Pirates", 800, 300, 1200)));
        assertThat(model.getMode()).isEqualTo(FactionEditorScreenModel.Mode.LIST);
        assertThat(model.factionCount()).isEqualTo(1);
    }
}
