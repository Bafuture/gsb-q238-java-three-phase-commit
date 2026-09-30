package com.example.gsb.tpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ThreePhaseCommitTest {

  private static final String TX = "tx-1";

  private List<Participant> threeParticipants(StateStore store, TransactionLog log) {
    return List.of(
        new Participant("p1", store, log),
        new Participant("p2", store, log),
        new Participant("p3", store, log));
  }

  private void assertAllStatus(List<Participant> ps, ParticipantStatus status) {
    for (Participant p : ps) {
      assertThat(p.status(TX)).as(p.id() + " 状态").isEqualTo(status);
    }
  }

  @Test
  void allParticipantsAgree_commitsThroughAllThreePhases() {
    StateStore store = new InMemoryStateStore();
    TransactionLog log = new TransactionLog();
    List<Participant> ps = threeParticipants(store, log);
    Coordinator coordinator = new Coordinator(store, log);

    TransactionResult result = coordinator.execute(TX, ps);

    assertThat(result).isEqualTo(TransactionResult.COMMITTED);
    assertAllStatus(ps, ParticipantStatus.COMMITTED);
    assertThat(ps).allSatisfy(p -> assertThat(p.applyCount(TX)).isEqualTo(1));
    assertThat(coordinator.persistedState(TX)).isEqualTo(TransactionState.COMMITTED);

    var events = log.entriesFor(TX).stream().map(LogEntry::event).toList();
    assertThat(events).contains(
        "进入阶段 CAN_COMMIT，向 3 个参与者询问",
        "进入阶段 PRE_COMMIT，全员已同意，发送预提交",
        "进入阶段 COMMIT，做出提交决定并发送 doCommit",
        "最终结果：COMMITTED");
    assertThat(log.entriesFor(TX)).allSatisfy(e -> assertThat(e.timestamp()).isNotNull());
  }

  @Test
  void oneParticipantRejects_transactionAbortsAndEveryoneRollsBack() {
    StateStore store = new InMemoryStateStore();
    TransactionLog log = new TransactionLog();
    List<Participant> ps = List.of(
        new Participant("p1", store, log),
        new Participant("p2", store, log).refusing(),
        new Participant("p3", store, log));
    Coordinator coordinator = new Coordinator(store, log);

    TransactionResult result = coordinator.execute(TX, ps);

    assertThat(result).isEqualTo(TransactionResult.ABORTED);
    assertAllStatus(ps, ParticipantStatus.ABORTED);
    assertThat(ps).allSatisfy(p -> assertThat(p.applyCount(TX)).isZero());
    assertThat(coordinator.persistedState(TX)).isEqualTo(TransactionState.ABORTED);
  }

  @Test
  void preCommitFailure_abortsEvenParticipantsThatAlreadyPreCommitted() {
    StateStore store = new InMemoryStateStore();
    TransactionLog log = new TransactionLog();
    List<Participant> ps = List.of(
        new Participant("p1", store, log),
        new Participant("p2", store, log).failingPreCommit(),
        new Participant("p3", store, log));
    Coordinator coordinator = new Coordinator(store, log);

    TransactionResult result = coordinator.execute(TX, ps);

    assertThat(result).isEqualTo(TransactionResult.ABORTED);
    assertAllStatus(ps, ParticipantStatus.ABORTED);
    assertThat(ps).allSatisfy(p -> assertThat(p.applyCount(TX)).isZero());
  }

  @Test
  void timeoutInReadyState_participantAbortsOnItsOwn() {
    StateStore store = new InMemoryStateStore();
    TransactionLog log = new TransactionLog();
    Participant p1 = new Participant("p1", store, log);
    Coordinator coordinator = new Coordinator(store, log);

    assertThat(coordinator.askCanCommit(TX, List.of(p1))).isTrue();
    assertThat(p1.status(TX)).isEqualTo(ParticipantStatus.READY);

    // 协调者在发 preCommit 前失联，参与者超时自决
    TransactionResult decision = p1.onTimeout(TX);

    assertThat(decision).isEqualTo(TransactionResult.ABORTED);
    assertThat(p1.status(TX)).isEqualTo(ParticipantStatus.ABORTED);
  }

  @Test
  void timeoutInPreCommittedState_participantCommitsOnItsOwn() {
    StateStore store = new InMemoryStateStore();
    TransactionLog log = new TransactionLog();
    Participant p1 = new Participant("p1", store, log);
    Coordinator coordinator = new Coordinator(store, log);

    assertThat(coordinator.askCanCommit(TX, List.of(p1))).isTrue();
    assertThat(coordinator.sendPreCommit(TX, List.of(p1))).isTrue();
    assertThat(p1.status(TX)).isEqualTo(ParticipantStatus.PRE_COMMITTED);

    // 协调者在发 doCommit 前失联，参与者超时自决：自行提交
    TransactionResult decision = p1.onTimeout(TX);

    assertThat(decision).isEqualTo(TransactionResult.COMMITTED);
    assertThat(p1.status(TX)).isEqualTo(ParticipantStatus.COMMITTED);
    assertThat(p1.applyCount(TX)).isEqualTo(1);
  }

  @Test
  void coordinatorRestart_resumesFromPersistedPreCommitPhaseInsteadOfRestarting() {
    StateStore store = new InMemoryStateStore();
    TransactionLog log = new TransactionLog();
    List<Participant> ps = threeParticipants(store, log);
    Coordinator coordinator = new Coordinator(store, log);

    // 旧协调者推进到预提交完成后崩溃
    assertThat(coordinator.askCanCommit(TX, ps)).isTrue();
    assertThat(coordinator.sendPreCommit(TX, ps)).isTrue();

    // 全新协调者实例（重启），共享同一份持久化存储
    Coordinator restarted = new Coordinator(store, log);
    assertThat(restarted.persistedState(TX)).isEqualTo(TransactionState.PRE_COMMIT);

    TransactionResult result = restarted.recover(TX, ps);

    assertThat(result).isEqualTo(TransactionResult.COMMITTED);
    assertAllStatus(ps, ParticipantStatus.COMMITTED);
    assertThat(restarted.persistedState(TX)).isEqualTo(TransactionState.COMMITTED);

    // 恢复后再 execute 也是幂等的，不重新询问 canCommit
    assertThat(restarted.execute(TX, ps)).isEqualTo(TransactionResult.COMMITTED);
    assertThat(ps).allSatisfy(p -> assertThat(p.applyCount(TX)).isEqualTo(1));
  }

  @Test
  void duplicatePreCommitAndDoCommit_returnSameResultAndApplyExactlyOnce() {
    StateStore store = new InMemoryStateStore();
    TransactionLog log = new TransactionLog();
    Participant p1 = new Participant("p1", store, log);

    assertThat(p1.canCommit(TX)).isEqualTo(Vote.YES);
    assertThat(p1.preCommit(TX)).isTrue();
    assertThat(p1.preCommit(TX)).isTrue();
    assertThat(p1.status(TX)).isEqualTo(ParticipantStatus.PRE_COMMITTED);

    assertThat(p1.doCommit(TX)).isTrue();
    assertThat(p1.doCommit(TX)).isTrue();
    assertThat(p1.doCommit(TX)).isTrue();

    assertThat(p1.status(TX)).isEqualTo(ParticipantStatus.COMMITTED);
    assertThat(p1.applyCount(TX)).as("事务只能生效一次").isEqualTo(1);

    // 重复 abort / canCommit 也不改变状态
    p1.abort(TX);
    assertThat(p1.status(TX)).isEqualTo(ParticipantStatus.COMMITTED);
    assertThat(p1.canCommit(TX)).isEqualTo(Vote.YES);
  }

  @Test
  void participantCrashesDuringDoCommit_recoversAndClusterReachesConsensus() {
    StateStore store = new InMemoryStateStore();
    TransactionLog log = new TransactionLog();
    List<Participant> ps = threeParticipants(store, log);
    Coordinator coordinator = new Coordinator(store, log);

    // p3 在预提交之后、doCommit 之前崩溃
    assertThat(coordinator.askCanCommit(TX, ps)).isTrue();
    assertThat(coordinator.sendPreCommit(TX, ps)).isTrue();
    Participant p3 = ps.get(2);
    p3.crash();

    // 协调者仍做出提交决定，但 p3 的 doCommit 暂时送不到
    assertThat(coordinator.sendDoCommit(TX, ps)).isEqualTo(TransactionResult.COMMITTED);
    assertThat(ps.get(0).status(TX)).isEqualTo(ParticipantStatus.COMMITTED);
    assertThat(ps.get(1).status(TX)).isEqualTo(ParticipantStatus.COMMITTED);
    assertThat(coordinator.persistedState(TX)).isEqualTo(TransactionState.COMMIT);

    // p3 重启：新实例只依据持久化状态恢复，随后协调者补偿 doCommit
    Participant p3Restarted = p3.restart();
    assertThat(p3Restarted.status(TX)).isEqualTo(ParticipantStatus.PRE_COMMITTED);
    List<Participant> recoveredTopology = List.of(ps.get(0), ps.get(1), p3Restarted);

    TransactionResult result = coordinator.recover(TX, recoveredTopology);

    assertThat(result).isEqualTo(TransactionResult.COMMITTED);
    assertAllStatus(recoveredTopology, ParticipantStatus.COMMITTED);
    assertThat(recoveredTopology).allSatisfy(p -> assertThat(p.applyCount(TX)).isEqualTo(1));
    assertThat(coordinator.persistedState(TX)).isEqualTo(TransactionState.COMMITTED);

    var events = log.entriesFor(TX).stream().map(LogEntry::event).toList();
    assertThat(events).contains(
        "p3 doCommit 未送达，记入待补偿集合",
        "恢复补偿：p3 doCommit -> COMMITTED",
        "恢复完成，最终结果：COMMITTED");
  }
}
