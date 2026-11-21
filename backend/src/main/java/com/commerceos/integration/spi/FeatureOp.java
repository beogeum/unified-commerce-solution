package com.commerceos.integration.spi;

/** 지원 기능 키: 기능 코드(capability_feature) × 동작(READ/WRITE/DELETE). */
public record FeatureOp(String feature, String operation) {
    public static FeatureOp read(String feature) {
        return new FeatureOp(feature, "READ");
    }

    public static FeatureOp write(String feature) {
        return new FeatureOp(feature, "WRITE");
    }

    public static FeatureOp delete(String feature) {
        return new FeatureOp(feature, "DELETE");
    }
}
