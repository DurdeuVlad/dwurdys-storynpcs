package com.storynpcs.domain.template;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NpcTemplateSerdeTest {

    private static NpcTemplate sampleTemplate() {
        NpcDefinition source = new NpcDefinition(NamespacedId.of("storynpcs:bard_npc"), "Bard NPC");
        return NpcTemplate.capture(NamespacedId.of("storynpcs:bard_template"), source.getId(), source);
    }

    @Test
    void roundTripsThroughJson() {
        NpcTemplate original = sampleTemplate();
        String json = NpcTemplateSerde.toJson(original);
        Optional<NpcTemplate> parsed = NpcTemplateSerde.fromJson(json);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().getId()).isEqualTo(original.getId());
        assertThat(parsed.get().getSourceNpcId()).isEqualTo(original.getSourceNpcId());
        assertThat(parsed.get().getSchemaVersion()).isEqualTo(original.getSchemaVersion());
        assertThat(parsed.get().getRevision()).isEqualTo(original.getRevision());
        assertThat(parsed.get().getEmbeddedDefinitionJson()).isEqualTo(original.getEmbeddedDefinitionJson());
    }

    @Test
    void roundTripsAListOfTemplates() {
        NpcTemplate a = sampleTemplate();
        NpcTemplate b = sampleTemplate();
        String json = NpcTemplateSerde.toJsonList(List.of(a, b));
        List<NpcTemplate> parsed = NpcTemplateSerde.fromJsonList(json);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).getId()).isEqualTo(a.getId());
        assertThat(parsed.get(1).getId()).isEqualTo(b.getId());
    }

    @Test
    void fromJsonIsEmptyForBlankOrNullInput() {
        assertThat(NpcTemplateSerde.fromJson(null)).isEmpty();
        assertThat(NpcTemplateSerde.fromJson("")).isEmpty();
        assertThat(NpcTemplateSerde.fromJson("   ")).isEmpty();
    }

    @Test
    void fromJsonIsEmptyForMalformedInput() {
        assertThat(NpcTemplateSerde.fromJson("{ not valid")).isEmpty();
    }

    @Test
    void fromJsonListIsEmptyListForBlankInput() {
        assertThat(NpcTemplateSerde.fromJsonList(null)).isEmpty();
        assertThat(NpcTemplateSerde.fromJsonList("")).isEmpty();
    }
}
