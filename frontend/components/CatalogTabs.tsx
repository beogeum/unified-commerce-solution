import Link from "next/link";

export function CatalogTabs({ active }: { active: "products" | "skus" | "listings" | "boms" | "gwp" }) {
  return (
    <nav aria-label="상품 메뉴" className="row-gap" style={{ borderBottom: "1px solid var(--border)" }}>
      <Link href="/products" className="btn" aria-current={active === "products" ? "page" : undefined} style={active === "products" ? { borderColor: "var(--primary)", color: "var(--primary-strong)" } : undefined}>상품마스터</Link>
      <Link href="/skus" className="btn" aria-current={active === "skus" ? "page" : undefined} style={active === "skus" ? { borderColor: "var(--primary)", color: "var(--primary-strong)" } : undefined}>SKU</Link>
      <Link href="/channel-listings" className="btn" aria-current={active === "listings" ? "page" : undefined} style={active === "listings" ? { borderColor: "var(--primary)", color: "var(--primary-strong)" } : undefined}>채널상품·연결</Link>
      <Link href="/boms" className="btn" aria-current={active === "boms" ? "page" : undefined} style={active === "boms" ? { borderColor: "var(--primary)", color: "var(--primary-strong)" } : undefined}>묶음·세트(BOM)</Link>
      <Link href="/gwp" className="btn" aria-current={active === "gwp" ? "page" : undefined} style={active === "gwp" ? { borderColor: "var(--primary)", color: "var(--primary-strong)" } : undefined}>GWP·증정</Link>
      <span className="ctx">일괄 작업(C6) 예정 · 가격·원가는 SKU 를 눌러 사업자별 정책에서</span>
    </nav>
  );
}
