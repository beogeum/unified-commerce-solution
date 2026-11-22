export type OrderRow = { id: string; order_no: string; business_entity_id: string; business_entity_name: string; channel_account_id: string;
  channel_account_name: string; sales_channel_name: string; sales_unit_name: string; source_type: string; external_order_key: string; ordered_at: string;
  status: string; payment_status: string; on_hold: boolean; hold_reasons: string[]; total_amount: string; item_count: number; item_summary: string | null;
  recipient_name_masked: string | null; updated_at: string };
export type OrderItem = { id: string; line_no: number; external_item_key: string; external_product_id: string; external_option_id: string; product_name: string;
  option_name: string | null; listing_id: string | null; sku_id: string | null; sku_code: string | null; sku_qty_per_unit: number | null; mapping_status: string;
  original_qty: number; canceled_qty: number; active_qty: number; shipped_qty: number; returned_qty: number; unit_price: string; line_amount: string;
  seller_discount: string; channel_discount: string; tax_type: string | null; external_status: string | null };
export type OrderEvent = { id: string; event_type: string; order_item_id: string | null; from_value: string | null; to_value: string | null; actor_type: string;
  actor_name: string | null; occurred_at: string };
export type OrderRaw = { id: string; source: string; result: string; result_detail: string | null; mapping_version: string; external_updated_at: string | null;
  received_at: string; payload_masked: unknown };
export type OrderDetail = { order: OrderRow; paid_at: string | null; external_status: string | null; external_updated_at: string | null; items_amount: string;
  seller_discount: string; channel_discount: string; shipping_fee: string; buyer_name_masked: string | null; recipient_phone_masked: string | null;
  address_masked: string | null; hold_note: string | null; items: OrderItem[]; events: OrderEvent[]; raws: OrderRaw[]; version: number };

export const ORDER_STATUS: Record<string, ["info" | "ok" | "warn" | "mute" | "err", string]> = {
  RECEIVED: ["info", "결제 대기"], CONFIRMED: ["ok", "주문 확인"], PROCESSING: ["warn", "출고 진행"], COMPLETED: ["ok", "완료"], CANCELLED: ["mute", "취소"],
};
export const PAYMENT_KO: Record<string, string> = { PENDING: "결제 대기", PAID: "결제 완료", PARTIALLY_REFUNDED: "부분 환불", REFUNDED: "환불", FAILED: "결제 실패" };
export const HOLD_KO: Record<string, string> = {
  UNMAPPED_ITEM: "상품 미연결", AMOUNT_MISMATCH: "금액 불일치", RECIPIENT_MISSING: "수령인 정보 없음", SKU_NOT_SELLABLE: "판매 불가 SKU", MANUAL: "직접 보류",
};
export const EVENT_KO: Record<string, string> = {
  ORDER_CREATED: "주문 수집", PAYMENT_CONFIRMED: "결제 확인", EXTERNAL_STATUS_CHANGED: "외부 상태 변경", RECIPIENT_CHANGED: "수령인 변경",
  ITEM_ADDED: "상품주문 추가", ITEM_CANCELED: "취소 수량 반영", ITEM_MAPPED: "SKU 연결", HOLD_CHANGED: "보류 사유 변경", HOLD_RELEASED: "보류 해제",
  HOLD_MANUAL: "직접 보류", HOLD_MANUAL_RELEASED: "직접 보류 해제", STATUS_CHANGED: "상태 변경", RAW_IGNORED_STALE: "오래된 원본 무시",
};
export const RAW_RESULT_KO: Record<string, string> = { CREATED: "생성", UPDATED: "갱신", IGNORED_STALE: "역순 도착 · 미반영", REJECTED: "거절" };
export const SOURCE_TYPE_KO: Record<string, string> = { ORDER: "일반", PLATFORM_FULFILLMENT: "플랫폼 물류", MANUAL: "수동" };
