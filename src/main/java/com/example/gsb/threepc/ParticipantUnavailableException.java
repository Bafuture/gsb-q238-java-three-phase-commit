package com.example.gsb.threepc;

/**
 * 参与者进程崩溃/网络不可达时抛出。
 */
public class ParticipantUnavailableException extends RuntimeException {

    public ParticipantUnavailableException(String message) {
        super(message);
    }
}
