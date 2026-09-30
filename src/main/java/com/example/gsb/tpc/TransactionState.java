package com.example.gsb.tpc;

/** 协调者侧事务生命周期阶段（按推进顺序单调迁移）。 */
public enum TransactionState {
  STARTED,
  CAN_COMMIT,
  PRE_COMMIT,
  COMMIT,
  COMMITTED,
  ABORTED
}
