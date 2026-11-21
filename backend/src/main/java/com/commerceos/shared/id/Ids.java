package com.commerceos.shared.id;

import java.security.SecureRandom;
import java.util.UUID;

/** UUIDv7(시간순) 내부 식별자 생성기. 외부 식별자를 PK 로 쓰지 않는다(CLAUDE.md). */
public final class Ids {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        long millis = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long msb = (millis << 16) | (0x7L << 12) | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
