import { STATUS_KO } from "@/lib/format";

const KIND: Record<string, string> = {
  ACTIVE: "ok", DONE: "ok", VERIFIED: "ok", PENDING_SETUP: "info", IN_PROGRESS: "info", OPEN: "warn", DELAYED: "warn",
  PAUSED: "mute", INACTIVE: "mute", IGNORED: "mute", ON_HOLD: "mute", SUSPENDED: "mute", ERROR: "err", AUTH_EXPIRED: "err",
};

export function StatusChip({ status }: { status: string }) {
  return <span className={`chip c-${KIND[status] ?? "mute"}`}>{STATUS_KO[status] ?? status}</span>;
}

export function Chip({ kind = "mute", children }: { kind?: "ok" | "info" | "warn" | "err" | "mute"; children: React.ReactNode }) {
  return <span className={`chip c-${kind}`}>{children}</span>;
}
