package com.storynpcs.domain.transport;

import com.storynpcs.domain.common.NamespacedId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
    void blankDimensionIsRejected() {
        var location = valid();
        location.setDimension("");
        assertThat(location.validateDestinationContract()).anyMatch(e -> e.contains("dimension"));
    }

    @Test
    void malformedDimensionIdIsRejected() {
        var location = valid();
        location.setDimension("not a namespaced id!!");
        assertThat(location.validateDestinationContract()).anyMatch(e -> e.contains("dimension"));
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
    void negativeFeeIsRejected() {
        var location = valid();
        location.setFee(-1);
        assertThat(location.validateDestinationContract()).anyMatch(e -> e.contains("fee"));
    }

    @Test
    void multipleViolationsAreAllReported() {
        var location = valid();
        location.setDimension("");
        location.setFee(-5);
        assertThat(location.validateDestinationContract()).hasSizeGreaterThanOrEqualTo(2);
    }
}
