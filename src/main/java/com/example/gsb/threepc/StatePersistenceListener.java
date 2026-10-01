package com.example.gsb.threepc;

/**
 * 协调者状态每次落盘后的回调。用于观测，也用于故障注入——
 * 测试借此在指定阶段模拟协调者崩溃（抛出异常即“进程死亡”，
 * 但状态已经持久化，之后可由新实例续推）。
 */
@FunctionalInterface
public interface StatePersistenceListener {

    void onStatePersisted(TransactionRecord record);
}
