export type ProfitRow = { key: string; label: string; net_sales: string; other_revenue: string; costs: Record<string, string>; total_cost: string; contribution: string;
  margin_pct: string | null; estimated_cost: string; missing_cost_lines: number };
export type Profit = { from: string; to: string; group_by: string; rows: ProfitRow[]; total: ProfitRow; basis_note: string; elimination: Elimination | null };
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

// ---------------------------------------------------------------- 정산·입금·월 마감(M5 2차)
export type SettlementLine = { id: string; line_no: number; line_type: string; amount: string; quantity: number | null; external_order_key: string | null;
  external_item_key: string | null; order_id: string | null; order_no: string | null; order_item_id: string | null; match_level: string; applied: string };
export type OrderDiff = { order_id: string; order_no: string; settlement: string; ledger: string; difference: string };
export type SettlementRun = { id: string; settlement_key: string; business_entity_id: string; business_entity_name: string; channel_account_id: string;
  channel_account_name: string; period_from: string | null; period_to: string | null; payout_date: string | null; declared_payout: string | null; payout: string;
  deposited: string; remaining: string; status: string; source: string; totals: Record<string, string>; review_lines: number; differences: OrderDiff[];
  lines: SettlementLine[]; created_at: string };
export type DepositMatch = { id: string; deposit_id: string; run_id: string; settlement_key: string; channel_account_name: string; amount: string; method: string;
  created_at: string; cancelled_at: string | null };
export type Deposit = { id: string; business_entity_id: string; business_entity_name: string; bank_account_id: string | null; bank_account_last4: string | null;
  deposited_on: string; amount: string; matched: string; remaining: string; depositor: string | null; external_ref: string | null; memo: string | null;
  matches: DepositMatch[]; created_at: string };
export type PeriodCheck = { code: string; severity: "WARNING" | "INFO"; count: number; amount: string | null; message: string };
export type PeriodTotals = { supply_by_category: Record<string, string>; vat_by_category: Record<string, string>; net_sales: string; other_revenue: string;
  total_cost: string; contribution: string; entry_count: number; posted_after_close: number };
export type Period = { business_entity_id: string; business_entity_name: string; month: string; status: "OPEN" | "CLOSED"; closable: boolean;
  not_closable_reason: string | null; checks: PeriodCheck[]; totals: PeriodTotals; snapshot: PeriodTotals | null; snapshot_matches: boolean | null;
  closed_at: string | null; closed_by_name: string | null; pending_reopen_approval_id: string | null;
  events: { event_type: string; reason: string | null; actor_name: string | null; approval_id: string | null; occurred_at: string }[] };

export const LINE_TYPE_KO: Record<string, string> = { SALE: "판매", SHIPPING_FEE: "배송비", COMMISSION: "판매수수료", PAYMENT_FEE: "결제수수료",
  DISCOUNT_COMPENSATION: "할인보전", REFUND: "환불", PENALTY: "패널티", ADJUSTMENT: "조정", OTHER: "기타" };
export const APPLIED_KO: Record<string, string> = { LEDGER: "원장 반영", RECON_ONLY: "대사용", REVIEW: "검토 필요" };
export const MATCH_KO: Record<string, string> = { ITEM: "주문상품", ORDER: "주문", PERIOD: "기간(주문 없음)", UNMATCHED: "원주문 없음" };
export const RUN_STATUS_KO: Record<string, string> = { IMPORTED: "가져옴", RECONCILED: "대사 완료", ISSUES: "확인 필요" };

// ---------------------------------------------------------------- 입고 부대비용·공통비 배부·내부거래 제거(M5 3차)
export type LandedCostLine = { receipt_txn_id: string; sku_id: string; sku_code: string; quantity: number; basis_value: string; allocated: string; capitalized: string;
  expensed: string; on_hand_at_allocation: number };
