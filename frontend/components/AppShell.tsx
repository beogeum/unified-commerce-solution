"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { SessionProvider, useSession, Me } from "@/lib/session";

type Item = { href?: string; label: string; perm?: string; soon?: string };
const MENU: { head?: string; items: Item[] }[] = [
  { items: [{ href: "/", label: "홈" }, { href: "/work-queue", label: "업무함", perm: "work_item.read" }] },
  { head: "판매·주문", items: [{ href: "/products", label: "상품", perm: "catalog.read" }, { label: "주문", soon: "M4" }, { label: "CS", soon: "M4" }] },
  { head: "재고·물류", items: [{ label: "재고", soon: "M3" }, { label: "물류·3PL", soon: "M3" }, { label: "발주·매입", soon: "M3" }] },
  { head: "재무", items: [{ label: "정산", soon: "M5" }, { label: "비용·원가", soon: "M5" }, { label: "손익", soon: "M5" }] },
  { head: "관리", items: [
    { href: "/approvals", label: "승인" },
    { href: "/integrations", label: "연동 관리", perm: "integration.read" },
    { href: "/settings/business-entities", label: "사업자 관리", perm: "business_entity.read" },
    { href: "/settings/channel-accounts", label: "판매계정", perm: "channel_account.read" },
    { href: "/settings/brands", label: "브랜드·카테고리", perm: "catalog.read" },
    { href: "/settings/users", label: "사용자·권한", perm: "member.read" },
  ] },
];

function Nav() {
  const path = usePathname();
  const { can } = useSession();
  return (
    <nav className="nav" aria-label="주 메뉴">
      <div className="brand"><i aria-hidden="true">C</i>커머스 운영 OS</div>
      {MENU.map((g, gi) => (
        <div key={gi} style={{ display: "contents" }}>
          {g.head && <div className="navh">{g.head}</div>}
          {g.items.map((it) => {
            if (it.perm && !can(it.perm) && !(it.perm === "member.read" && can("access.manage"))) return null;
            if (!it.href) {
              return <span key={it.label} aria-disabled="true" style={{ display: "flex", alignItems: "center", gap: 6, minHeight: 36, padding: "0 12px", fontSize: 14, color: "#8A9099" }}>
                {it.label}<span className="chip c-mute" style={{ marginLeft: "auto", height: 18 }}>{it.soon} 예정</span></span>;
            }
            const on = it.href === "/" ? path === "/" : path.startsWith(it.href) || (it.href === "/products" && (path.startsWith("/skus") || path.startsWith("/channel-listings") || path.startsWith("/boms") || path.startsWith("/gwp")));
            return <Link key={it.href} href={it.href} aria-current={on ? "page" : undefined}>{it.label}</Link>;
          })}
        </div>
      ))}
    </nav>
  );
}

function TopBar() {
  const { me, entities, selected, setSelected } = useSession();
  const logout = async () => {
    await fetch("/bff/session", { method: "DELETE", headers: { "x-cos-csrf": "1" } }).catch(() => undefined);
    window.location.assign("/login");
  };
  return (
    <header className="topbar">
      <label className="fld" style={{ minWidth: 220 }}>사업자
        <select className="inp" aria-label="사업자 필터" value={selected[0] ?? ""} onChange={(e) => setSelected(e.target.value ? [e.target.value] : [])}>
          <option value="">전체 사업자 ({entities.length})</option>
          {entities.map((e) => <option key={e.id} value={e.id}>{e.display_name}</option>)}
        </select>
      </label>
      <span className="ctx" style={{ flex: 1 }}>접근 범위 안의 사업자만 표시됩니다.</span>
      <div style={{ display: "flex", flexDirection: "column" }}>
        <span style={{ fontSize: 13, fontWeight: 600 }} data-testid="me-name">{me.display_name}</span>
        <span className="ctx">{me.role_codes.join(", ") || "역할 없음"}</span>
      </div>
      <button className="btn" type="button" onClick={logout}>로그아웃</button>
    </header>
  );
}

export function AppShell({ me, children }: { me: Me; children: React.ReactNode }) {
  return (
    <SessionProvider me={me}>
      <div className="shell">
        <Nav />
        <div className="main-col">
          <TopBar />
          <main className="content">{children}</main>
        </div>
      </div>
    </SessionProvider>
  );
}
