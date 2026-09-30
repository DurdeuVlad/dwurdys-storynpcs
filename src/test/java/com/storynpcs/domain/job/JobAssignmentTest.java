package com.storynpcs.domain.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobAssignmentTest {

    @Test
    void newAssignmentStartsIdleWithMatchingTimestamps() {
        JobAssignment job = new JobAssignment(JobType.FARMER);
        assertThat(job.getType()).isEqualTo(JobType.FARMER);
        assertThat(job.getState()).isEqualTo(JobState.IDLE);
        assertThat(job.getAssignedAtEpochMillis()).isGreaterThan(0);
        assertThat(job.getLastTransitionAtEpochMillis()).isEqualTo(job.getAssignedAtEpochMillis());
    }

    @Test
    void fullLifecycleStartPauseResumeStop() {
        JobAssignment job = new JobAssignment(JobType.GUARD);

        job.start();
        assertThat(job.getState()).isEqualTo(JobState.RUNNING);

        job.pause();
        assertThat(job.getState()).isEqualTo(JobState.PAUSED);

        job.resume();
        assertThat(job.getState()).isEqualTo(JobState.RUNNING);

        job.stop();
        assertThat(job.getState()).isEqualTo(JobState.STOPPED);
    }

    @Test
    void stopIsIdempotentFromStoppedState() {
        JobAssignment job = new JobAssignment(JobType.BARD);
        job.start();
        job.stop();
        job.stop(); // must not throw
        assertThat(job.getState()).isEqualTo(JobState.STOPPED);
    }

    @Test
    void stopFromIdleIsAllowed() {
        JobAssignment job = new JobAssignment(JobType.PUPPET);
        job.stop();
        assertThat(job.getState()).isEqualTo(JobState.STOPPED);
    }

    @Test
    void stopFromPausedIsAllowed() {
        JobAssignment job = new JobAssignment(JobType.HEALER);
        job.start();
        job.pause();
        job.stop();
        assertThat(job.getState()).isEqualTo(JobState.STOPPED);
    }

    @Test
    void cannotStartATwiceStartedJob() {
        JobAssignment job = new JobAssignment(JobType.ITEM_GIVER);
        job.start();
        assertThatThrownBy(job::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotPauseAnIdleJob() {
        JobAssignment job = new JobAssignment(JobType.CHUNK_LOADER);
        assertThatThrownBy(job::pause).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotPauseAnAlreadyPausedJob() {
        JobAssignment job = new JobAssignment(JobType.SPAWNER);
        job.start();
        job.pause();
        assertThatThrownBy(job::pause).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotResumeAnIdleJob() {
        JobAssignment job = new JobAssignment(JobType.CONVERSATION);
        assertThatThrownBy(job::resume).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotResumeARunningJob() {
        JobAssignment job = new JobAssignment(JobType.BUILDER);
        job.start();
        assertThatThrownBy(job::resume).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotResumeAStoppedJob() {
        JobAssignment job = new JobAssignment(JobType.FOLLOWER);
        job.start();
        job.stop();
        assertThatThrownBy(job::resume).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotStartAStoppedJob() {
        JobAssignment job = new JobAssignment(JobType.FARMER);
        job.stop();
        assertThatThrownBy(job::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allElevenTargetJobTypesAreDeclared() {
        assertThat(JobType.values()).hasSize(11);
    }
}
