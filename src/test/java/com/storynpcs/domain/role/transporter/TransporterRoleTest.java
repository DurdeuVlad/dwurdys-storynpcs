package com.storynpcs.domain.role.transporter;

import com.storynpcs.domain.common.NamespacedId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TransporterRoleTest {

    @Test
    void defaultsToNoDestinationsAndNoFeeOverride() {
        TransporterRole role = new TransporterRole();
        assertThat(role.getOfferedDestinationIds()).isEmpty();
        assertThat(role.hasFeeOverride()).isFalse();
        assertThat(role.getFeeOverride()).isEqualTo(-1);
    }

    @Test
    void addAndRemoveOfferedDestinations() {
        TransporterRole role = new TransporterRole();
        NamespacedId harbor = NamespacedId.of("storynpcs:harbor");
        NamespacedId capital = NamespacedId.of("storynpcs:capital");

        role.addOfferedDestination(harbor);
        role.addOfferedDestination(capital);
        assertThat(role.offers(harbor)).isTrue();
        assertThat(role.offers(capital)).isTrue();
        assertThat(role.offers(NamespacedId.of("storynpcs:unrelated"))).isFalse();

        role.removeOfferedDestination(harbor);
        assertThat(role.offers(harbor)).isFalse();
        assertThat(role.offers(capital)).isTrue();
    }

    @Test
    void setOfferedDestinationIdsReplacesTheSetAndDropsNulls() {
        TransporterRole role = new TransporterRole();
        role.addOfferedDestination(NamespacedId.of("storynpcs:stale"));

        Set<NamespacedId> replacement = new LinkedHashSet<>();
        replacement.add(NamespacedId.of("storynpcs:harbor"));
        replacement.add(null);
        role.setOfferedDestinationIds(replacement);

        assertThat(role.getOfferedDestinationIds()).containsExactly(NamespacedId.of("storynpcs:harbor"));
    }

    @Test
    void setOfferedDestinationIdsAcceptsNullAsEmpty() {
        TransporterRole role = new TransporterRole();
        role.addOfferedDestination(NamespacedId.of("storynpcs:stale"));
        role.setOfferedDestinationIds(null);
        assertThat(role.getOfferedDestinationIds()).isEmpty();
    }

    @Test
    void effectiveFeeUsesDestinationFeeWhenNoOverrideIsSet() {
        TransporterRole role = new TransporterRole();
        assertThat(role.effectiveFee(50)).isEqualTo(50);
        assertThat(role.effectiveFee(0)).isEqualTo(0);
    }

    @Test
    void effectiveFeeUsesTheOverrideWhenSet() {
        TransporterRole role = new TransporterRole(Set.of(), 10);
        assertThat(role.hasFeeOverride()).isTrue();
        assertThat(role.effectiveFee(999)).isEqualTo(10);
    }

    @Test
    void effectiveFeeNeverGoesNegativeWithoutAnOverride() {
        TransporterRole role = new TransporterRole();
        assertThat(role.effectiveFee(-5)).isEqualTo(0);
    }
}
