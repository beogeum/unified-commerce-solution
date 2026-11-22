export type Node = { id: string; code: string; name: string; logistics_provider_code: string; provider_kind: string; provider_name: string;
  inventory_source: "INTERNAL" | "EXTERNAL"; cutoff_time: string | null; address_note: string | null; status: string;
  shippers: { shipper_account_id: string; shipper_name: string; business_entity_id: string; business_entity_name: string }[]; location_count: number; version: number };
export type Position = { id: string; business_entity_id: string; business_entity_name: string; sku_id: string; sku_code: string; sku_name: string | null;
  node_id: string; node_code: string; node_name: string; inventory_source: string; sellable_on_hand: number; committed: number; reserved: number;
  allocated: number; picking: number; atp: number; non_sellable_on_hand: number; updated_at: string };
export type Stock = { id: string; business_entity_id: string; business_entity_name: string; sku_id: string; sku_code: string; sku_name: string | null;
  node_id: string; node_code: string; location_code: string | null; lot_code: string | null; expires_on: string | null; quality_status: string;
  hold_reason: string | null; on_hand: number; updated_at: string };
export type Txn = { id: string; occurred_at: string; txn_type: string; business_entity_id: string; business_entity_name: string; sku_id: string; sku_code: string;
  node_id: string; node_code: string; stock_id: string | null; quality_status: string | null; on_hand_delta: number; sellable_delta: number;
  committed_delta: number; reservation_id: string | null; reservation_state_from: string | null; reservation_state_to: string | null;
  reference_type: string | null; reference_id: string | null; reason: string | null; actor_name: string | null };
export type Reservation = { id: string; group_id: string; business_entity_id: string; business_entity_name: string; sku_id: string; sku_code: string;
  node_id: string; node_code: string; quantity: number; state: string; demand_type: string; demand_ref: string; expires_at: string | null;
  created_at: string; updated_at: string; version: number };
export type Mismatch = { check: string; position_id: string; business_entity_id: string; sku_id: string; node_id: string; expected: number; actual: number; message_ko: string };

export const QUALITY_KO: Record<string, string> = {
  SELLABLE: "정상", PENDING_INSPECTION: "검수대기", REPACK: "재포장", DEFECTIVE: "불량", DAMAGED: "파손", EXPIRED: "만료",
  DISPOSAL_PENDING: "폐기대기", UNDETERMINED: "미확정",
};
export const TXN_KO: Record<string, string> = {
  RECEIPT: "입고", QUALITY_CHANGE: "품질 변경", RESERVE: "예약", ALLOCATE: "할당", PICK: "피킹", CONSUME: "출고 확정", RELEASE: "확보 해제",
  ADJUSTMENT: "조정", TRANSFER_OUT: "이동 출고", TRANSFER_IN: "이동 입고", ASSEMBLY_OUT: "조립 투입", ASSEMBLY_IN: "조립 완성", DISPOSAL: "폐기",
};
export const RES_STATE: Record<string, ["info" | "ok" | "warn" | "mute", string]> = {
  RESERVED: ["info", "예약"], ALLOCATED: ["warn", "할당"], PICKING: ["warn", "피킹"], CONSUMED: ["ok", "출고 확정"], RELEASED: ["mute", "해제"],
};
export const SOURCE_KO: Record<string, string> = { INTERNAL: "내부 원장", EXTERNAL: "물류사 재고 기준" };
export const PROVIDER_KIND_KO: Record<string, string> = { SELF_WMS: "자체", THIRD_PARTY: "3PL", PLATFORM: "플랫폼 물류" };
export const signed = (n: number) => (n > 0 ? `+${n.toLocaleString("ko-KR")}` : n < 0 ? `−${Math.abs(n).toLocaleString("ko-KR")}` : "0");
export const num = (n: number) => n.toLocaleString("ko-KR");
