# 재무 API (M5)

관리 손익(부가세 제외 공급가 기준)이며 법정 재무제표·세무 신고를 대체하지 않는다(docs/05). 금액은 원 단위 정수 문자열.

## 1. 원장 (`financial_entry`, DLG-004)
- 단일 권위 원장. `entry_class` = `REVENUE·COST·MEMO`(메모는 손익 밖), `basis` = `ESTIMATED·ACTUAL`, 부호: 늘면 +, 줄면 −.
- 같은 경제적 사실은 `source_key` 로 한 번만(재처리·재수신 멱등). 기록은 고치지 않고 반대 거래로 조정(`reverses_id`).
- 자동 기록:
  - 출고 확정: 상품 매출(`SALES`)·판매자 할인(`SELLER_DISCOUNT`, −)·채널 부담 할인(`CHANNEL_DISCOUNT` 메모)을 출고 단위만큼(마지막 수량이 나머지), 주문 첫 출고에 고객 배송비(`SHIPPING_FEE`), 구성품 원가(`PRODUCT_COGS`, 사은품은 `GWP`)를 이동평균 단가로, 계약요율 예상 비용(수수료·배송·피킹·포장·부자재).
  - 반품 검수: 정상·재포장 판정분만 출고 당시 단가로 원가 환입, 불량·파손·만료·폐기대기는 장부가 0 기록(원가는 매출원가에 남음), 미확정은 보류. 반품 클레임의 회수 검수가 끝나면 반품 매출 차감(`SALES_RETURN`)·할인 환원·수수료 환급(규칙이 환급이면)·회수비 예상(`RETURN_SHIPPING`)·고객 부담 반품 배송비 수익(`CUSTOMER_RETURN_FEE`).
  - 교환 완료: 차액 ±(`EXCHANGE_PRICE_DIFFERENCE`). 교환품 원가는 그 출고에서. 원매출은 그대로.
  - 폐기 승인: 장부가가 남은 수량만 폐기손실(`INVENTORY_IMPAIRMENT`).
  - 환불 확정은 현금 사건이라 손익에 따로 기록하지 않는다(반품 매출 차감으로 한 번만).

| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /finance/entries?business_entity_id&order_id&claim_id&category&from&to&limit` | `cost.read` + 사업자·판매계정 범위 | 드릴다운 |
| `GET /finance/profit?business_entity_id&channel_account_id&from&to&group_by(business_entity·channel_account·sku·order·month)` | `profit.read` | 순매출·기타 수익·항목별 비용·공헌이익·이익률(합계 ÷ 합계)·예상 비용·원가 미등록 줄 |
| `POST /finance/cost-entries {business_entity_id, category, basis, supply_amount, vat_amount?, period_date?, scope?{fulfillment_order_id \| order_item_id \| claim_id}, channel_account_id?, node_id?, sku_id?, source_ref?, memo?}` + `Idempotency-Key` | `cost.manage` | 실제 + 범위 → 그 범위의 남은 예상을 상계하고 실제만(T26). 차이 ≥ 100원이면 업무 `COST_VARIANCE_REVIEW`. 매출원가·평가손실은 직접 입력 불가 |
| `POST /finance/impairments {stock_id, quantity, reason}` | `cost.manage` | 비가용 버킷만, 장부가 0 으로(이미 0 인 수량 제외, `422 ALREADY_IMPAIRED`) |
| `GET /finance/cost-rules` · `POST /finance/cost-rules {business_entity_id?, channel_account_id?, node_id?, category, method, rate_bps? \| amount?, refundable_on_return?, effective_from?}` | `cost.read` / `cost.manage` | 방식 `RATE_OF_NET_SALES·PER_UNIT·PER_ORDER·PER_SHIPMENT·PER_RETURN`. 같은 대상·항목의 열린 규칙은 새 규칙 시작 시 종료 |

## 2. 원가
- 이동평균(사업자·SKU, 거점 통합): `POST /inventory/receipts` 에 `unit_cost`(공급가)를 주면 갱신. 없으면 등록 원가(`sku_cost`). 원가가 없으면 0원 예상 원가 + 업무 `FINANCE_COST_MISSING`.
