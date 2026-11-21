package com.commerceos.identity.access;

/** 범위 제한의 종류(DEC-F02). WRITE·EXPORT 는 READ 허용을 전제로 한다. */
public enum AccessKind {
    READ, WRITE, EXPORT
}
