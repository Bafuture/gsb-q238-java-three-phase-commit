package com.example.gsb.tpc;

import java.util.List;

/**
 * 三阶段提交协调者（内存模拟）。
 *
 * <p>流程：canCommit（全员 YES）-> preCommit（全员 ACK）-> doCommit（全员提交）。
 * 任一步出现拒绝 / 失败 / 不可达即进入 abort 并通知全员回滚。
 *
 * <p>每个阶段状态在发送消息前先持久化，因此协调者“重启”后 {@link #recover}
 * 可从最后持久化的阶段继续推进，而不是重新走 canCommit。
 */
public class Coordinator {

  private final StateStore store;
  private final TransactionLog txLog;

  public Coordinator(StateStore store, TransactionLog txLog) {
    this.store = store;
    this.txLog = txLog;
  }

  /** 一次性跑完整三阶段流程。 */
  public TransactionResult execute(String txId, List<Participant> participants) {
    CoordinatorRecord rec = record(txId);
    if (rec.state() == TransactionState.COMMITTED) {
      txLog.record(txId, "coordinator", "execute 重复调用，事务已 COMMITTED（幂等）");
      return TransactionResult.COMMITTED;
    }
    if (rec.state() == TransactionState.ABORTED) {
      txLog.record(txId, "coordinator", "execute 重复调用，事务已 ABORTED（幂等）");
      return TransactionResult.ABORTED;
    }
    if (!askCanCommit(txId, participants)) {
      return TransactionResult.ABORTED;
    }
    if (!sendPreCommit(txId, participants)) {
      return TransactionResult.ABORTED;
    }
    return sendDoCommit(txId, participants);
  }

  /** 阶段一：询问所有参与者是否可提交。任一拒绝即中止。 */
  public boolean askCanCommit(String txId, List<Participant> participants) {
    CoordinatorRecord rec = record(txId);
    rec.state(TransactionState.CAN_COMMIT);
    save(rec);
    txLog.record(txId, "coordinator", "进入阶段 CAN_COMMIT，向 " + participants.size() + " 个参与者询问");

    boolean allYes = true;
    for (Participant p : participants) {
      Vote vote;
      try {
        vote = p.canCommit(txId);
      } catch (ParticipantUnavailableException e) {
        vote = Vote.NO;
        txLog.record(txId, "coordinator", p.id() + " 不可达，按拒绝处理");
      }
      txLog.record(txId, "coordinator", "收到 " + p.id() + " 的投票：" + vote);
      if (vote == Vote.NO) {
        allYes = false;
      }
    }
    if (!allYes) {
      abortAll(txId, participants);
      return false;
    }
    return true;
  }

  /** 阶段二：通知所有参与者预提交。任一失败即中止。 */
  public boolean sendPreCommit(String txId, List<Participant> participants) {
    CoordinatorRecord rec = record(txId);
    rec.state(TransactionState.PRE_COMMIT);
    save(rec);
    txLog.record(txId, "coordinator", "进入阶段 PRE_COMMIT，全员已同意，发送预提交");

    boolean allAcked = true;
    for (Participant p : participants) {
      boolean ok;
      try {
        ok = p.preCommit(txId);
      } catch (ParticipantUnavailableException e) {
        ok = false;
        txLog.record(txId, "coordinator", p.id() + " 不可达，预提交未确认");
      }
      txLog.record(txId, "coordinator", p.id() + " preCommit 响应：" + (ok ? "ACK" : "NACK"));
      if (ok) {
        rec.preCommitAcks().add(p.id());
        save(rec);
      } else {
        allAcked = false;
      }
    }
    if (!allAcked) {
      abortAll(txId, participants);
      return false;
    }
    return true;
  }

  /** 阶段三：正式提交。不可达的参与者记入待补偿集合，恢复后补齐。 */
  public TransactionResult sendDoCommit(String txId, List<Participant> participants) {
    CoordinatorRecord rec = record(txId);
    rec.state(TransactionState.COMMIT);
    save(rec);
    txLog.record(txId, "coordinator", "进入阶段 COMMIT，做出提交决定并发送 doCommit");

    for (Participant p : participants) {
      if (rec.committedAcks().contains(p.id())) {
        continue;
      }
      try {
        p.doCommit(txId);
        rec.committedAcks().add(p.id());
        txLog.record(txId, "coordinator", p.id() + " doCommit 响应：COMMITTED");
      } catch (ParticipantUnavailableException e) {
        txLog.record(txId, "coordinator", p.id() + " doCommit 未送达，记入待补偿集合");
      }
    }
    save(rec);

    boolean allCommitted = rec.committedAcks().size() == participants.size();
    if (allCommitted) {
      rec.state(TransactionState.COMMITTED);
      rec.result(TransactionResult.COMMITTED);
      save(rec);
      txLog.record(txId, "coordinator", "最终结果：COMMITTED");
    } else {
      txLog.record(txId, "coordinator", "提交决定已做出，等待故障参与者恢复后补齐");
    }
    return TransactionResult.COMMITTED;
  }

