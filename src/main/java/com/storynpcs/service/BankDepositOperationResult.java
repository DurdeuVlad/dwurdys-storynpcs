package com.storynpcs.service;

/** Typed result for a journaled held-item bank deposit. */
public record BankDepositOperationResult(Outcome outcome, String code, int slot) {
    public enum Outcome {
        APPLIED,
        REPLAYED,
        REJECTED,
        RECOVERY_REQUIRED
    }

    public boolean accepted() {
        return outcome == Outcome.APPLIED || outcome == Outcome.REPLAYED;
    }

    public static BankDepositOperationResult applied(int slot) {
        return new BankDepositOperationResult(Outcome.APPLIED, "APPLIED", slot);
    }

    public static BankDepositOperationResult replayed(int slot) {
        return new BankDepositOperationResult(Outcome.REPLAYED, "REPLAYED", slot);
    }

    public static BankDepositOperationResult rejected(String code) {
        return new BankDepositOperationResult(Outcome.REJECTED, code, -1);
    }

    public static BankDepositOperationResult recoveryRequired(String code) {
        return new BankDepositOperationResult(Outcome.RECOVERY_REQUIRED, code, -1);
    }
}
