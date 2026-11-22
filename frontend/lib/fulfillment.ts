export type FoLine = { line_no: number; order_item_id: string; sku_id: string; sku_code: string; quantity: number; reservation_id: string | null; reservation_state: string | null };
export type FoEvent = { event_type: string; from_status: string | null; to_status: string | null; external_at: string | null; detail: Record<string, unknown>;
  actor_type: string; occurred_at: string };
export type FulfillmentOrder = { id: string; fo_no: string; order_id: string; order_no: string; business_entity_id: string; business_entity_name: string;
  channel_account_id: string; node_id: string; node_name: string; provider_kind: string; status: string; dispatch_key: string; dispatch_mode: string | null;
  external_ref: string | null; attempts: number; last_error_code: string | null; last_error_message: string | null; requested_at: string | null;
  accepted_at: string | null; created_at: string; lines: FoLine[]; events: FoEvent[]; version: number };
export type AllocationResult = { outcome: string; fulfillment_order_id: string | null; node_name: string | null;
  shortages: { sku_code: string; required: number; available_all_nodes: number }[] };

export const FO_STATUS: Record<string, ["info" | "ok" | "warn" | "mute" | "err", string]> = {
  ALLOCATED: ["info", "배정됨 · 전송 전"], REQUESTED: ["warn", "물류사 전송 중"], ACCEPTED: ["ok", "물류사 접수"], PICKING: ["warn", "피킹 중"],
  PACKING: ["warn", "포장 중"], SHIPPED: ["ok", "출고 완료"], FAILED: ["err", "전송 실패"], CANCEL_REQUESTED: ["warn", "취소 확인 대기"], CANCELLED: ["mute", "취소"],
};
export const DISPATCH_MODE_KO: Record<string, string> = { INTERNAL: "자체 WMS 작업지시", API: "물류사 API", MANUAL: "수동 전달(화면·파일)" };
export const ALLOCATION_KO: Record<string, string> = { PENDING: "배정 대기", ALLOCATED: "배정 완료", FAILED: "배정 실패", NOT_REQUIRED: "플랫폼 물류(배정 불필요)" };
export const FO_EVENT_KO: Record<string, string> = {
  ALLOCATED: "출고 배정", DISPATCH_REQUESTED: "전송 요청", DISPATCH_ATTEMPT: "물류사 전송 시도", ACCEPTED: "물류사 접수", DISPATCH_MANUAL_REQUIRED: "수동 전달 필요",
  DISPATCH_FAILED: "전송 실패", CANCEL_REQUESTED: "취소 요청(물류사 확인 대기)", CANCELLED: "취소",
};
