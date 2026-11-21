package com.commerceos.identity.access;

/** 사용자 범위 차원(docs/08 §2-3). 거점 차원은 M3 에서 대상 테이블이 생기면 활성화한다. */
public enum ScopeDimension {
    BUSINESS_ENTITY, CHANNEL_ACCOUNT, FULFILLMENT_NODE
}