export type LandedCost = { id: string; business_entity_id: string; business_entity_name: string; cost_type: string; supply_amount: string; vat_amount: string; method: string;
  source_ref: string; memo: string | null; capitalized: string; expensed: string; lines: LandedCostLine[]; created_at: string };
export type AllocationLine = { business_entity_id: string; business_entity_name: string; numerator: string | null; denominator: string | null; supply_amount: string | null;
  vat_amount: string | null; entry_id: string | null };
export type Allocation = { id: string; allocation_no: string; version: number; supersedes_id: string | null; status: string; category: string; supply_amount: string;
  vat_amount: string; period_date: string; method: string; basis_from: string | null; basis_to: string | null; source_ref: string | null; memo: string | null;
  lines: AllocationLine[]; created_at: string };
export type Elimination = { internal_sales: string; unrealized_at_start: string; unrealized_at_end: string; cogs_adjustment: string; note: string };
export const LANDED_TYPE_KO: Record<string, string> = { FREIGHT: "운임", DUTY: "관세", CUSTOMS: "통관", INSPECTION: "검사", OTHER: "기타" };
export const LANDED_METHOD_KO: Record<string, string> = { QUANTITY: "수량 비중", AMOUNT: "입고 금액 비중", MANUAL: "직접 지정" };
export const ALLOC_METHOD_KO: Record<string, string> = { NET_SALES: "순매출 비중", EQUAL: "균등", MANUAL_RATIO: "직접 비율" };

// ---------------------------------------------------------------- 운영 리포트·인사이트(M6)
export type InsightCard = { id: string; rule: string; severity: "HIGH" | "MEDIUM" | "LOW"; status: string; business_entity_id: string; business_entity_name: string;
  channel_account_id: string | null; sku_id: string | null; title: string; summary: string; metric: Record<string, unknown>;
  causes: { code: string; label: string; delta_pp?: string; certainty?: string; current_amount?: string; previous_amount?: string }[];
  evidence: Record<string, unknown>; unconfirmed: string[]; observed_on: string; first_detected_at: string; last_detected_at: string; resolved_at: string | null;
  work_item_id: string | null; dismiss_reason: string | null };
export const RULE_KO: Record<string, string> = { MARGIN_DROP: "공헌이익률 급락", STOCKOUT_RISK: "품절 위험", SLOW_MOVING: "장기 미판매 재고", EXPIRY_RISK: "유통기한 임박",
  SETTLEMENT_MISSING: "정산 누락 의심", UNDEPOSITED: "미입금 정산" };
export const INSIGHT_STATUS_KO: Record<string, string> = { OPEN: "열림", ACTIONED: "검토 업무 생성", DISMISSED: "무시", RESOLVED: "해결" };
export const METRIC_KO: Record<string, string> = { current_margin_pct: "최근 이익률(%)", previous_margin_pct: "직전 이익률(%)", delta_pp: "변화(%p)",
  current_net_sales: "최근 순매출", previous_net_sales: "직전 순매출", current_contribution: "최근 공헌이익", previous_contribution: "직전 공헌이익",
  current_from: "최근 기간 시작", current_to: "최근 기간 끝", previous_from: "직전 기간 시작", previous_to: "직전 기간 끝", atp: "판매가능(확보 제외)",
  sold_28d: "최근 28일 판매", avg_daily_sales: "일평균 판매", days_of_cover: "재고일수", threshold_days: "기준 일수", on_hand: "보유 수량",
  days_without_sale: "미판매 기준 일수", last_sale_at: "마지막 출고", book_value: "장부가(원)", lot_code: "LOT", expires_on: "유통기한", days_left: "남은 일수",
  sellable_qty: "판매가능 수량", orders: "주문 수", order_amount: "주문금액(원)", days_after_shipment: "출고 후 기준 일수", runs: "정산 수",
  undeposited_amount: "미입금액(원)", oldest_payout_date: "가장 오래된 지급예정일", grace_days: "유예 일수" };
