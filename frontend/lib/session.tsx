"use client";
import { createContext, useContext, useEffect, useMemo, useState } from "react";
import { api } from "./api";

export type Me = {
  user_id: string; member_id: string; workspace_id: string; display_name: string;
  role_codes: string[]; permissions: string[]; access_policy_version: number;
  scope: Record<string, { unrestricted: boolean; business_entity_ids: string[]; channel_account_count: number }>;
};
export type EntityOption = { id: string; display_name: string };

type Ctx = {
  me: Me;
  can: (perm: string) => boolean;
  entities: EntityOption[];
  /** 비어 있으면 전체(접근 범위 안의) 사업자 */
  selected: string[];
  setSelected: (ids: string[]) => void;
  /** 사업자를 등록·변경한 뒤 헤더 필터·범위 화면 목록을 다시 읽는다. */
  refreshEntities: () => void;
};

const SessionContext = createContext<Ctx | null>(null);
const KEY = "cos.business-filter";

export function SessionProvider({ me, children }: { me: Me; children: React.ReactNode }) {
  const [entities, setEntities] = useState<EntityOption[]>([]);
  const [selected, setSelectedState] = useState<string[]>([]);
  const [version, setVersion] = useState(0);
  useEffect(() => {
    try {
      const saved = JSON.parse(window.localStorage.getItem(`${KEY}.${me.member_id}`) ?? "[]");
      // eslint-disable-next-line react-hooks/set-state-in-effect -- 서버 렌더와 첫 화면을 같게 두고, 하이드레이션 후 브라우저 값(주소창·로컬 저장소)을 한 번 읽는다
      if (Array.isArray(saved)) setSelectedState(saved.filter((x) => typeof x === "string"));
    } catch { /* 저장소를 못 쓰면 전체 사업자 */ }
  }, [me.member_id]);
  useEffect(() => {
    if (me.permissions.includes("business_entity.read")) {
      api<EntityOption[]>("/business-entities?limit=200&status=ACTIVE").then((r) => setEntities(r.data)).catch(() => setEntities([]));
    }
  }, [me.permissions, version]);
  // 범위가 바뀌어 더 이상 볼 수 없는 사업자가 저장돼 있으면 제외
  const effective = useMemo(() => selected.filter((id) => entities.length === 0 || entities.some((e) => e.id === id)), [selected, entities]);
  const value = useMemo<Ctx>(() => ({
    me,
    can: (p) => me.permissions.includes(p),
    entities,
    selected: effective,
    refreshEntities: () => setVersion((v) => v + 1),
    setSelected: (ids) => {
      setSelectedState(ids);
      try { window.localStorage.setItem(`${KEY}.${me.member_id}`, JSON.stringify(ids)); } catch { /* 무시 */ }
    },
  }), [me, entities, effective]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): Ctx {
  const c = useContext(SessionContext);
  if (!c) throw new Error("SessionProvider missing");
  return c;
}
