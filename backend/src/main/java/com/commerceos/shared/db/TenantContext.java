package com.commerceos.shared.db;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 현재 스레드의 DB 행 수준 보안(RLS) 문맥(F8, ADR-0002).
 * <ul>
 *   <li>인증된 요청: 해당 운영그룹 행만 보인다.</li>
 *   <li>시스템 작업(로그인·토큰 확인·작업자·운영그룹 생성): {@link #asSystem} 으로 명시해야 운영그룹 경계를 넘을 수 있다.</li>
 *   <li>문맥이 없으면 운영그룹 테이블의 행이 하나도 보이지 않는다(기본 거부).</li>
 * </ul>
 * 문맥은 DB 연결을 받을 때 적용되므로, 트랜잭션을 시작하기 전에 정해야 한다.
 */
public final class TenantContext {
    record State(UUID workspaceId, boolean system) {
    }

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setWorkspace(UUID workspaceId) {
        CURRENT.set(new State(workspaceId, false));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static UUID workspaceId() {
        State s = CURRENT.get();
        return s == null ? null : s.workspaceId();
    }

    public static boolean isSystem() {
        State s = CURRENT.get();
        return s != null && s.system();
    }

    public static <T> T asSystem(Supplier<T> work) {
        State previous = CURRENT.get();
        CURRENT.set(new State(null, true));
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void asSystem(Runnable work) {
        asSystem(() -> {
            work.run();
            return null;
        });
    }
}
