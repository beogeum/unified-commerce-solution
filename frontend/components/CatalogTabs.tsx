import Link from "next/link";

export function CatalogTabs({ active }: { active: "products" | "skus" }) {
  return (
    <nav aria-label="상품 메뉴" className="row-gap" style={{ borderBottom: "1px solid var(--border)" }}>
      <Link href="/products" className="btn" aria-current={active === "products" ? "page" : undefined} style={active === "products" ? { borderColor: "var(--primary)", color: "var(--primary-strong)" } : undefined}>상품마스터</Link>
      <Link href="/skus" className="btn" aria-current={active === "skus" ? "page" : undefined} style={active === "skus" ? { borderColor: "var(--primary)", color: "var(--primary-strong)" } : undefined}>SKU</Link>
      <span className="ctx">묶음·세트(C4) · GWP(C5) · 채널상품 연결(C3) · 가격·판매정책(C2) · 일괄 작업(C6) 예정</span>
    </nav>
  );
}
