package com.example.gsb.threepc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 需求 7：完整事务日志——各阶段时间、参与者响应与最终结果。
 */
class TransactionLogTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void logRecordsPhaseTimestampsParticipantResponsesAndFinalResult() {
        InMemoryDurableStore coordinatorStore = new InMemoryDurableStore();
        SimulatedParticipant p1 = new SimulatedParticipant("p1", new InMemoryDurableStore());
        SimulatedParticipant p2 = new SimulatedParticipant("p2", new InMemoryDurableStore());
        // 每次落盘推进 1 秒，使各阶段时间戳可区分
        ThreePhaseCommitCoordinator coordinator = new ThreePhaseCommitCoordinator(
                coordinatorStore, clock, record -> clock.advanceSeconds(1));

        TransactionResult result = coordinator.commit("tx-l1", List.of(p1, p2));
        List<TransactionLogEntry> log = result.log();

        // 各阶段及进入时间
        List<TransactionLogEntry> phaseEntries = log.stream()
                .filter(entry -> entry.event() == LogEventType.PHASE_ENTERED)
                .toList();
        assertThat(phaseEntries)
                .extracting(TransactionLogEntry::detail)
                .containsExactly("CAN_COMMITTING", "PRE_COMMITTING", "COMMITTING", "COMMITTED");
        assertThat(phaseEntries).allSatisfy(entry -> assertThat(entry.timestamp()).isNotNull());
        assertThat(log.stream().map(TransactionLogEntry::timestamp).toList()).isSorted();

        // 参与者响应（投票与预提交应答）
        assertThat(log.stream()
                .filter(entry -> entry.event() == LogEventType.VOTE_RECORDED)
                .map(TransactionLogEntry::detail))
                .containsExactlyInAnyOrder("p1 -> YES", "p2 -> YES");
        assertThat(log.stream()
                .filter(entry -> entry.event() == LogEventType.PRECOMMIT_REPLY_RECORDED)
                .map(TransactionLogEntry::detail))
                .containsExactlyInAnyOrder("p1 -> ACK", "p2 -> ACK");

        // 最终结果
        assertThat(log.stream()
                .filter(entry -> entry.event() == LogEventType.DECISION_MADE)
                .map(TransactionLogEntry::detail))
                .anySatisfy(detail -> assertThat(detail).contains("COMMITTED"));
        assertThat(log.get(log.size() - 1).event()).isEqualTo(LogEventType.TX_COMPLETED);
        assertThat(log.get(log.size() - 1).detail()).contains("COMMITTED");
        assertThat(log).allSatisfy(entry -> assertThat(entry.txId()).isEqualTo("tx-l1"));
    }

    @Test
    void logSurvivesCoordinatorRestart() {
        InMemoryDurableStore coordinatorStore = new InMemoryDurableStore();
        SimulatedParticipant p1 = new SimulatedParticipant("p1", new InMemoryDurableStore());
        ThreePhaseCommitCoordinator coordinator =
                new ThreePhaseCommitCoordinator(coordinatorStore, clock);
        List<TransactionLogEntry> before = coordinator.commit("tx-l2", List.of(p1)).log();

        ThreePhaseCommitCoordinator restarted =
                new ThreePhaseCommitCoordinator(coordinatorStore, clock);

        assertThat(restarted.log("tx-l2")).isEqualTo(before);
    }

    @Test
    void abortFlowIsAlsoFullyLogged() {
        InMemoryDurableStore coordinatorStore = new InMemoryDurableStore();
        SimulatedParticipant p1 = new SimulatedParticipant("p1", new InMemoryDurableStore())
                .vote(Vote.NO);
        SimulatedParticipant p2 = new SimulatedParticipant("p2", new InMemoryDurableStore());
        ThreePhaseCommitCoordinator coordinator =
                new ThreePhaseCommitCoordinator(coordinatorStore, clock);

        TransactionResult result = coordinator.commit("tx-l3", List.of(p1, p2));
        List<TransactionLogEntry> log = result.log();

        assertThat(log.stream()
                .filter(entry -> entry.event() == LogEventType.VOTE_RECORDED)
                .map(TransactionLogEntry::detail))
                .containsExactlyInAnyOrder("p1 -> NO", "p2 -> YES");
        assertThat(log.stream()
                .filter(entry -> entry.event() == LogEventType.DECISION_MADE)
                .map(TransactionLogEntry::detail))
                .anySatisfy(detail -> assertThat(detail).contains("ABORTED"));
        assertThat(log.stream()
                .filter(entry -> entry.event() == LogEventType.FINAL_DELIVERY)
                .map(TransactionLogEntry::detail))
                .allSatisfy(detail -> assertThat(detail).contains("ABORTED"));
        assertThat(log.get(log.size() - 1).event()).isEqualTo(LogEventType.TX_COMPLETED);
        assertThat(log.get(log.size() - 1).detail()).contains("ABORTED");
        List<Instant> timestamps = log.stream().map(TransactionLogEntry::timestamp).toList();
        assertThat(timestamps).isSorted();
    }
}
