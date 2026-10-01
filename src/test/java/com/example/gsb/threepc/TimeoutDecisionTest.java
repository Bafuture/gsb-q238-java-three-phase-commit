package com.example.gsb.threepc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 需求 3：预提交阶段协调者失联时，参与者的两种超时决策。
 */
class TimeoutDecisionTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void participantAbortsOnTimeoutWhenItOnlyVotedYes() {
        SimulatedParticipant participant = new SimulatedParticipant("p1", new InMemoryDurableStore());
        participant.canCommit("tx-t1");
        assertThat(participant.state("tx-t1")).isEqualTo(ParticipantState.READY);

        TransactionOutcome decision = participant.onCoordinatorTimeout("tx-t1");

        assertThat(decision).isEqualTo(TransactionOutcome.ABORTED);
        assertThat(participant.state("tx-t1")).isEqualTo(ParticipantState.ABORTED);
        assertThat(participant.commitEffects("tx-t1")).isZero();
    }

    @Test
    void participantCommitsOnTimeoutWhenPreCommitted() {
        SimulatedParticipant participant = new SimulatedParticipant("p1", new InMemoryDurableStore());
        participant.canCommit("tx-t2");
        participant.preCommit("tx-t2");
        assertThat(participant.state("tx-t2")).isEqualTo(ParticipantState.PRE_COMMITTED);

        TransactionOutcome decision = participant.onCoordinatorTimeout("tx-t2");

        assertThat(decision).isEqualTo(TransactionOutcome.COMMITTED);
        assertThat(participant.state("tx-t2")).isEqualTo(ParticipantState.COMMITTED);
        assertThat(participant.commitEffects("tx-t2")).isEqualTo(1);
    }

    @Test
    void participantsConvergeOnCommitWhenCoordinatorIsLostAfterPreCommitPhase() {
        InMemoryDurableStore coordinatorStore = new InMemoryDurableStore();
        SimulatedParticipant p1 = new SimulatedParticipant("p1", new InMemoryDurableStore());
        SimulatedParticipant p2 = new SimulatedParticipant("p2", new InMemoryDurableStore());
        SimulatedParticipant p3 = new SimulatedParticipant("p3", new InMemoryDurableStore());
        List<SimulatedParticipant> participants = List.of(p1, p2, p3);
        // 提交决策落盘（进入 COMMITTING）后、任何 doCommit 发出前，协调者崩溃
        ThreePhaseCommitCoordinator crashed = new ThreePhaseCommitCoordinator(coordinatorStore, clock,
                record -> {
                    if (record.state() == CoordinatorState.COMMITTING) {
                        throw new SimulatedCrashException();
                    }
                });

        assertThatThrownBy(() -> crashed.commit("tx-t3", List.copyOf(participants)))
                .isInstanceOf(SimulatedCrashException.class);
        for (SimulatedParticipant participant : participants) {
            assertThat(participant.state("tx-t3")).isEqualTo(ParticipantState.PRE_COMMITTED);
            assertThat(participant.doCommitCalls()).isZero();
        }

        // 超时规则：PRE_COMMITTED → 单方面提交，不阻塞等待协调者
        for (SimulatedParticipant participant : participants) {
            assertThat(participant.onCoordinatorTimeout("tx-t3")).isEqualTo(TransactionOutcome.COMMITTED);
        }

        // 协调者重启后续推：与参与者的单方面决策一致，且 doCommit 幂等不重复生效
        ThreePhaseCommitCoordinator restarted = new ThreePhaseCommitCoordinator(coordinatorStore, clock);
        TransactionResult result = restarted.recover("tx-t3", List.copyOf(participants));

        assertThat(result.outcome()).isEqualTo(TransactionOutcome.COMMITTED);
        assertThat(result.complete()).isTrue();
        for (SimulatedParticipant participant : participants) {
            assertThat(participant.state("tx-t3")).isEqualTo(ParticipantState.COMMITTED);
            assertThat(participant.commitEffects("tx-t3")).isEqualTo(1);
        }
    }

    @Test
    void participantsConvergeOnAbortWhenCoordinatorIsLostBeforePreCommit() {
        InMemoryDurableStore coordinatorStore = new InMemoryDurableStore();
        SimulatedParticipant p1 = new SimulatedParticipant("p1", new InMemoryDurableStore());
        SimulatedParticipant p2 = new SimulatedParticipant("p2", new InMemoryDurableStore());
        List<SimulatedParticipant> participants = List.of(p1, p2);
        // canCommit 全部通过、preCommit 尚未发出时，协调者崩溃
        ThreePhaseCommitCoordinator crashed = new ThreePhaseCommitCoordinator(coordinatorStore, clock,
                record -> {
                    if (record.state() == CoordinatorState.PRE_COMMITTING) {
                        throw new SimulatedCrashException();
                    }
                });

        assertThatThrownBy(() -> crashed.commit("tx-t4", List.copyOf(participants)))
                .isInstanceOf(SimulatedCrashException.class);
        for (SimulatedParticipant participant : participants) {
            assertThat(participant.state("tx-t4")).isEqualTo(ParticipantState.READY);
        }

        // 超时规则：READY → 单方面中止（此时不可能有人已提交）
        for (SimulatedParticipant participant : participants) {
            assertThat(participant.onCoordinatorTimeout("tx-t4")).isEqualTo(TransactionOutcome.ABORTED);
        }

        // 协调者重启：没有持久化的提交决策 → 安全中止，与参与者一致
        ThreePhaseCommitCoordinator restarted = new ThreePhaseCommitCoordinator(coordinatorStore, clock);
        TransactionResult result = restarted.recover("tx-t4", List.copyOf(participants));

        assertThat(result.outcome()).isEqualTo(TransactionOutcome.ABORTED);
        assertThat(result.complete()).isTrue();
        for (SimulatedParticipant participant : participants) {
            assertThat(participant.state("tx-t4")).isEqualTo(ParticipantState.ABORTED);
            assertThat(participant.commitEffects("tx-t4")).isZero();
        }
    }
}
