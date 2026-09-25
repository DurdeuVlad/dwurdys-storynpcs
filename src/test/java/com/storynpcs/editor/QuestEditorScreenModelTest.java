package com.storynpcs.editor;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.quest.QuestObjective;
import com.storynpcs.domain.quest.QuestReward;
import com.storynpcs.domain.quest.QuestSerde;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestEditorScreenModelTest {

    private QuestEditorScreenModel model;

    private static Quest quest(String id, String title) {
        Quest q = new Quest(NamespacedId.of(id), title);
        q.getObjectives().add(new QuestObjective("objective_1",
                QuestObjective.Type.KILL_ENTITY, "minecraft:zombie", 3));
        return q;
    }

    @BeforeEach
    void setUp() {
        model = new QuestEditorScreenModel();
        model.loadQuests(List.of(
                quest("storynpcs:bounty_goblins", "Bounty: Forest Goblins"),
                quest("storynpcs:m3test", "Quest Authoring Test")));
    }

    @Test
    void listModeIsDefaultAndQuestsAreSorted() {
        assertThat(model.getMode()).isEqualTo(QuestEditorScreenModel.Mode.LIST);
        assertThat(model.questCount()).isEqualTo(2);
        assertThat(model.getQuests().get(0).getId().toString()).isEqualTo("storynpcs:bounty_goblins");
    }

    @Test
    void beginEditEntersEditModeOnAnIsolatedCopy() {
        assertThat(model.beginEdit(NamespacedId.of("storynpcs:m3test"))).isTrue();
        assertThat(model.getMode()).isEqualTo(QuestEditorScreenModel.Mode.EDIT);
        assertThat(model.isDirty()).isFalse();
        assertThat(model.isEditingNew()).isFalse();

        // Mutating the working copy must not touch the synced list snapshot
        model.setTitle("Renamed");
        assertThat(model.isDirty()).isTrue();
        assertThat(model.getQuests().get(1).getTitle()).isEqualTo("Quest Authoring Test");
    }

    @Test
    void beginEditRejectsUnknownId() {
        assertThat(model.beginEdit(NamespacedId.of("storynpcs:ghost"))).isFalse();
        assertThat(model.getMode()).isEqualTo(QuestEditorScreenModel.Mode.LIST);
        assertThat(model.isStatusError()).isTrue();
    }

    @Test
    void beginNewScaffoldsPlaceholderObjectiveAndRejectsDuplicates() {
        assertThat(model.beginNew(NamespacedId.of("storynpcs:brand_new"), "Brand New")).isTrue();
        assertThat(model.isEditingNew()).isTrue();
        assertThat(model.isDirty()).isTrue();
        assertThat(model.getEditing().getObjectives()).hasSize(1);
        assertThat(model.getEditing().getObjectives().get(0).getId()).isEqualTo("objective_1");

        // Duplicate id rejected while listing
        model.backToList();
        assertThat(model.beginNew(NamespacedId.of("storynpcs:m3test"), "Dup")).isFalse();
        assertThat(model.isStatusError()).isTrue();
    }

    @Test
    void rowEditsAddUpdateAndRemoveObjectives() {
        model.beginEdit(NamespacedId.of("storynpcs:m3test"));

        // add a new objective row
        model.beginRowEdit(QuestEditorScreenModel.RowKind.OBJECTIVE, -1);
        model.applyRowEdit("COLLECT_ITEM", "minecraft:emerald", 5);
        assertThat(model.getEditing().getObjectives()).hasSize(2);
        assertThat(model.getEditing().getObjectives().get(1).getId()).isEqualTo("objective_2");
        assertThat(model.getEditing().getObjectives().get(1).getType()).isEqualTo(QuestObjective.Type.COLLECT_ITEM);

        // edit the row in place — id preserved
        model.beginRowEdit(QuestEditorScreenModel.RowKind.OBJECTIVE, 1);
        model.applyRowEdit("TALK_TO_NPC", "storynpcs:guard_captain", 1);
        assertThat(model.getEditing().getObjectives().get(1).getId()).isEqualTo("objective_2");
        assertThat(model.getEditing().getObjectives().get(1).getType()).isEqualTo(QuestObjective.Type.TALK_TO_NPC);

        // blank target rejected, list unchanged
        model.beginRowEdit(QuestEditorScreenModel.RowKind.OBJECTIVE, -1);
        model.applyRowEdit("CUSTOM", "  ", 1);
        assertThat(model.getEditing().getObjectives()).hasSize(2);
        assertThat(model.isStatusError()).isTrue();

        model.removeObjectiveRow(1);
        assertThat(model.getEditing().getObjectives()).hasSize(1);
    }

    @Test
    void rewardRowsRoundTrip() {
        model.beginEdit(NamespacedId.of("storynpcs:m3test"));
        model.beginRowEdit(QuestEditorScreenModel.RowKind.REWARD, -1);
        model.applyRowEdit("ITEM", "minecraft:diamond", 2);
        assertThat(model.getEditing().getRewards()).hasSize(1);
        assertThat(model.getEditing().getRewards().get(0).getType()).isEqualTo(QuestReward.Type.ITEM);

        model.beginRowEdit(QuestEditorScreenModel.RowKind.REWARD, 0);
        model.applyRowEdit("EXPERIENCE", "levels", 10);
        assertThat(model.getEditing().getRewards().get(0).getType()).isEqualTo(QuestReward.Type.EXPERIENCE);

        model.removeRewardRow(0);
        assertThat(model.getEditing().getRewards()).isEmpty();
    }

    @Test
    void validateForSaveEnforcesValidatorRequirements() {
        model.beginNew(NamespacedId.of("storynpcs:ok_quest"), "Fine");
        assertThat(model.validateForSave()).isNull();

        model.getEditing().getObjectives().clear();
        assertThat(model.validateForSave()).contains("objective");

        model.beginNew(NamespacedId.of("storynpcs:ok2"), "  ");
        model.getEditing().setTitle(" ");
        assertThat(model.validateForSave()).contains("title");
    }

    @Test
    void deleteRequiresTwoClicks() {
        model.beginEdit(NamespacedId.of("storynpcs:m3test"));
        assertThat(model.confirmDeleteClick()).isFalse();
        assertThat(model.isDeleteArmed()).isTrue();
        assertThat(model.confirmDeleteClick()).isTrue();
        assertThat(model.isDeleteArmed()).isFalse();
    }

    @Test
    void saveResultRefreshesListAndDropsToListWhenQuestWasDeleted() {
        model.beginEdit(NamespacedId.of("storynpcs:m3test"));
        model.setTitle("X");

        model.onSaveResult(true, "Quest deleted.", List.of(quest("storynpcs:bounty_goblins", "Bounty")));
        assertThat(model.getMode()).isEqualTo(QuestEditorScreenModel.Mode.LIST);
        assertThat(model.questCount()).isEqualTo(1);
        assertThat(model.isDirty()).isFalse();
    }

    @Test
    void saveJsonSerializesWorkingCopy() {
        model.beginEdit(NamespacedId.of("storynpcs:m3test"));
        model.setTitle("Round Trip");
        Quest back = QuestSerde.fromJson(model.saveJson()).orElseThrow();
        assertThat(back.getId().toString()).isEqualTo("storynpcs:m3test");
        assertThat(back.getTitle()).isEqualTo("Round Trip");
        assertThat(back.getObjectives()).hasSize(1);
    }

    @Test
    void filterMatchesIdAndTitleCaseInsensitively() {
        model.setListFilter("BOUNTY");
        assertThat(model.getFilteredQuests()).hasSize(1);
        assertThat(model.getFilteredQuests().get(0).getId().toString()).isEqualTo("storynpcs:bounty_goblins");

        model.setListFilter("authoring");
        assertThat(model.getFilteredQuests()).hasSize(1);
        assertThat(model.getFilteredQuests().get(0).getId().toString()).isEqualTo("storynpcs:m3test");

        model.setListFilter("storynpcs:");
        assertThat(model.getFilteredQuests()).hasSize(2);
    }

    @Test
    void clearingFilterRestoresFullListAndResetsScroll() {
        model.setListScroll(1);
        model.setListFilter("zzz_no_match");
        assertThat(model.getFilteredQuests()).isEmpty();
        assertThat(model.getListScroll()).isZero();
        model.setListFilter("");
        assertThat(model.getFilteredQuests()).hasSize(2);
    }

    @Test
    void expectedRevisionFollowsTheSelectedDefinitionNotTheOpenedRow() {
        // The server sends one token per definition; the token for the row under
        // the cursor must be used — never the token of the initially opened row.
        model.loadExpectedRevisions(java.util.Map.of(
                "storynpcs:bounty_goblins", 3L,
                "storynpcs:m3test", 7L));

        model.beginEdit(NamespacedId.of("storynpcs:bounty_goblins"));
        assertThat(model.expectedRevision()).isEqualTo(3L);

        model.backToList();
        model.beginEdit(NamespacedId.of("storynpcs:m3test"));
        assertThat(model.expectedRevision()).isEqualTo(7L);

        // A brand-new definition has no committed revision.
        model.backToList();
        model.beginNew(NamespacedId.of("storynpcs:fresh_quest"), "Fresh");
        assertThat(model.expectedRevision()).isZero();
    }

    @Test
    void committedRevisionBindsToTheSavedIdOnly() {
        model.loadExpectedRevisions(java.util.Map.of(
                "storynpcs:bounty_goblins", 3L,
                "storynpcs:m3test", 7L));

        // Save commits revision 4 for bounty_goblins while a different row is open.
        model.beginEdit(NamespacedId.of("storynpcs:m3test"));
        model.recordCommittedRevision("storynpcs:bounty_goblins", 4L);

        assertThat(model.expectedRevision()).isEqualTo(7L);
        assertThat(model.expectedRevisionFor("storynpcs:bounty_goblins")).isEqualTo(4L);
    }

    @Test
    void revisionHintSeedsOnlyUnknownIds() {
        model.loadExpectedRevisions(java.util.Map.of("storynpcs:m3test", 7L));
        // The open-payload hint for the selected row must not clobber the map's token.
        model.recordRevisionHint("storynpcs:m3test", 99L);
        model.recordRevisionHint("storynpcs:bounty_goblins", 3L);

        assertThat(model.expectedRevisionFor("storynpcs:m3test")).isEqualTo(7L);
        assertThat(model.expectedRevisionFor("storynpcs:bounty_goblins")).isEqualTo(3L);
    }

    @Test
    void staleOrMalformedRevisionEntriesAreDropped() {
        java.util.Map<String, Long> revisions = new java.util.HashMap<>();
        revisions.put("storynpcs:m3test", 5L);
        revisions.put("", 9L);
        revisions.put("storynpcs:bad", -1L);
        revisions.put("storynpcs:nullrev", null);
        model.loadExpectedRevisions(revisions);

        assertThat(model.expectedRevisionFor("storynpcs:m3test")).isEqualTo(5L);
        assertThat(model.expectedRevisionFor("storynpcs:bad")).isZero();
        assertThat(model.expectedRevisionFor("storynpcs:nullrev")).isZero();
        assertThat(model.expectedRevisionFor("")).isZero();
        assertThat(model.expectedRevisionFor(null)).isZero();
    }

    @Test
    void editorRevisionsJsonRoundTrips() {
        java.util.Map<String, Long> revisions = java.util.Map.of(
                "storynpcs:bounty_goblins", 3L, "storynpcs:m3test", 7L);
        String json = EditorRevisions.toJson(revisions);
        assertThat(EditorRevisions.parse(json)).containsExactlyInAnyOrderEntriesOf(revisions);
        assertThat(EditorRevisions.parse("not json")).isEmpty();
        assertThat(EditorRevisions.parse(null)).isEmpty();
        assertThat(EditorRevisions.toJson(null)).isEqualTo("{}");
    }
}
