package com.example.gsb.threepc;

/**
 * 参与者侧单个事务的持久化记录。
 * 两个效果计数器用于证明幂等：重复的 preCommit/doCommit 不重复生效。
 */
public record ParticipantRecord(
        String txId,
        ParticipantState state,
        Vote vote,
        int prepareEffectsApplied,
        int commitEffectsApplied) {

    public static ParticipantRecord init(String txId) {
        return new ParticipantRecord(txId, ParticipantState.INIT, null, 0, 0);
    }

    public ParticipantRecord withState(ParticipantState newState) {
        return new ParticipantRecord(txId, newState, vote, prepareEffectsApplied, commitEffectsApplied);
    }

    public ParticipantRecord withPrepareEffectApplied() {
        return new ParticipantRecord(txId, state, vote, prepareEffectsApplied + 1, commitEffectsApplied);
    }

    public ParticipantRecord withCommitEffectApplied() {
        return new ParticipantRecord(txId, state, vote, prepareEffectsApplied, commitEffectsApplied + 1);
    }
}
