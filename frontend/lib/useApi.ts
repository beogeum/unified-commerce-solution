"use client";
import { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "./api";

/** 목록·상세 조회용. 경로가 null 이면 호출하지 않는다. */
export function useApi<T>(path: string | null) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [loading, setLoading] = useState<boolean>(path !== null);
  const [tick, setTick] = useState(0);
  const reload = useCallback(() => setTick((t) => t + 1), []);
  useEffect(() => {
    if (path === null) return;
    let alive = true;
    setLoading(true);
    setError(null);
    api<T>(path)
      .then((r) => alive && setData(r.data))
      .catch((e: unknown) => alive && setError(e instanceof ApiError ? e : new ApiError(0, "UNKNOWN", "요청을 처리하지 못했습니다.")))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [path, tick]);
  return { data, error, loading, reload };
}
