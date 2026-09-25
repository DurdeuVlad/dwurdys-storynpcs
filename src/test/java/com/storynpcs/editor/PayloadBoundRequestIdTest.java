package com.storynpcs.editor;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadBoundRequestIdTest {
    @Test
    void unchangedRetriesReuseIdButEditedSubmissionsAndAcknowledgedResultsDoNot() {
        PayloadBoundRequestId ids = new PayloadBoundRequestId();
        UUID original = ids.forPayload("{\"name\":\"invalid reference\"}");

        assertThat(ids.forPayload("{\"name\":\"invalid reference\"}")).isEqualTo(original);
        assertThat(ids.matchesCurrent(original)).isTrue();
        UUID corrected = ids.forPayload("{\"name\":\"reference cleared\"}");
        assertThat(corrected).isNotEqualTo(original);
        assertThat(ids.matchesCurrent(original)).isFalse();
        assertThat(ids.matchesCurrent(corrected)).isTrue();
        assertThat(ids.forPayload("{\"name\":\"reference cleared\"}")).isEqualTo(corrected);

        assertThat(ids.acknowledge(corrected)).isTrue();
        assertThat(ids.matchesCurrent(corrected)).isFalse();
        assertThat(ids.forPayload("{\"name\":\"reference cleared\"}")).isNotEqualTo(corrected);
    }

    @Test
    void acknowledgedRejectionStartsANewAttemptButUnansweredRetryKeepsItsKey() {
        PayloadBoundRequestId ids = new PayloadBoundRequestId();
        String submitted = "{\"name\":\"transient storage error\"}";
        UUID unanswered = ids.forPayload(submitted);

        // A timeout or lost response is retried as the same logical request.
        assertThat(ids.forPayload(submitted)).isEqualTo(unanswered);

        // Once the server's rejection arrives, the next click is a new attempt.
        assertThat(ids.acknowledge(unanswered)).isTrue();
        UUID retryAfterAcknowledgedFailure = ids.forPayload(submitted);
        assertThat(retryAfterAcknowledgedFailure).isNotEqualTo(unanswered);
        assertThat(ids.forPayload(submitted)).isEqualTo(retryAfterAcknowledgedFailure);

        // A delayed duplicate response from the first attempt must not clear the new key.
        assertThat(ids.acknowledge(unanswered)).isFalse();
        assertThat(ids.matchesCurrent(retryAfterAcknowledgedFailure)).isTrue();
    }

    @Test
    void deleteRetriesReuseIdOnlyForTheSameTargetAndRevision() {
        PayloadBoundRequestId ids = new PayloadBoundRequestId();
        UUID first = ids.forPayload("quest-delete\nstorynpcs:starter\n4");

        assertThat(ids.forPayload("quest-delete\nstorynpcs:starter\n4")).isEqualTo(first);
        UUID otherTarget = ids.forPayload("quest-delete\nstorynpcs:escort\n4");
        assertThat(otherTarget).isNotEqualTo(first);
        UUID newerRevision = ids.forPayload("quest-delete\nstorynpcs:escort\n5");
        assertThat(newerRevision).isNotEqualTo(otherTarget);
        assertThat(ids.matchesCurrent(first)).isFalse();
        assertThat(ids.matchesCurrent(newerRevision)).isTrue();

        assertThat(ids.acknowledge(newerRevision)).isTrue();
        assertThat(ids.forPayload("quest-delete\nstorynpcs:escort\n5")).isNotEqualTo(newerRevision);
    }
}
