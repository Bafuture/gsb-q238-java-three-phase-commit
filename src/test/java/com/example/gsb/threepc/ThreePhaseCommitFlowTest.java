package com.example.gsb.threepc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 需求 1/2：三阶段正常流程与拒绝中止。
 */
class ThreePhaseCommitFlowTest {

    private final MutableClock clock = new MutableClock();

    private ThreePhaseCommitCoordinator newCoordinator(InMemoryDurableStore store) {
        return new ThreePhaseCommitCoordinator(store, clock);
    }

    @Test
    void commitsWhenAllParticipantsAgree() {
        SimulatedParticipant p1 = new SimulatedParticipant("p1", new InMemoryDurableStore());
        SimulatedParticipant p2 = new SimulatedParticipant("p2", new InMemoryDurableStore());
        SimulatedParticipant p3 = new SimulatedParticipant("p3", new InMemoryDurableStore());
        ThreePhaseCommitCoordinator coordinator = newCoordinator(new InMemoryDurableStore());

        TransactionResult result = coordinator.commit("tx-1", List.of(p1, p2, p3));

        assertThat(result.outcome()).isEqualTo(TransactionOutcome.COMMITTED);
        assertThat(result.complete()).isTrue();
        assertThat(result.pendingParticipants()).isEmpty();
        for (SimulatedParticipant participant : List.of(p1, p2, p3)) {
            assertThat(participant.state("tx-1")).isEqualTo(ParticipantState.COMMITTED);
            assertThat(participant.commitEffects("tx-1")).isEqualTo(1);
            assertThat(participant.canCommitCalls()).isEqualTo(1);
            assertThat(participant.preCommitCalls()).isEqualTo(1);
            assertThat(participant.doCommitCalls()).isEqualTo(1);
            assertThat(participant.abortCalls()).isZero();
        }
        List<CoordinatorState> phases = result.log().stream()
                .filter(entry -> entry.event() == LogEventType.PHASE_ENTERED)
                .map(entry -> CoordinatorState.valueOf(entry.detail()))
                .toList();
        assertThat(phases).containsExactly(
                CoordinatorState.CAN_COMMITTING,
                CoordinatorState.PRE_COMMITTING,
                CoordinatorState.COMMITTING,
                CoordinatorState.COMMITTED);
    }

    @Test
    void abortsAndRollsBackEveryoneWhenAnyParticipantVotesNo() {
        SimulatedParticipant p1 = new SimulatedParticipant("p1", new InMemoryDurableStore());
        SimulatedParticipant p2 = new SimulatedParticipant("p2", new InMemoryDurableStore())
                .vote(Vote.NO);
        SimulatedParticipant p3 = new SimulatedParticipant("p3", new InMemoryDurableStore());
        ThreePhaseCommitCoordinator coordinator = newCoordinator(new InMemoryDurableStore());

        TransactionResult result = coordinator.commit("tx-2", List.of(p1, p2, p3));

        assertThat(result.outcome()).isEqualTo(TransactionOutcome.ABORTED);
        assertThat(result.complete()).isTrue();
        for (SimulatedParticipant participant : List.of(p1, p2, p3)) {
            assertThat(participant.state("tx-2")).isEqualTo(ParticipantState.ABORTED);
            assertThat(participant.commitEffects("tx-2")).isZero();
            assertThat(participant.preCommitCalls()).isZero();
            assertThat(participant.doCommitCalls()).isZero();
            assertThat(participant.abortCalls()).isEqualTo(1);
        }
    }

    @Test
    void abortsWhenAnyParticipantFailsPreCommit() {
        SimulatedParticipant p1 = new SimulatedParticipant("p1", new InMemoryDurableStore());
        SimulatedParticipant p2 = new SimulatedParticipant("p2", new InMemoryDurableStore());
        SimulatedParticipant p3 = new SimulatedParticipant("p3", new InMemoryDurableStore())
                .failPreCommit(true);
        ThreePhaseCommitCoordinator coordinator = newCoordinator(new InMemoryDurableStore());

        TransactionResult result = coordinator.commit("tx-3", List.of(p1, p2, p3));

        assertThat(result.outcome()).isEqualTo(TransactionOutcome.ABORTED);
        assertThat(result.complete()).isTrue();
        for (SimulatedParticipant participant : List.of(p1, p2, p3)) {
            assertThat(participant.state("tx-3")).isEqualTo(ParticipantState.ABORTED);
            assertThat(participant.commitEffects("tx-3")).isZero();
            assertThat(participant.doCommitCalls()).isZero();
        }
    }
}
