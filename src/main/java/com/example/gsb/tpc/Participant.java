package com.example.gsb.tpc;

/**
 * 三阶段提交中的参与者（内存模拟）。
 *
 * <p>状态机：INIT -> READY -> PRE_COMMITTED -> COMMITTED，任意时刻可 -> ABORTED。
 *
 * <p>幂等：重复的 preCommit / doCommit / abort 返回同一结果，事务不会重复生效
 * （{@link #applyCount(String)} 始终为 1）。
 *
 * <p>超时自决：
 * <ul>
 *   <li>停留在 READY（已投票但没等到 preCommit）时超时 -> 本地 ABORTED；</li>
 *   <li>停留在 PRE_COMMITTED（已确认预提交但没等到 doCommit）时超时 -> 本地自行 COMMITTED。</li>
 * </ul>
 */
public class Participant {

  private final String id;
  private final StateStore store;
  private final TransactionLog txLog;
  private final boolean voteYes;
  private final boolean failPreCommit;
  private volatile boolean crashed;

  public Participant(String id, StateStore store, TransactionLog txLog) {
    this(id, store, txLog, true, false);
  }

  private Participant(String id, StateStore store, TransactionLog txLog,
                      boolean voteYes, boolean failPreCommit) {
    this.id = id;
    this.store = store;
    this.txLog = txLog;
    this.voteYes = voteYes;
    this.failPreCommit = failPreCommit;
  }

  public String id() {
    return id;
  }

  /** 让该参与者在 canCommit 中投 NO。 */
  public Participant refusing() {
    return new Participant(id, store, txLog, false, failPreCommit);
  }

  /** 让该参与者在 preCommit 时失败。 */
  public Participant failingPreCommit() {
    return new Participant(id, store, txLog, voteYes, true);
  }

  /** 模拟进程崩溃：之后所有 RPC 抛异常，内存中无任何可用状态。 */
  public void crash() {
    this.crashed = true;
  }

  /**
   * 模拟重启：返回一个全新实例，崩溃前的内存信息不保留，
   * 只从持久化 store 中恢复状态（配置随实例延续）。
   */
  public Participant restart() {
    return new Participant(id, store, txLog, voteYes, failPreCommit);
  }

  /** 阶段一：canCommit，询问是否可以提交。 */
  public Vote canCommit(String txId) {
    ensureAlive();
    ParticipantRecord rec = record(txId);
    if (rec.status() != ParticipantStatus.INIT) {
      Vote replay = rec.status() == ParticipantStatus.ABORTED ? Vote.NO : Vote.YES;
      txLog.record(txId, id, "canCommit 重复请求，返回既有投票 " + replay + "（幂等）");
      return replay;
    }
    Vote vote = voteYes ? Vote.YES : Vote.NO;
    ParticipantStatus status = vote == Vote.YES ? ParticipantStatus.READY : ParticipantStatus.ABORTED;
    save(txId, new ParticipantRecord(txId, status, 0));
    txLog.record(txId, id, "canCommit -> " + vote);
    return vote;
  }

  /** 阶段二：preCommit，预提交（锁定资源）。 */
  public boolean preCommit(String txId) {
    ensureAlive();
    ParticipantRecord rec = record(txId);
    switch (rec.status()) {
      case PRE_COMMITTED:
      case COMMITTED:
        txLog.record(txId, id, "preCommit 重复请求，返回 true（幂等，不重复生效）");
        return true;
      case ABORTED:
        return false;
      default:
    }
    if (failPreCommit) {
      txLog.record(txId, id, "preCommit -> false（预提交失败）");
      return false;
    }
    save(txId, new ParticipantRecord(txId, ParticipantStatus.PRE_COMMITTED, rec.applyCount()));
    txLog.record(txId, id, "preCommit -> true（已预提交，等待 doCommit）");
    return true;
  }

  /** 阶段三：doCommit，真正提交，事务在此处生效一次。 */
  public boolean doCommit(String txId) {
    ensureAlive();
    ParticipantRecord rec = record(txId);
    if (rec.status() == ParticipantStatus.COMMITTED) {
      txLog.record(txId, id, "doCommit 重复请求，返回 true（幂等，不重复生效）");
      return true;
    }
    if (rec.status() == ParticipantStatus.ABORTED) {
      return false;
    }
    ParticipantRecord updated =
        new ParticipantRecord(txId, ParticipantStatus.COMMITTED, rec.applyCount() + 1);
    save(txId, updated);
    txLog.record(txId, id, "doCommit -> COMMITTED（事务生效）");
    return true;
  }

  /** 中止 / 回滚。 */
  public void abort(String txId) {
    ensureAlive();
    ParticipantRecord rec = record(txId);
    if (rec.status() == ParticipantStatus.ABORTED) {
      txLog.record(txId, id, "abort 重复请求，忽略（幂等）");
      return;
    }
    if (rec.status() == ParticipantStatus.COMMITTED) {
      txLog.record(txId, id, "abort 到达但本节点已 COMMITTED，忽略");
      return;
    }
    save(txId, new ParticipantRecord(txId, ParticipantStatus.ABORTED, rec.applyCount()));
    txLog.record(txId, id, "abort -> ABORTED（已回滚）");
  }

  /**
   * 协调者失联后的超时自决：
   * <ul>
   *   <li>READY 状态超时 -> 中止（此时无人能保证全体投过 YES）；</li>
   *   <li>PRE_COMMITTED 状态超时 -> 自行提交（能进预提交说明全体已 YES 且已预提交）。</li>
   * </ul>
   */
  public TransactionResult onTimeout(String txId) {
    ensureAlive();
    ParticipantRecord rec = record(txId);
    return switch (rec.status()) {
      case PRE_COMMITTED -> {
        txLog.record(txId, id, "超时：处于 PRE_COMMITTED 且协调者失联 -> 自行提交");
        doCommit(txId);
        yield TransactionResult.COMMITTED;
      }
      case READY -> {
        txLog.record(txId, id, "超时：处于 READY 且协调者失联 -> 中止");
        abort(txId);
        yield TransactionResult.ABORTED;
      }
      case COMMITTED -> TransactionResult.COMMITTED;
      default -> TransactionResult.ABORTED;
    };
  }

  public ParticipantStatus status(String txId) {
    return record(txId).status();
  }

  /** 事务真正生效的次数，幂等场景下必须恒为 1。 */
  public int applyCount(String txId) {
    return record(txId).applyCount();
  }

  private void ensureAlive() {
    if (crashed) {
      throw new ParticipantUnavailableException(id);
    }
  }

  private ParticipantRecord record(String txId) {
    ParticipantRecord rec = (ParticipantRecord) store.get("p:" + id + ":" + txId);
    return rec != null ? rec : new ParticipantRecord(txId, ParticipantStatus.INIT, 0);
  }

  private void save(String txId, ParticipantRecord rec) {
    store.put("p:" + id + ":" + txId, rec);
  }
}
