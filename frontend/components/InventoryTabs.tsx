import Link from "next/link";

const TABS: [string, string, string][] = [
  ["positions", "/inventory", "통합재고"],
  ["ledger", "/inventory/ledger", "재고원장"],
  ["reservations", "/inventory/reservations", "예약·할당"],
];

export function InventoryTabs({ active }: { active: "positions" | "ledger" | "reservations" }) {
  return (
    <nav aria-label="재고 메뉴" className="row-gap" style={{ borderBottom: "1px solid var(--border)" }}>
      {TABS.map(([k, href, label]) => (
        <Link key={k} href={href} className="btn" aria-current={active === k ? "page" : undefined}
          style={active === k ? { borderColor: "var(--primary)", color: "var(--primary-strong)" } : undefined}>{label}</Link>))}
      <span className="ctx">재고이동·조정·실사·채널 배분은 다음 단계(M3 I4~I6)에서 엽니다</span>
    </nav>
  );
}
