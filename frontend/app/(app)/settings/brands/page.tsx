"use client";
import { FormEvent, useState } from "react";
import { Chip } from "@/components/Chip";
import { DataTable } from "@/components/DataTable";
import { Empty, ErrorState, Loading } from "@/components/States";
import { api, ApiError, newIdempotencyKey } from "@/lib/api";
import { Brand, Category } from "@/lib/catalog";
import { useSession } from "@/lib/session";
import { useApi } from "@/lib/useApi";

export default function BrandsPage() {
  const { can } = useSession();
  const brands = useApi<Brand[]>("/brands");
  const categories = useApi<Category[]>("/categories");
  const [msg, setMsg] = useState<{ kind: "ok" | "err"; text: string } | null>(null);
  const create = async (path: string, body: Record<string, unknown>, done: () => void, form: HTMLFormElement) => {
    setMsg(null);
    try {
      await api(path, { method: "POST", idempotencyKey: newIdempotencyKey(), body });
      form.reset();
      done();
      setMsg({ kind: "ok", text: "추가했습니다." });
    } catch (e) {
      setMsg({ kind: "err", text: e instanceof ApiError ? e.message : "추가하지 못했습니다." });
    }
  };
  const onBrand = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    create("/brands", { name: new FormData(e.currentTarget).get("name") }, brands.reload, e.currentTarget);
  };
  const onCategory = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    create("/categories", { name: f.get("name"), parent_id: f.get("parent_id") || undefined }, categories.reload, e.currentTarget);
  };
  const manage = can("catalog.manage");
  return (
    <>
      <div className="page-head"><div><h1>브랜드·카테고리</h1><p>브랜드는 상품 속성이자 손익·분석의 묶는 기준입니다(접근 범위 기준은 아님).</p></div></div>
      {msg && <div className={`note ${msg.kind === "ok" ? "n-info" : "n-err"}`} role={msg.kind === "err" ? "alert" : "status"}>{msg.text}</div>}
      <div className="kpis" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(320px, 1fr))", alignItems: "start" }}>
        <section style={{ display: "flex", flexDirection: "column", gap: 8 }} aria-label="브랜드">
          <h2>브랜드</h2>
          {manage && <form onSubmit={onBrand} className="row-gap"><input className="inp" name="name" aria-label="새 브랜드 이름" required maxLength={100} style={{ flex: 1 }} /><button className="btn pri" type="submit">브랜드 추가</button></form>}
          {brands.loading ? <Loading /> : brands.error ? <ErrorState error={brands.error} onRetry={brands.reload} /> : (
            <DataTable caption="브랜드" rows={brands.data ?? []} rowKey={(r) => r.id} empty={<Empty title="브랜드가 없습니다." />}
              columns={[
                { key: "n", header: "브랜드", render: (r) => <b>{r.name}</b> },
                { key: "c", header: "상품", num: true, render: (r) => r.product_count },
                { key: "s", header: "상태", render: (r) => <Chip kind={r.status === "ACTIVE" ? "ok" : "mute"}>{r.status === "ACTIVE" ? "사용" : "중지"}</Chip> },
              ]} />)}
        </section>
        <section style={{ display: "flex", flexDirection: "column", gap: 8 }} aria-label="카테고리">
          <h2>카테고리</h2>
          {manage && <form onSubmit={onCategory} className="row-gap">
            <select className="inp" name="parent_id" aria-label="상위 카테고리" style={{ flex: 1 }}><option value="">최상위</option>{(categories.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.path}</option>)}</select>
            <input className="inp" name="name" aria-label="새 카테고리 이름" required maxLength={100} style={{ flex: 1 }} /><button className="btn pri" type="submit">카테고리 추가</button></form>}
          {categories.loading ? <Loading /> : categories.error ? <ErrorState error={categories.error} onRetry={categories.reload} /> : (
            <DataTable caption="카테고리" rows={categories.data ?? []} rowKey={(r) => r.id} empty={<Empty title="카테고리가 없습니다." />}
              columns={[{ key: "p", header: "카테고리", render: (r) => r.path }]} />)}
        </section>
      </div>
    </>
  );
}
