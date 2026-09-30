package com.example.gsb.tpc;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 完整事务日志：记录各阶段时间、参与者响应与最终结果。 */
public class TransactionLog {

  private final List<LogEntry> entries = new CopyOnWriteArrayList<>();

  public void record(String txId, String actor, String event) {
    entries.add(new LogEntry(Instant.now(), txId, actor, event));
  }

  public List<LogEntry> entries() {
    return List.copyOf(entries);
  }

  public List<LogEntry> entriesFor(String txId) {
    return entries.stream().filter(e -> e.txId().equals(txId)).toList();
  }
}
