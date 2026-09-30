package com.example.gsb.tpc;

import java.util.HashSet;
import java.util.Set;

/**
 * 协调者为单个事务持久化的阶段状态，崩溃恢复时据此续推。
 */
public final class CoordinatorRecord {

  private final String txId;
  private TransactionState state = TransactionState.STARTED;
  private TransactionResult result;
  private final Set<String> preCommitAcks = new HashSet<>();
  private final Set<String> committedAcks = new HashSet<>();
  private final Set<String> abortedAcks = new HashSet<>();

  public CoordinatorRecord(String txId) {
    this.txId = txId;
  }

  public String txId() {
    return txId;
  }

  public TransactionState state() {
    return state;
  }

  public void state(TransactionState state) {
    this.state = state;
  }

  public TransactionResult result() {
    return result;
  }

  public void result(TransactionResult result) {
    this.result = result;
  }

  public Set<String> preCommitAcks() {
    return preCommitAcks;
  }

  public Set<String> committedAcks() {
    return committedAcks;
  }

  public Set<String> abortedAcks() {
    return abortedAcks;
  }
}
