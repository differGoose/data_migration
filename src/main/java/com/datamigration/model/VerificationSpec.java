package com.datamigration.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Opt-in, exact-size random sampling of migrated fields. */
public record VerificationSpec(boolean enabled, BigDecimal samplePercent) {
    public static final VerificationSpec DISABLED = new VerificationSpec(false, null);
    public VerificationSpec {
        if (enabled && (samplePercent == null || samplePercent.signum() <= 0 || samplePercent.compareTo(BigDecimal.valueOf(100)) > 0))
            throw new IllegalArgumentException("verification.sample-percent must be greater than zero and at most 100");
    }
    public long samples(long total) {
        return samplePercent.multiply(BigDecimal.valueOf(total)).divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING).longValueExact();
    }
}