  private void abortAll(String txId, List<Participant> participants) {
    CoordinatorRecord rec = record(txId);
    rec.state(TransactionState.ABORTED);
    rec.result(TransactionResult.ABORTED);
    save(rec);
    txLog.record(txId, "coordinator", "做出中止决定，通知全员 abort");

    for (Participant p : participants) {
      try {
        p.abort(txId);
        rec.abortedAcks().add(p.id());
      } catch (ParticipantUnavailableException e) {
        txLog.record(txId, "coordinator", p.id() + " abort 未送达，记入待补偿集合");
      }
    }
    save(rec);
    txLog.record(txId, "coordinator", "最终结果：ABORTED");
  }

  /**
   * 协调者重启后的恢复入口：根据持久化的阶段状态续推。
   * <ul>
   *   <li>COMMITTED / COMMIT：提交决定已做出（或即将做出），向未提交者补发 doCommit；</li>
   *   <li>PRE_COMMIT：仅当全员都已确认预提交才可提交，否则中止；</li>
   *   <li>CAN_COMMIT / STARTED：尚未做出任何决定，安全中止。</li>
   * </ul>
   */
  public TransactionResult recover(String txId, List<Participant> participants) {
    CoordinatorRecord rec = record(txId);
    txLog.record(txId, "coordinator", "重启恢复，从持久化阶段 " + rec.state() + " 继续推进");
    return switch (rec.state()) {
      case COMMITTED, COMMIT -> completeCommit(txId, participants);
      case PRE_COMMIT -> {
        boolean everyonePreCommitted = participants.stream()
            .allMatch(p -> rec.preCommitAcks().contains(p.id()));
        if (everyonePreCommitted) {
          yield completeCommit(txId, participants);
        }
        abortAll(txId, participants);
        yield TransactionResult.ABORTED;
      }
      case ABORTED -> completeAbort(txId, participants);
      case CAN_COMMIT, STARTED -> {
        abortAll(txId, participants);
        yield TransactionResult.ABORTED;
      }
    };
  }

  private TransactionResult completeCommit(String txId, List<Participant> participants) {
    CoordinatorRecord rec = record(txId);
    for (Participant p : participants) {
      if (rec.committedAcks().contains(p.id())) {
        continue;
      }
      try {
        p.doCommit(txId);
        rec.committedAcks().add(p.id());
        txLog.record(txId, "coordinator", "恢复补偿：" + p.id() + " doCommit -> COMMITTED");
      } catch (ParticipantUnavailableException e) {
        txLog.record(txId, "coordinator", "恢复补偿：" + p.id() + " 仍不可达");
      }
    }
    if (rec.committedAcks().size() == participants.size()) {
      rec.state(TransactionState.COMMITTED);
      rec.result(TransactionResult.COMMITTED);
    }
    save(rec);
    if (rec.state() == TransactionState.COMMITTED) {
      txLog.record(txId, "coordinator", "恢复完成，最终结果：COMMITTED");
    }
    return rec.result();
  }

  private TransactionResult completeAbort(String txId, List<Participant> participants) {
    CoordinatorRecord rec = record(txId);
    for (Participant p : participants) {
      if (rec.abortedAcks().contains(p.id())) {
        continue;
      }
      try {
        p.abort(txId);
        rec.abortedAcks().add(p.id());
        txLog.record(txId, "coordinator", "恢复补偿：" + p.id() + " abort -> ABORTED");
      } catch (ParticipantUnavailableException e) {
        txLog.record(txId, "coordinator", "恢复补偿：" + p.id() + " 仍不可达");
      }
    }
    save(rec);
    txLog.record(txId, "coordinator", "恢复完成，最终结果：ABORTED");
    return TransactionResult.ABORTED;
  }

  public TransactionState persistedState(String txId) {
    return record(txId).state();
  }

  private CoordinatorRecord record(String txId) {
    CoordinatorRecord rec = (CoordinatorRecord) store.get("c:" + txId);
    if (rec == null) {
      rec = new CoordinatorRecord(txId);
      store.put("c:" + txId, rec);
    }
    return rec;
  }

  private void save(CoordinatorRecord rec) {
    store.put("c:" + rec.txId(), rec);
  }
}
