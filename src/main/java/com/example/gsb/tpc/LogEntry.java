package com.example.gsb.tpc;

import java.time.Instant;

/** 事务日志条目：时间、事务、行为者（协调者或参与者）、事件描述。 */
public record LogEntry(Instant timestamp, String txId, String actor, String event) {

  @Override
  public String toString() {
    return timestamp + " [" + txId + "] " + actor + ": " + event;
  }
}
