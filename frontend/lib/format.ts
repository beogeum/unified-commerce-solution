const dt = new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false });

export function formatDateTime(iso?: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : dt.format(d);
}

/** 금액은 서버가 정수 원 단위 문자열/숫자로 준다. 부동소수 연산을 하지 않고 표시만 한다. */
export function formatWon(v: string | number | null | undefined): string {
  if (v === null || v === undefined || v === "") return "—";
  const s = String(v);
  const neg = s.startsWith("-");
  const digits = neg ? s.slice(1) : s;
  return (neg ? "−" : "") + digits.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

export const LEGAL_TYPE: Record<string, string> = { CORPORATION: "법인", INDIVIDUAL: "개인" };
export const TAX_TYPE: Record<string, string> = { GENERAL: "일반과세", SIMPLIFIED: "간이과세", EXEMPT: "면세" };
export const STATUS_KO: Record<string, string> = {
  ACTIVE: "운영", INACTIVE: "비활성", SUSPENDED: "중지", PENDING_SETUP: "설정 중", ERROR: "오류", AUTH_EXPIRED: "인증 만료",
  PAUSED: "일시중지", DELAYED: "지연", OPEN: "열림", IN_PROGRESS: "처리 중", ON_HOLD: "보류", DONE: "완료", IGNORED: "무시",
};
