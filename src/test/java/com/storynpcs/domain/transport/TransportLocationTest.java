package com.storynpcs.domain.transport;

import com.storynpcs.domain.common.NamespacedId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransportLocationTest {

    private TransportLocation valid() {
        return new TransportLocation(NamespacedId.of("storynpcs:capital"), "Capital City",
                "minecraft:overworld", 100.0, 64.0, -200.0);
    }

    @Test
    void wellFormedLocationHasNoValidationErrors() {
        assertThat(valid().validateDestinationContract()).isEmpty();
    }

    @Test
    void missingDimensionIsRejected() {
        var location = valid();
        location.setDimensionId(null);
        assertThat(location.validateDestinationContract()).anyMatch(e -> e.contains("dimension"));
    }

    @Test
    void malformedDimensionIdIsRejectedAtParse() {
        // The model stores a typed NamespacedId: malformed dimensions cannot be
        // held at all — they fail at construction rather than at validation.
        assertThatThrownBy(() -> new TransportLocation(NamespacedId.of("storynpcs:bad"), "Bad",
                "not a namespaced id!!", 0.0, 64.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        // A blank dimension means "missing", not malformed — it is reported by
        // destination validation so the service boundary returns diagnostics
        // instead of throwing on untrusted input.
        var blankDimension = new TransportLocation(NamespacedId.of("storynpcs:bad"), "Bad",
                "", 0.0, 64.0, 0.0);
        assertThat(blankDimension.validateDestinationContract())
                .anyMatch(e -> e.contains("dimension"));
    }

    @Test
    void nonFiniteCoordinatesAreRejected() {
        var location = valid();
        location.setX(Double.NaN);
        assertThat(location.validateDestinationContract()).anyMatch(e -> e.contains("finite"));

        var infLocation = valid();
        infLocation.setY(Double.POSITIVE_INFINITY);
        assertThat(infLocation.validateDestinationContract()).anyMatch(e -> e.contains("finite"));
    }

    @Test
    void coordinatesOutsideWorldBorderAreRejected() {
        var location = valid();
        location.setX(30_000_000);
        assertThat(location.validateDestinationContract()).anyMatch(e -> e.contains("world border"));
    }

    @Test
    void yOutsideBuildableRangeIsRejected() {
        var location = valid();
        location.setY(5000);
        assertThat(location.validateDestinationContract()).anyMatch(e -> e.contains("height range"));
    }

    @Test
    void negativeFeeIsRejectedAtMutation() {
        // setFee is fail-closed; the validation path still guards deserialized
        // payloads that bypass the setter.
        assertThatThrownBy(() -> valid().setFee(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multipleViolationsAreAllReported() {
        var location = new TransportLocation();
        assertThat(location.validateDestinationContract()).hasSizeGreaterThanOrEqualTo(2);
    }
}
