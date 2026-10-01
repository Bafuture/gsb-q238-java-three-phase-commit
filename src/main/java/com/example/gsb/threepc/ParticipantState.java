package com.example.gsb.threepc;

/**
 * 参与者侧事务状态机。
 */
public enum ParticipantState {
    /** 尚未收到 canCommit。 */
    INIT,
    /** canCommit 投了 YES，等待 preCommit。 */
    READY,
    /** 已应答 preCommit ACK，等待 doCommit。 */
    PRE_COMMITTED,
    /** 已提交（终态）。 */
    COMMITTED,
    /** 已中止（终态）。 */
    ABORTED
}
