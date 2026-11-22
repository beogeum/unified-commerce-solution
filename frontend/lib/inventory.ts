export type Node = { id: string; code: string; name: string; logistics_provider_code: string; provider_kind: string; provider_name: string;
  inventory_source: "INTERNAL" | "EXTERNAL"; cutoff_time: string | null; address_note: string | null; status: string;
  shippers: { shipper_account_id: string; shipper_name: string; business_entity_id: string; business_entity_name: string }[]; location_count: number;
  quantity_semantic: string; quantity_semantic_evidence: string | null; quantity_semantic_verified_at: string | null; restricted_sell_buffer: number;
  snapshot_max_age_minutes: number; routing_priority: number; tracking_sla_hours: number; version: number };
export type Position = { id: string; business_entity_id: string; business_entity_name: string; sku_id: string; sku_code: string; sku_name: string | null;
  node_id: string; node_code: string; node_name: string; inventory_source: string; sellable_on_hand: number; committed: number; reserved: number;
  allocated: number; picking: number; atp: number; non_sellable_on_hand: number; restricted: boolean; restriction_reason: string | null;
  restricted_hold: number; external_as_of: string | null; updated_at: string };
export type Stock = { id: string; business_entity_id: string; business_entity_name: string; sku_id: string; sku_code: string; sku_name: string | null;
  node_id: string; node_code: string; location_code: string | null; lot_code: string | null; expires_on: string | null; quality_status: string;
  hold_reason: string | null; on_hand: number; updated_at: string };
export type Txn = { id: string; occurred_at: string; txn_type: string; business_entity_id: string; business_entity_name: string; sku_id: string; sku_code: string;
  node_id: string; node_code: string; stock_id: string | null; quality_status: string | null; on_hand_delta: number; sellable_delta: number;
  committed_delta: number; reservation_id: string | null; reservation_state_from: string | null; reservation_state_to: string | null;
  reference_type: string | null; reference_id: string | null; reason: string | null; actor_name: string | null };
export type Reservation = { id: string; group_id: string; business_entity_id: string; business_entity_name: string; sku_id: string; sku_code: string;
  node_id: string; node_code: string; quantity: number; state: string; demand_type: string; demand_ref: string; expires_at: string | null;
  external_ack_at: string | null; external_ref: string | null; consumed_at: string | null; created_at: string; updated_at: string; version: number };
export type Snapshot = { id: string; node_id: string; node_code: string; node_name: string; business_entity_id: string; business_entity_name: string;
  source: string; source_key: string; as_of: string | null; fetched_at: string; complete: boolean; quantity_semantic: string; mapping_version: string;
  status: string; line_count: number; issue_count: number; file_id: string | null; received_by_name: string | null; created_at: string };
export type SnapshotResult = { sku_id: string; sku_code: string; external_sellable: number; external_non_sellable: number; reflected_quantity: number;
  shipped_after_quantity: number; committed_at_apply: number; sellable_before: number; sellable_after: number; non_sellable_before: number;
  non_sellable_after: number; certain: boolean; restriction_reason: string | null };
export type SnapshotLine = { line_no: number; external_sku_code: string; sku_id: string | null; sku_code: string | null; lot_code: string | null;
  expires_on: string | null; location_code: string | null; external_status: string | null; sellable: boolean; quantity: number; issue: string | null };
export type SnapshotDetail = { snapshot: Snapshot; results: SnapshotResult[]; lines: SnapshotLine[] };
export type Mismatch = { check: string; position_id: string; business_entity_id: string; sku_id: string; node_id: string; expected: number; actual: number; message_ko: string };

