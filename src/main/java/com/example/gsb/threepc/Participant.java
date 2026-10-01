package com.example.gsb.threepc;

/**
 * 3PC 参与者接口。所有方法必须按事务 ID 幂等：
 * 对同一事务重复调用返回同一结果，且业务效果只生效一次。
 */
public interface Participant {

    String id();

    /** 阶段一：询问是否可提交，返回 YES/NO。 */
    Vote canCommit(String txId);

    /** 阶段二：预提交（持久化 prepare 状态），返回 ACK/NACK。 */
    Ack preCommit(String txId);

    /** 阶段三：真正提交，返回 COMMITTED；若事务已中止则返回 ABORTED。 */
    TransactionOutcome doCommit(String txId);

    /** 回滚/中止。 */
    TransactionOutcome abort(String txId);

    ParticipantState state(String txId);

    /**
     * 协调者失联时的单方面超时决策（3PC 的核心）：
     * PRE_COMMITTED → 继续提交；READY/INIT → 中止。
     */
    TransactionOutcome onCoordinatorTimeout(String txId);
}
