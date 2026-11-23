export type ProfitRow = { key: string; label: string; net_sales: string; other_revenue: string; costs: Record<string, string>; total_cost: string; contribution: string;
  margin_pct: string | null; estimated_cost: string; missing_cost_lines: number };
export type Profit = { from: string; to: string; group_by: string; rows: ProfitRow[]; total: ProfitRow; basis_note: string };
export type Entry = { id: string; business_entity_id: string; business_entity_name: string; channel_account_id: string | null; order_id: string | null;
  order_no: string | null; order_item_id: string | null; claim_id: string | null; fulfillment_order_id: string | null; sku_id: string | null; sku_code: string | null;
  entry_class: string; category: string; basis: string; supply_amount: string; vat_amount: string; tax_type: string; quantity: number | null; unit_cost: string | null;
  period_date: string; source_type: string; source_ref: string | null; reverses_id: string | null; scope_key: string | null; memo: string | null; created_at: string };
export type CostRule = { id: string; business_entity_id: string | null; channel_account_id: string | null; node_id: string | null; category: string; method: string;
  rate_bps: number | null; amount: string | null; refundable_on_return: boolean; effective_from: string; effective_to: string | null; memo: string | null };

export const CATEGORY_KO: Record<string, string> = {
  SALES: "상품 매출", SELLER_DISCOUNT: "판매자 할인", SALES_RETURN: "반품 매출 차감", SHIPPING_FEE: "고객 배송비", EXCHANGE_PRICE_DIFFERENCE: "교환 차액",
  CUSTOMER_RETURN_FEE: "고객 부담 반품비", CHANNEL_DISCOUNT: "채널 부담 할인(메모)",
  PRODUCT_COGS: "상품 원가", SELLING_FEE: "판매수수료", PAYMENT_FEE: "결제수수료", ADVERTISING: "광고비", OUTBOUND_SHIPPING: "출고 배송비", THIRD_PARTY_PICK: "3PL 피킹비",
  PACK: "포장비", STORAGE: "보관비", INBOUND_SERVICE: "입고 작업비", MATERIAL: "부자재", GWP: "사은품(판촉비)", RETURN_SHIPPING: "반품 회수비", RETURN_INSPECTION: "반품 검수비",
  REPACK: "재포장비", EXCHANGE_SHIPPING: "교환 배송비", INVENTORY_IMPAIRMENT: "재고 평가·폐기손실", DISPOSAL_SERVICE: "폐기 처리비", CHANNEL_PENALTY: "채널 패널티", OTHER: "기타",
};
export const COST_ORDER = ["PRODUCT_COGS", "GWP", "SELLING_FEE", "PAYMENT_FEE", "ADVERTISING", "OUTBOUND_SHIPPING", "THIRD_PARTY_PICK", "PACK", "MATERIAL", "STORAGE",
  "INBOUND_SERVICE", "RETURN_SHIPPING", "RETURN_INSPECTION", "REPACK", "EXCHANGE_SHIPPING", "INVENTORY_IMPAIRMENT", "DISPOSAL_SERVICE", "CHANNEL_PENALTY", "OTHER"];
export const MANUAL_COST_CATEGORIES = COST_ORDER.filter((c) => c !== "PRODUCT_COGS" && c !== "INVENTORY_IMPAIRMENT");
export const RULE_CATEGORIES = ["SELLING_FEE", "PAYMENT_FEE", "OUTBOUND_SHIPPING", "THIRD_PARTY_PICK", "PACK", "MATERIAL", "RETURN_SHIPPING", "RETURN_INSPECTION", "EXCHANGE_SHIPPING"];
export const METHOD_KO: Record<string, string> = { RATE_OF_NET_SALES: "순매출 대비 요율", PER_UNIT: "개당", PER_ORDER: "출고지시당", PER_SHIPMENT: "송장(박스)당", PER_RETURN: "반품 건당" };
export const BASIS_KO: Record<string, string> = { ESTIMATED: "예상", ACTUAL: "실제" };
export const GROUP_KO: Record<string, string> = { business_entity: "사업자", channel_account: "판매계정", sku: "SKU", order: "주문", month: "월" };
export const won = (v: string | number | null | undefined) => (v === null || v === undefined ? "—" : Number(v).toLocaleString("ko-KR"));
