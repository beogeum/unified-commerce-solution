"use client";
import { ReactNode, useEffect, useId, useRef } from "react";

function useEscape(onClose: () => void) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [onClose]);
}

export function Drawer({ title, onClose, children, footer }: { title: string; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  const id = useId();
  const ref = useRef<HTMLDivElement>(null);
  useEscape(onClose);
  useEffect(() => ref.current?.focus(), []);
  return (
    <>
      <div className="overlay" onClick={onClose} />
      <div className="drawer" role="dialog" aria-modal="true" aria-labelledby={id} ref={ref} tabIndex={-1}>
        <header><h2 id={id} style={{ fontSize: 18 }}>{title}</h2><button className="btn" type="button" onClick={onClose} aria-label="닫기">닫기</button></header>
        <div className="body">{children}</div>
        {footer && <footer>{footer}</footer>}
      </div>
    </>
  );
}

/** 민감·파괴 작업 확인. impact 에 영향도 미리보기를 넣는다(CLAUDE.md). */
export function ConfirmModal({ title, impact, confirmLabel, danger, busy, onConfirm, onClose }: {
  title: string; impact: [string, string][]; confirmLabel: string; danger?: boolean; busy?: boolean;
  onConfirm: () => void; onClose: () => void;
}) {
  const id = useId();
  useEscape(onClose);
  return (
    <>
      <div className="overlay" onClick={onClose} />
      <div className="modal" role="alertdialog" aria-modal="true" aria-labelledby={id}>
        <header><h2 id={id}>{title}</h2></header>
        <div className="body">{impact.map(([k, v]) => <div className="kv" key={k}><span>{k}</span><span>{v}</span></div>)}</div>
        <footer>
          <button className="btn" type="button" onClick={onClose}>취소</button>
          <button className={`btn ${danger ? "dan" : "pri"}`} type="button" onClick={onConfirm} disabled={busy}>{busy ? "처리 중…" : confirmLabel}</button>
        </footer>
      </div>
    </>
  );
}
