export type ClaimItem = { id: string; line_no: number; order_item_id: string; product_name: string; sku_id: string | null; sku_code: string | null; quantity: number; refund_amount: string;
  replacement_sku_id: string | null; replacement_sku_code: string | null; replacement_quantity: number | null; replacement_order_item_id: string | null;
  expected_return_qty: number; received_qty: number; inspected_qty: number };
export type Refund = { id: string; executor: string; status: string; requested_amount: string; confirmed_amount: string | null; external_ref: string | null; source: string;
  note: string | null; requested_at: string; confirmed_at: string | null; failure_reason: string | null };
export type ClaimEvent = { event_type: string; from_value: string | null; to_value: string | null; actor_type: string; occurred_at: string };
export type Claim = { id: string; claim_no: string; order_id: string; order_no: string; business_entity_id: string; business_entity_name: string; channel_account_id: string;
  channel_account_name: string; claim_type: string; source: string; external_claim_key: string | null; status: string; return_status: string; refund_status: string;
  reship_status: string; cancel_applied: boolean; reason_code: string; reason_note: string | null; fault_party: string; ship_before_return: boolean;
  return_carrier_code: string | null; return_tracking_no: string | null; item_refund_amount: string; shipping_refund_amount: string; return_shipping_fee: string;
  price_difference: string; expected_refund_amount: string; additional_payment_amount: string; confirmed_refund_amount: string; channel_action_status: string;
  channel_action: string | null; channel_action_error: string | null; external_status: string | null; decision_note: string | null; requested_at: string;
  decided_at: string | null; completed_at: string | null; created_at: string; items: ClaimItem[]; refunds: Refund[]; events: ClaimEvent[]; version: number };
export type ReceiptLine = { id: string; line_no: number; sku_id: string; sku_code: string; quantity: number; claim_item_id: string | null; matched: boolean;
  stock_id: string | null; inspected_qty: number; inspections: { condition: string; quantity: number; reason: string | null; source: string; applied_to_stock: boolean;
    inspected_at: string }[] };
export type Receipt = { id: string; receipt_no: string; node_id: string; node_name: string; business_entity_id: string; business_entity_name: string; claim_id: string | null;
  claim_no: string | null; order_id: string | null; order_no: string | null; identification: string; status: string; external_inventory: boolean;
  inspection_support: string; carrier_code: string | null; tracking_no: string | null; note: string | null; received_at: string; created_at: string; inspection_due_at: string | null; inspection_overdue: boolean;
  lines: ReceiptLine[]; version: number };

type Tone = "info" | "ok" | "warn" | "mute" | "err";
export const CLAIM_TYPE_KO: Record<string, string> = { CANCEL: "취소", RETURN: "반품", EXCHANGE: "교환" };
export const CLAIM_STATUS: Record<string, [Tone, string]> = {
  REQUESTED: ["info", "접수"], REVIEWING: ["info", "검토 중"], APPROVED: ["warn", "승인"], REJECTED: ["mute", "거부"], PROCESSING: ["warn", "처리 중"],
  COMPLETED: ["ok", "완료"], CANCELLED: ["mute", "철회"],
};
export const RETURN_STATUS: Record<string, [Tone, string]> = {
  NOT_REQUIRED: ["mute", "회수 없음"], REQUESTED: ["info", "회수 요청"], IN_TRANSIT: ["warn", "회수 중"], RECEIVED: ["warn", "입고(검수 대기)"], INSPECTED: ["ok", "검수 완료"],
};
export const REFUND_STATUS: Record<string, [Tone, string]> = {
  NOT_REQUIRED: ["mute", "환불 없음"], EXPECTED: ["info", "환불 예정"], REQUESTED: ["warn", "환불 요청"], PARTIALLY_CONFIRMED: ["warn", "일부 환불"],
  CONFIRMED: ["ok", "환불 완료"], FAILED: ["err", "환불 실패"], CANCELLED: ["mute", "환불 취소"],
};
export const RESHIP_STATUS: Record<string, [Tone, string]> = { NOT_REQUIRED: ["mute", "재출고 없음"], PENDING: ["info", "재출고 대기"], SHIPPED: ["ok", "재출고 완료"] };
export const CHANNEL_ACTION: Record<string, [Tone, string]> = {
  NOT_REQUIRED: ["mute", "채널 반영 불필요"], PENDING: ["info", "채널 반영 대기"], SENT: ["ok", "채널 반영 완료"], MANUAL_REQUIRED: ["warn", "판매자센터 처리 필요"],
  MANUAL_DONE: ["ok", "판매자센터 처리 완료"], FAILED: ["err", "채널 반영 실패"],
};
export const REASON_KO: Record<string, string> = {
  CHANGE_OF_MIND: "단순 변심", DEFECT: "상품 불량", WRONG_ITEM: "오배송", DAMAGED_IN_TRANSIT: "배송 중 파손", DELIVERY_DELAY: "배송 지연", OUT_OF_STOCK: "품절",
  WRONG_ADDRESS: "주소 오류", OTHER: "기타",
};
export const FAULT_KO: Record<string, string> = { CUSTOMER: "고객", SELLER: "판매자", CARRIER: "택배사", UNKNOWN: "확인 필요" };
export const EXECUTOR_KO: Record<string, string> = { CHANNEL: "판매채널", PG: "PG", MANUAL: "수동(계좌 송금 등)" };
export const REFUND_ROW: Record<string, [Tone, string]> = { REQUESTED: ["warn", "요청"], CONFIRMED: ["ok", "확정"], FAILED: ["err", "실패"], CANCELLED: ["mute", "취소"] };
export const CONDITION_KO: Record<string, string> = {
  SELLABLE: "정상(판매가능 복귀)", REPACK: "재포장", DEFECTIVE: "불량", DAMAGED: "파손", EXPIRED: "만료", DISPOSAL_PENDING: "폐기대기", UNDETERMINED: "미확정",
};
export const IDENT: Record<string, [Tone, string]> = { MATCHED: ["ok", "클레임 일치"], MISMATCH: ["err", "클레임과 다름"], UNIDENTIFIED: ["err", "원주문 미확인"] };
export const RECEIPT_STATUS: Record<string, [Tone, string]> = { RECEIVED: ["warn", "검수 대기"], INSPECTING: ["warn", "검수 중"], INSPECTED: ["ok", "검수 완료"] };
export const CLAIM_EVENT_KO: Record<string, string> = {
  CLAIM_CREATED: "접수", CLAIM_APPROVED: "승인", CLAIM_REJECTED: "거부", CLAIM_WITHDRAWN: "철회", CANCEL_WAITING_LOGISTICS: "출고 취소 확인 대기",
  CANCEL_APPLIED: "취소 수량 반영", RETURN_SHIPMENT_RECORDED: "회수 송장 기록", RETURN_RECEIVED: "회수품 입고", RETURN_INSPECTED: "검수 완료",
  REFUND_REQUESTED: "환불 요청", REFUND_CONFIRMED: "환불 확정", REFUND_FAILED: "환불 실패", STATUS_CHANGED: "상태 변경", REFUND_STATUS_CHANGED: "환불 상태 변경",
  RESHIP_STATUS_CHANGED: "재출고 상태 변경", CHANNEL_ACTION_SENT: "채널 반영", CHANNEL_ACTION_MANUAL_REQUIRED: "판매자센터 처리 필요",
  CHANNEL_ACTION_MANUAL_DONE: "판매자센터 처리 완료", CHANNEL_ACTION_FAILED: "채널 반영 실패",
};
