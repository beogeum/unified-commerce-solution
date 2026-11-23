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
