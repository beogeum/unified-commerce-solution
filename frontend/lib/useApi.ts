"use client";
import { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "./api";

type Result<T> = { key: string; data: T | null; error: ApiError | null; nextCursor: string | null };

const toError = (e: unknown) => (e instanceof ApiError ? e : new ApiError(0, "UNKNOWN", "요청을 처리하지 못했습니다."));

/**
 * 목록·상세 조회용. 경로가 null 이면 호출하지 않는다.
 * 로딩 여부는 '요청한 경로 + 새로고침 횟수'와 마지막 응답의 키를 비교해 계산한다(효과 안에서 상태를 동기 변경하지 않음).
 * 다시 불러오는 동안에는 직전 데이터를 그대로 보여 준다. 목록 응답에 다음 커서가 있으면 more() 로 이어 붙인다.
 */
export function useApi<T>(path: string | null) {
  const [tick, setTick] = useState(0);
  const [res, setRes] = useState<Result<T> | null>(null);
  const key = path === null ? null : `${tick}|${path}`;
  const reload = useCallback(() => setTick((t) => t + 1), []);
  useEffect(() => {
    if (path === null || key === null) return;
    let alive = true;
    api<T>(path)
      .then((r) => { if (alive) setRes({ key, data: r.data, error: null, nextCursor: r.nextCursor ?? null }); })
      .catch((e: unknown) => { if (alive) setRes((prev) => ({ key, data: prev?.data ?? null, error: toError(e), nextCursor: null })); });
    return () => {
      alive = false;
    };
  }, [key, path]);
  const loading = key !== null && res?.key !== key;
  const nextCursor = loading ? null : res?.nextCursor ?? null;
  const more = useCallback(async () => {
    if (path === null || !nextCursor) return;
    const r = await api<T>(`${path}${path.includes("?") ? "&" : "?"}cursor=${encodeURIComponent(nextCursor)}`);
    setRes((p) => (p && p.key === key && Array.isArray(p.data) && Array.isArray(r.data)
      ? { ...p, data: [...p.data, ...r.data] as T, nextCursor: r.nextCursor ?? null } : p));
  }, [path, key, nextCursor]);
  return { data: res?.data ?? null, error: loading ? null : res?.error ?? null, loading, reload, nextCursor, more };
}
