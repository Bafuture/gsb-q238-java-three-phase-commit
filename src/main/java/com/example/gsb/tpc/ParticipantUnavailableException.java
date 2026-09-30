package com.example.gsb.tpc;

/** 参与者已崩溃 / 不可达时抛出，模拟网络或进程故障。 */
public class ParticipantUnavailableException extends RuntimeException {
  public ParticipantUnavailableException(String participantId) {
    super("participant unavailable: " + participantId);
  }
}