export const QUALITY_KO: Record<string, string> = {
  SELLABLE: "정상", PENDING_INSPECTION: "검수대기", REPACK: "재포장", DEFECTIVE: "불량", DAMAGED: "파손", EXPIRED: "만료",
  DISPOSAL_PENDING: "폐기대기", UNDETERMINED: "미확정",
};
export const TXN_KO: Record<string, string> = {
  RECEIPT: "입고", QUALITY_CHANGE: "품질 변경", RESERVE: "예약", ALLOCATE: "할당", PICK: "피킹", CONSUME: "출고 확정", RELEASE: "확보 해제",
  EXTERNAL_SYNC: "외부 재고 동기화", ADJUSTMENT: "조정", TRANSFER_OUT: "이동 출고", TRANSFER_IN: "이동 입고", ASSEMBLY_OUT: "조립 투입", ASSEMBLY_IN: "조립 완성", DISPOSAL: "폐기",
};
export const RES_STATE: Record<string, ["info" | "ok" | "warn" | "mute", string]> = {
  RESERVED: ["info", "예약"], ALLOCATED: ["warn", "할당"], PICKING: ["warn", "피킹"], CONSUMED: ["ok", "출고 확정"], RELEASED: ["mute", "해제"],
};
export const SOURCE_KO: Record<string, string> = { INTERNAL: "내부 원장", EXTERNAL: "물류사 재고 기준" };
export const PROVIDER_KIND_KO: Record<string, string> = { SELF_WMS: "자체", THIRD_PARTY: "3PL", PLATFORM: "플랫폼 물류" };
export const signed = (n: number) => (n > 0 ? `+${n.toLocaleString("ko-KR")}` : n < 0 ? `−${Math.abs(n).toLocaleString("ko-KR")}` : "0");
export const num = (n: number) => n.toLocaleString("ko-KR");
export const SEMANTIC_KO: Record<string, string> = {
  UNKNOWN: "미확인(보수적 판매 제한)", ON_HAND: "총재고(출고 전 수량 포함)", AVAILABLE: "가용(접수된 출고지시 제외)", POST_OUTBOUND_ESTIMATE: "출고 후 예상",
};
export const RESTRICTION_KO: Record<string, string> = {
  QUANTITY_SEMANTIC_UNKNOWN: "수량 의미 미확인", WATERMARK_MISSING: "기준시각 없음", SNAPSHOT_STALE: "자료 오래됨", EXTERNAL_SHORTAGE: "외부 재고 부족(과확보 의심)",
};
export const SNAPSHOT_STATUS: Record<string, ["ok" | "warn" | "mute", string]> = {
  APPLIED: ["ok", "반영"], APPLIED_RESTRICTED: ["warn", "반영 · 판매 제한"], IGNORED_OUT_OF_ORDER: ["mute", "역순 도착 · 미반영"],
};
export const LINE_ISSUE_KO: Record<string, string> = { SKU_UNMAPPED: "SKU 미연결", SKU_NOT_INVENTORY_MANAGED: "재고관리 대상 아님" };
export function ago(iso: string | null): string {
  if (!iso) return "기준시각 없음";
  const m = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
  if (m < 1) return "방금";
  if (m < 60) return `${m}분 전`;
  if (m < 1440) return `${Math.floor(m / 60)}시간 전`;
  return `${Math.floor(m / 1440)}일 전`;
}
export type CountPolicy = { id: string; business_entity_id: string; node_id: string | null; node_name: string | null; zone: string | null; version: number;
  removed: boolean; qty_threshold: number | null; rate_threshold_pct: string | null; cost_threshold: string | null; combine: string; on_exceed: string;
  recount_assignee: string; created_by_name: string | null; created_at: string };
export type StockCount = { id: string; count_no: string; business_entity_id: string; business_entity_name: string; node_id: string; node_name: string;
  scope: string; zone: string | null; sku_ids: string[] | null; blind: boolean; policy_id: string; policy_version: number; status: string; round: number;
  snapshot_at: string; note: string | null; requested_by: string; requested_by_name: string | null; counters: { member_id: string; name: string }[];
  line_count: number; counted_lines: number; diff_lines: number; pending_lines: number; recount_required_lines: number; submitted_at: string | null;
  completed_at: string | null; version: number };
export type CountLine = { id: string; line_no: number; sku_id: string; sku_code: string; sku_name: string | null; location_code: string | null; zone: string | null;
  lot_code: string | null; quality_status: string; hold_reason: string | null; system_qty: number | null; found_in_count: boolean; counted_qty: number | null;
  counted_by_name: string | null; recount_count: number; status: string; diff: number | null; diff_rate_pct: string | null; unit_cost: string | null;
  cost_impact: string | null; exceeds_policy: boolean | null; recount_recommended: boolean; policy_id: string; policy_version: number;
  decided_by_name: string | null; decision_reason: string | null };
export type CountDetail = { count: StockCount; lines: CountLine[]; impact: Record<string, unknown> };
export type Transfer = { id: string; transfer_no: string; business_entity_id: string; business_entity_name: string; from_node_id: string; from_node_name: string;
  to_node_id: string; to_node_name: string; to_inventory_source: string; status: string; note: string | null; shipped_at: string; received_at: string | null;
  requested_by_name: string | null; total_quantity: number; in_transit_quantity: number;
  lines: { line_no: number; sku_id: string; sku_code: string; lot_code: string | null; quantity: number; received_qty: number | null; received_quality: string | null }[];
  version: number };
export const COUNT_STATUS: Record<string, ["info" | "warn" | "ok" | "mute", string]> = {
  COUNTING: ["info", "카운트 중"], REVIEW: ["warn", "차이 검토"], COMPLETED: ["ok", "완료"], CANCELLED: ["mute", "취소"],
};
export const LINE_STATUS: Record<string, ["info" | "warn" | "ok" | "err" | "mute", string]> = {
  COUNTING: ["info", "카운트 대기"], RECOUNT: ["info", "재실사 중"], MATCHED: ["ok", "일치"], PENDING_APPROVAL: ["warn", "승인 대기"],
  RECOUNT_REQUIRED: ["err", "재실사 필요"], APPROVED: ["ok", "승인·반영"], REJECTED: ["mute", "반려"],
};
export const SCOPE_KO: Record<string, string> = { ALL: "거점 전체", ZONE: "Zone", SKU: "SKU 지정" };
export const TRANSFER_STATUS: Record<string, ["info" | "ok" | "warn", string]> = {
  IN_TRANSIT: ["info", "이동 중"], RECEIVED: ["ok", "입고 완료"], RECEIVED_WITH_DISCREPANCY: ["warn", "입고 · 수량 차이"],
};
