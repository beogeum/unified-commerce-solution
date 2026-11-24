export type Supplier = { id: string; business_entity_id: string; business_entity_name: string; name: string; business_registration_no: string | null;
  lead_time_days: number; payment_terms: string | null; memo: string | null; status: string; sku_count: number; version: number };
export type SupplierSku = { id: string; supplier_id: string; sku_id: string; sku_code: string; sku_name: string | null; supplier_sku_code: string | null;
  unit_cost: string | null; tax_type: string; moq: number; order_multiple: number; lead_time_days: number | null; effective_lead_time_days: number; status: string; version: number };
export type PoLine = { id: string; line_no: number; sku_id: string; sku_code: string; sku_name: string | null; quantity: number; unit_cost: string | null; tax_type: string;
  supply_amount: string | null; vat_amount: string | null; received_qty: number; rejected_qty: number; remaining_qty: number; moq_override_reason: string | null };
export type PoReceipt = { id: string; receipt_no: string; received_at: string; note: string | null; created_by_name: string | null;
  lines: { po_line_id: string; sku_code: string; good_qty: number; rejected_qty: number; unit_cost: string | null; po_unit_cost: string | null; lot_code: string | null;
    expires_on: string | null; inventory_txn_id: string | null; reject_reason: string | null }[] };
export type PurchaseOrder = { id: string; po_no: string; business_entity_id: string; business_entity_name: string; supplier_id: string; supplier_name: string; node_id: string;
  node_name: string; status: string; expected_on: string | null; supplier_ref: string | null; memo: string | null; close_reason: string | null; approval_id: string | null;
  supply_total: string | null; vat_total: string | null; ordered_qty: number; received_qty: number; rejected_qty: number; price_variance: string | null; cost_visible: boolean;
  lines: PoLine[]; receipts: PoReceipt[]; created_at: string; placed_at: string | null; version: number };
export type Inbound = { business_entity_id: string; sku_id: string; sku_code: string; node_id: string; inbound_qty: number; earliest_expected_on: string | null };
export const PO_STATUS: Record<string, ["ok" | "info" | "warn" | "err" | "mute", string]> = {
  DRAFT: ["mute", "작성 중"], APPROVAL_PENDING: ["warn", "승인 대기"], PLACED: ["info", "발주 완료"], SUPPLIER_CONFIRMED: ["info", "공급처 확인"],
  PARTIALLY_RECEIVED: ["warn", "부분 입고"], RECEIVED: ["ok", "입고 완료"], CLOSED: ["ok", "잔량 종료"], CANCELLED: ["mute", "취소"],
};
export type Suggestion = { business_entity_id: string; business_entity_name: string; sku_id: string; sku_code: string; supplier_id: string; supplier_name: string;
  supplier_sku_id: string; avg_daily_sales: string; atp: number; inbound: number; lead_time_days: number; target_days: number; days_of_cover: string | null; need: number;
  moq: number; order_multiple: number; suggested_qty: number; unit_cost: string | null; amount: string | null; reason: string };
export type ShipmentLine = { id: string; po_line_id: string; sku_id: string; sku_code: string; sku_name: string | null; expected_qty: number; arrived_qty: number | null;
  good_qty: number | null; rejected_qty: number | null; over_qty: number | null; reject_reason: string | null; putaway_qty: number; putaway_remaining: number;
  difference: number | null };
export type Shipment = { id: string; shipment_no: string; po_id: string; po_no: string; business_entity_id: string; business_entity_name: string; supplier_name: string;
  node_id: string; node_name: string; status: string; expected_on: string | null; carrier: string | null; tracking_no: string | null; note: string | null;
  receipt_no: string | null; has_discrepancy: boolean; arrived_at: string | null; inspected_at: string | null; completed_at: string | null; cancel_reason: string | null;
  expected_qty: number; arrived_qty: number; good_qty: number; putaway_qty: number; lines: ShipmentLine[];
  events: { from_status: string | null; to_status: string; actor_name: string | null; occurred_at: string; detail: string }[]; created_at: string; version: number };
export const SHIPMENT_STATUS: Record<string, ["ok" | "info" | "warn" | "err" | "mute", string]> = {
  EXPECTED: ["info", "입고예정"], ARRIVED: ["warn", "센터 도착"], INSPECTING: ["warn", "검수 중"], PUTAWAY: ["warn", "적치 대기"],
  COMPLETED: ["ok", "입고 완료"], DISCREPANCY: ["err", "완료(수량 차이)"], CANCELLED: ["mute", "취소"],
};
