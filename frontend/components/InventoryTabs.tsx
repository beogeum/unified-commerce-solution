import Link from "next/link";

const TABS: [string, string, string][] = [
  ["positions", "/inventory", "통합재고"],
  ["ledger", "/inventory/ledger", "재고원장"],
  ["reservations", "/inventory/reservations", "예약·할당"],
  ["external", "/inventory/external", "외부 재고 동기화"],
  ["counts", "/inventory/counts", "재고조정·실사"],
  ["transfers", "/inventory/transfers", "재고 이동"],
  ["policy", "/inventory/policy", "재고정책"],
];

export function InventoryTabs({ active }: { active: "positions" | "ledger" | "reservations" | "external" | "counts" | "transfers" | "policy" }) {
  return (
    <nav aria-label="재고 메뉴" className="row-gap" style={{ borderBottom: "1px solid var(--border)" }}>
      {TABS.map(([k, href, label]) => (
        <Link key={k} href={href} className="btn" aria-current={active === k ? "page" : undefined}
          style={active === k ? { borderColor: "var(--primary)", color: "var(--primary-strong)" } : undefined}>{label}</Link>))}
    </nav>
  );
}
