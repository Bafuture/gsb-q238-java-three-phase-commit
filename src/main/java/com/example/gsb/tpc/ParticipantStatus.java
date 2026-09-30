package com.example.gsb.tpc;

/** 参与者侧事务状态机。 */
public enum ParticipantStatus {
  INIT,
  READY,
  PRE_COMMITTED,
  COMMITTED,
  ABORTED
}
