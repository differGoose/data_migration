package com.datamigration.verification;

import java.sql.SQLException;

/** A confirmed mismatch, unlike a query/connection/conversion failure. Contains no business values. */
public final class VerificationMismatch extends SQLException {
    private final String code;
    public VerificationMismatch(String code) {
        super("Verification mismatch", "P0006");
        if (!java.util.Set.of("VERIFICATION_COUNT_MISMATCH", "VERIFICATION_CONTENT_MISMATCH").contains(code))
            throw new IllegalArgumentException("Unknown verification mismatch code");
        this.code = code;
    }
    public String code() { return code; }
}
