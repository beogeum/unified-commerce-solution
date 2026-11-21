/** 브라우저 → BFF(/bff/api) 호출. 오류는 백엔드의 한국어 메시지를 그대로 쓴다(기술 상세는 백엔드 로그). */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly requestId?: string,
    public readonly details?: Record<string, unknown>,
  ) {
    super(message);
  }
}

export type ApiResult<T> = { data: T; etag: string | null; nextCursor: string | null };

type Options = { method?: string; body?: unknown; ifMatch?: number; idempotencyKey?: string };

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

export async function api<T>(path: string, opts: Options = {}): Promise<ApiResult<T>> {
  const headers: Record<string, string> = { accept: "application/json", "x-cos-csrf": "1" };
  if (opts.body !== undefined) headers["content-type"] = "application/json";
  if (opts.ifMatch !== undefined) headers["if-match"] = `"${opts.ifMatch}"`;
  if (opts.idempotencyKey) headers["idempotency-key"] = opts.idempotencyKey;
  let res: Response;
  try {
    res = await fetch(`/bff/api${path}`, {
      method: opts.method ?? "GET",
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      cache: "no-store",
    });
  } catch {
    throw new ApiError(0, "NETWORK", "서버에 연결하지 못했습니다. 네트워크 상태를 확인한 뒤 다시 시도하세요.");
  }
  if (res.status === 204) return { data: undefined as T, etag: null, nextCursor: null };
  const json = await res.json().catch(() => null);
  if (!res.ok) {
    const e = json?.error;
    const err = new ApiError(res.status, e?.code ?? "UNKNOWN", e?.message ?? "요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.",
      json?.request_id, e?.details);
    if (res.status === 401 && typeof window !== "undefined") {
      window.location.assign("/login?expired=1");
    }
    if (res.status === 403 && (err.code === "MFA_ENROLLMENT_REQUIRED" || err.code === "MFA_VERIFICATION_REQUIRED") && typeof window !== "undefined") {
      window.location.assign("/login?mfa=1");
    }
    throw err;
  }
  return { data: json?.data as T, etag: res.headers.get("etag"), nextCursor: json?.page?.next_cursor ?? null };
}

export function qs(params: Record<string, string | string[] | undefined | null>): string {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === "") continue;
    if (Array.isArray(v)) v.forEach((x) => sp.append(k, x));
    else sp.set(k, v);
  }
  const s = sp.toString();
  return s ? `?${s}` : "";
}
