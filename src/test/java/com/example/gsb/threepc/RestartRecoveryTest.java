package com.example.gsb.threepc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 需求 4/6：协调者与参与者重启后续推、故障恢复后补齐状态并达成最终一致。
 */
class RestartRecoveryTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void coordinatorRestartResumesFromPersistedPhaseInsteadOfStartingOver() {
        InMemoryDurableStore coordinatorStore = new InMemoryDurableStore();
        SimulatedParticipant p1 = new SimulatedParticipant("p1", new InMemoryDurableStore());
        SimulatedParticipant p2 = new SimulatedParticipant("p2", new InMemoryDurableStore());
        SimulatedParticipant p3 = new SimulatedParticipant("p3", new InMemoryDurableStore());
        List<SimulatedParticipant> participants = List.of(p1, p2, p3);
        // 提交决策落盘后、doCommit 发出前崩溃
        ThreePhaseCommitCoordinator crashed = new ThreePhaseCommitCoordinator(coordinatorStore, clock,
                record -> {
                    if (record.state() == CoordinatorState.COMMITTING) {
                        throw new SimulatedCrashException();
                    }
                });
        assertThatThrownBy(() -> crashed.commit("tx-r1", List.copyOf(participants)))
                .isInstanceOf(SimulatedCrashException.class);

        // 重启：同一 store 上的新协调者实例，从 COMMITTING 续推
        ThreePhaseCommitCoordinator restarted = new ThreePhaseCommitCoordinator(coordinatorStore, clock);
        TransactionResult result = restarted.recover("tx-r1", List.copyOf(participants));

        assertThat(result.outcome()).isEqualTo(TransactionOutcome.COMMITTED);
        assertThat(result.complete()).isTrue();
        for (SimulatedParticipant participant : participants) {
            assertThat(participant.state("tx-r1")).isEqualTo(ParticipantState.COMMITTED);
            assertThat(participant.commitEffects("tx-r1")).isEqualTo(1);
            // 没有从头开始：canCommit/preCommit 都只执行过一次
            assertThat(participant.canCommitCalls()).isEqualTo(1);
            assertThat(participant.preCommitCalls()).isEqualTo(1);
            assertThat(participant.doCommitCalls()).isEqualTo(1);
        }
        assertThat(result.log())
                .anyMatch(entry -> entry.event() == LogEventType.RECOVERY_STARTED);
    }

    @Test
    void participantRestartPreservesPreCommittedStateAndCatchesUp() {
        InMemoryDurableStore coordinatorStore = new InMemoryDurableStore();
        InMemoryDurableStore p2Store = new InMemoryDurableStore();
        SimulatedParticipant p1 = new SimulatedParticipant("p1", new InMemoryDurableStore());
        SimulatedParticipant p2 = new SimulatedParticipant("p2", p2Store);
        SimulatedParticipant p3 = new SimulatedParticipant("p3", new InMemoryDurableStore());
        // p2 在 doCommit 投递阶段宕机
        ThreePhaseCommitCoordinator coordinator = new ThreePhaseCommitCoordinator(coordinatorStore, clock,
                record -> {
                    if (record.state() == CoordinatorState.COMMITTING) {
                        p2.crash();
                    }
                });

        TransactionResult result = coordinator.commit("tx-r2", List.of(p1, p2, p3));

        assertThat(result.outcome()).isEqualTo(TransactionOutcome.COMMITTED);
        assertThat(result.complete()).isFalse();
        assertThat(result.pendingParticipants()).containsExactly("p2");
        assertThat(p1.state("tx-r2")).isEqualTo(ParticipantState.COMMITTED);
        assertThat(p3.state("tx-r2")).isEqualTo(ParticipantState.COMMITTED);

        // 参与者重启：同一 store 上的新实例，状态仍是 PRE_COMMITTED
        SimulatedParticipant p2Restarted = new SimulatedParticipant("p2", p2Store);
        assertThat(p2Restarted.state("tx-r2")).isEqualTo(ParticipantState.PRE_COMMITTED);
        assertThat(p2Restarted.doCommitCalls()).isZero();

        // 协调者续推，为恢复后的 p2 补齐最终决策 → 全部一致
        TransactionResult recovered = coordinator.recover("tx-r2", List.of(p1, p2Restarted, p3));

        assertThat(recovered.outcome()).isEqualTo(TransactionOutcome.COMMITTED);
        assertThat(recovered.complete()).isTrue();
        assertThat(p2Restarted.state("tx-r2")).isEqualTo(ParticipantState.COMMITTED);
        assertThat(p2Restarted.commitEffects("tx-r2")).isEqualTo(1);
        assertThat(p1.state("tx-r2")).isEqualTo(ParticipantState.COMMITTED);
        assertThat(p3.state("tx-r2")).isEqualTo(ParticipantState.COMMITTED);
    }

    @Test
    void recoveredParticipantEventuallyReachesConsistentFinalStateAfterAbort() {
        InMemoryDurableStore coordinatorStore = new InMemoryDurableStore();
        SimulatedParticipant p1 = new SimulatedParticipant("p1", new InMemoryDurableStore());
        SimulatedParticipant p2 = new SimulatedParticipant("p2", new InMemoryDurableStore());
        SimulatedParticipant p3 = new SimulatedParticipant("p3", new InMemoryDurableStore());
        p3.crash();
        ThreePhaseCommitCoordinator coordinator = new ThreePhaseCommitCoordinator(coordinatorStore, clock);

        // p3 全程不可达 → 视为 NO → 中止；abort 无法送达 p3，记为待补齐
        TransactionResult result = coordinator.commit("tx-r3", List.of(p1, p2, p3));

        assertThat(result.outcome()).isEqualTo(TransactionOutcome.ABORTED);
        assertThat(result.complete()).isFalse();
        assertThat(result.pendingParticipants()).containsExactly("p3");
        assertThat(p1.state("tx-r3")).isEqualTo(ParticipantState.ABORTED);
        assertThat(p2.state("tx-r3")).isEqualTo(ParticipantState.ABORTED);
        assertThat(p3.state("tx-r3")).isEqualTo(ParticipantState.INIT);

        // p3 恢复后补齐 abort，最终所有参与者状态一致
        p3.revive();
        TransactionResult recovered = coordinator.recover("tx-r3", List.of(p1, p2, p3));

        assertThat(recovered.outcome()).isEqualTo(TransactionOutcome.ABORTED);
        assertThat(recovered.complete()).isTrue();
        for (SimulatedParticipant participant : List.of(p1, p2, p3)) {
            assertThat(participant.state("tx-r3")).isEqualTo(ParticipantState.ABORTED);
            assertThat(participant.commitEffects("tx-r3")).isZero();
        }
    }
}
