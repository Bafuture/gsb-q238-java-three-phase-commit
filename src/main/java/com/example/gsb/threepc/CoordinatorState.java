package com.example.gsb.threepc;

/**
 * 协调者侧事务状态机。COMMITTING/ABORTING 表示最终决策已落盘、
 * 正在向参与者投递；COMMITTED/ABORTED 表示全部投递完成（终态）。
 */
public enum CoordinatorState {
    /** 阶段一：收集 canCommit 投票。 */
    CAN_COMMITTING,
    /** 阶段二：全部 YES，投递 preCommit。 */
    PRE_COMMITTING,
    /** 阶段三：提交决策已落盘，投递 doCommit。 */
    COMMITTING,
    /** doCommit 已送达全部参与者（终态）。 */
    COMMITTED,
    /** 中止决策已落盘，投递 abort。 */
    ABORTING,
    /** abort 已送达全部参与者（终态）。 */
    ABORTED
}
