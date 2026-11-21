"use client";
import { FormEvent, useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";

type Step = "credentials" | "verify" | "enroll" | "codes";
type Enrollment = { secret: string; otpauth_uri: string };

function go(path: string) {
  window.location.assign(path);
}

export function LoginFlow({ resumeMfa, expired, defaultWorkspace }: { resumeMfa: boolean; expired: boolean; defaultWorkspace: string }) {
  const [step, setStep] = useState<Step>("credentials");
  const [error, setError] = useState<string | null>(expired ? "로그인 시간이 지나 다시 로그인해야 합니다." : null);
  const [busy, setBusy] = useState(false);
  const [enrollment, setEnrollment] = useState<Enrollment | null>(null);
  const [codes, setCodes] = useState<string[]>([]);
  const [useRecovery, setUseRecovery] = useState(false);

  const startEnrollment = async () => {
    const r = await api<Enrollment>("/auth/mfa/totp/enrollment", { method: "POST", body: {} });
    setEnrollment(r.data);
    setStep("enroll");
  };

  const routeByStatus = async (status: string) => {
    if (status === "VERIFICATION_REQUIRED") setStep("verify");
    else if (status === "ENROLLMENT_REQUIRED") await startEnrollment();
    else go("/");
  };

  useEffect(() => {
    if (!resumeMfa) return;
    api<{ mfa_status: string }>("/auth/mfa").then((r) => routeByStatus(r.data.mfa_status)).catch(() => setStep("credentials"));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resumeMfa]);

  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.");
    } finally {
      setBusy(false);
    }
  };

  const onLogin = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    run(async () => {
      const res = await fetch("/bff/session", {
        method: "POST",
        headers: { "content-type": "application/json", "x-cos-csrf": "1" },
        body: JSON.stringify({ workspace_code: f.get("workspace_code"), email: f.get("email"), password: f.get("password") }),
      });
      const json = await res.json().catch(() => null);
      if (res.status !== 201) throw new ApiError(res.status, json?.error?.code ?? "LOGIN_FAILED", json?.error?.message ?? "로그인하지 못했습니다.");
      await routeByStatus(json.data.mfa_status);
    });
  };

  const onVerify = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const v = String(new FormData(e.currentTarget).get("code") ?? "").trim();
    run(async () => {
      await api("/auth/mfa/verify", { method: "POST", body: useRecovery ? { recovery_code: v } : { code: v } });
      go("/");
    });
  };

  const onConfirm = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const v = String(new FormData(e.currentTarget).get("code") ?? "").trim();
    run(async () => {
      const r = await api<{ recovery_codes: string[] }>("/auth/mfa/totp/enrollment/confirm", { method: "POST", body: { code: v } });
      setCodes(r.data.recovery_codes);
      setStep("codes");
    });
  };

  return (
    <div className="auth-wrap">
      <main className="card auth-card">
        <div className="brand" style={{ padding: 0 }}><i aria-hidden="true">C</i><h1 style={{ fontSize: 20 }}>커머스 운영 OS</h1></div>
        {error && <div className="note n-err" role="alert">{error}</div>}

        {step === "credentials" && (
          <form onSubmit={onLogin} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            <label className="fld">운영그룹 코드<input className="inp" name="workspace_code" required defaultValue={defaultWorkspace} autoComplete="organization" /></label>
            <label className="fld">이메일<input className="inp" name="email" type="email" required autoComplete="username" /></label>
            <label className="fld">비밀번호<input className="inp" name="password" type="password" required autoComplete="current-password" /></label>
            <button className="btn pri" type="submit" disabled={busy}>{busy ? "확인 중…" : "로그인"}</button>
            <p className="ctx" style={{ margin: 0 }}>5회 틀리면 15분 동안 로그인할 수 없습니다.</p>
          </form>
        )}

        {step === "verify" && (
          <form onSubmit={onVerify} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            <h2>2단계 인증</h2>
            <p className="ctx" style={{ margin: 0 }}>{useRecovery ? "보관해 둔 복구 코드 하나를 입력하세요. 한 번 쓴 코드는 다시 쓸 수 없습니다." : "OTP 앱에 표시된 6자리 숫자를 입력하세요."}</p>
            <label className="fld">{useRecovery ? "복구 코드" : "인증 코드"}
              <input className="inp" name="code" required autoComplete="one-time-code" inputMode={useRecovery ? "text" : "numeric"} maxLength={useRecovery ? 11 : 6} />
            </label>
            <button className="btn pri" type="submit" disabled={busy}>확인</button>
            <button className="btn" type="button" onClick={() => setUseRecovery((v) => !v)}>{useRecovery ? "OTP 코드 입력" : "복구 코드로 인증"}</button>
          </form>
        )}

        {step === "enroll" && enrollment && (
          <form onSubmit={onConfirm} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            <h2>2단계 인증 등록</h2>
            <p className="ctx" style={{ margin: 0 }}>이 역할은 2단계 인증이 필요합니다. OTP 앱(Google Authenticator 등)에서 &lsquo;설정 키 입력&rsquo;을 골라 아래 키를 입력하세요.</p>
            <div className="card pad mono" data-testid="totp-secret" style={{ wordBreak: "break-all", fontSize: 15 }}>{enrollment.secret.replace(/(.{4})/g, "$1 ").trim()}</div>
            <details><summary className="ctx">설정 URI 보기</summary><div className="mono ctx" style={{ wordBreak: "break-all" }}>{enrollment.otpauth_uri}</div></details>
            <label className="fld">앱에 표시된 6자리 코드<input className="inp" name="code" required inputMode="numeric" maxLength={6} autoComplete="one-time-code" /></label>
            <button className="btn pri" type="submit" disabled={busy}>등록 완료</button>
          </form>
        )}

        {step === "codes" && (
          <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            <h2>복구 코드</h2>
            <div className="note n-warn">휴대폰을 잃어버렸을 때 쓰는 일회용 코드입니다. 지금만 표시되니 안전한 곳에 보관하세요.</div>
            <div className="codes" data-testid="recovery-codes">{codes.map((c) => <span key={c}>{c}</span>)}</div>
            <button className="btn pri" type="button" onClick={() => go("/")}>보관했습니다 — 시작하기</button>
          </div>
        )}
      </main>
    </div>
  );
}
