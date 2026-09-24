package com.storynpcs.service;

import com.storynpcs.domain.role.banker.BankVault;

/** Typed result for the canonical whole-stack bank withdrawal operation. */
public record BankWithdrawalOperationResult(
        boolean accepted,
        BankVault.VaultItem item,
        String code
) {
    public static BankWithdrawalOperationResult applied(BankVault.VaultItem item) {
        return new BankWithdrawalOperationResult(true, item, "APPLIED");
    }

    public static BankWithdrawalOperationResult rejected(String code) {
        return new BankWithdrawalOperationResult(false, null,
                code == null || code.isBlank() ? "REJECTED" : code);
    }

    public static BankWithdrawalOperationResult replayed() {
        return new BankWithdrawalOperationResult(false, null, "REPLAYED");
    }

    public static BankWithdrawalOperationResult recoveryRequired(String code) {
        return new BankWithdrawalOperationResult(false, null,
                code == null || code.isBlank() ? "RECOVERY_REQUIRED" : code);
    }
}
