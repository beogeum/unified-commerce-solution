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

## 3. 정산 원본·대사 (N3, DLG-161~165)
금액은 판매자 기준 부호(+ 받을 돈, − 공제)·부가세 포함. 원본 행은 불변(미매칭 행의 연결 정보만 재매칭으로 채움).

| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `POST /finance/settlements/imports` (multipart `channel_account_id`, `file`) | `settlement.manage` + 판매계정 쓰기 범위 | 표준 양식(.csv·.xlsx). 필수 열 정산번호·항목·금액, 선택 정산기간시작·정산기간종료·지급예정일·지급액·주문번호·상품주문번호·수량. 항목 = 판매·배송비·판매수수료·결제수수료·할인보전·환불·패널티·조정·기타(또는 영문 코드). 잘못된 행이 있으면 `422 SETTLEMENT_FILE_INVALID`(전체 미반영), 열 누락 `422 TEMPLATE_COLUMN_MISSING`. 응답 `{runs, new_lines, duplicate_lines, run_ids}` |
| `POST /finance/settlements {channel_account_id, settlement_key, period_from?, period_to?, payout_date?, declared_payout?, lines[{line_type, amount, quantity?, external_order_key?, external_item_key?}]}` | `settlement.manage` | 수기 정산. 같은 판매계정·정산번호는 같은 정산에 새 행만 추가 |
| `GET /finance/settlements?business_entity_id&channel_account_id` | `settlement.read` + 범위 | 지급액(행 합계)·입금 연결·미입금·상태·검토 행·대사 차이 |
| `GET /finance/settlements/{id}` | `settlement.read` | `{run(…, totals, differences, lines), matches}` |
| `POST /finance/settlements/{id}/rematch` | `settlement.manage` | 원주문 없음 행을 다시 연결, 모두 연결되면 업무 해결 |

원장 반영: 수수료 → 실제 `SELLING_FEE`·`PAYMENT_FEE`(같은 주문상품 예상 상계, 주문 단위는 순매출 비중 배부), 패널티 → `CHANNEL_PENALTY`, 조정·기타 → `OTHER`. 판매·배송비·할인보전·환불은 대사용(플랫폼 물류 주문만 매출 근거). 주문 없는 수수료·원주문 없음은 업무(`SETTLEMENT_LINE_REVIEW`·`SETTLEMENT_UNMATCHED`).

## 4. 입금 매칭 (N4, DLG-166)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `POST /finance/deposits {business_entity_id, deposited_on, amount, bank_account_id?, depositor?, external_ref?, memo?}` + `Idempotency-Key` | `settlement.manage` | 추가 전용. 같은 키 → 같은 입금, 같은 거래번호 `409 DEPOSIT_DUPLICATE` |
| `GET /finance/deposits?business_entity_id&unmatched` · `GET /finance/deposits/{id}` | `settlement.read` | 연결·미연결·연결 내역(취소 포함) |
| `POST /finance/deposits/{id}/matches {run_id, amount?}` | `settlement.manage` | 금액 생략 = min(입금 잔액, 정산 미입금). 초과 `422 DEPOSIT_OVER_MATCHED`·`SETTLEMENT_OVER_MATCHED`, 다른 사업자 `422 DEPOSIT_ENTITY_MISMATCH` |
| `POST /finance/deposits/{id}/auto-match` | `settlement.manage` | `outcome` = `MATCHED·NO_MATCH·AMBIGUOUS·NOTHING_TO_MATCH`, 모호하면 `candidate_run_ids` 만 |
| `POST /finance/deposit-matches/{id}/cancel {reason}` | `settlement.manage` | 행 유지 + 취소 시각, 이미 취소 `409 DEPOSIT_MATCH_CANCELLED` |

## 5. 월 마감 (N7, DLG-167·168)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /finance/periods?business_entity_id&months` | `profit.read` | 최근 N개월 상태·합계·점검 |
| `GET /finance/periods/preview?business_entity_id&month` | `profit.read` | 점검(`WARNING`·`INFO`), 원장 합계, 마감 스냅샷·일치 여부, 이력, 대기 중 재개방 요청 |
| `POST /finance/periods/close {business_entity_id, month, acknowledge_warnings?, note?}` | `period.close` | 끝난 달만(`422 PERIOD_NOT_ENDED`), 경고 미확인 `422 PERIOD_CLOSE_WARNINGS`, 이미 마감 `409 PERIOD_ALREADY_CLOSED` |
| `POST /finance/periods/reopen-requests {business_entity_id, month, reason}` | `period.close` | 승인 요청 `PERIOD_REOPEN`(본인 승인 불가, 승인자도 `period.close`). `POST /approvals/{id}/decision` 으로 처리 |

마감 월에는 원장을 기록할 수 없다(DB 트리거). 마감 후 사건은 다음 열린 달 1일로 귀속하고 `original_period_date`·메모 "마감 후 발생(원 귀속 YYYY-MM)"으로 원거래 월과 연결한다.

## 6. 입고 부대비용 (T33, DLG-169·170)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `POST /finance/landed-costs {business_entity_id, cost_type(FREIGHT·DUTY·CUSTOMS·INSPECTION·OTHER), supply_amount, vat_amount?, method(QUANTITY·AMOUNT·MANUAL), source_ref, memo?, receipts[{receipt_txn_id, amount?}]}` | `cost.manage` + 사업자 쓰기·거점 범위 | 입고 행(`GET /inventory/transactions?txn_type=RECEIPT`)에 배부. 보유분 → 이동평균 원가, 출고분 → `PRODUCT_COGS`. 오류: `RECEIPT_COST_MISSING`(금액 기준인데 입고 단가 없음), `ALLOCATION_TOTAL_MISMATCH`(직접 지정 합계 ≠ 공급가), `409 LANDED_COST_DUPLICATE`, `409 COST_ALREADY_EXPENSED` |
| `GET /finance/landed-costs?business_entity_id` · `GET /finance/landed-costs/{id}` | `cost.read` | 줄별 배부·취득원가·매출원가·배부 시점 보유 수량 |

`POST /finance/cost-entries` 는 같은 사업자·근거번호가 부대비용으로 반영돼 있으면 `409 LANDED_COST_CAPITALIZED`.
`POST /inventory/receipts` 에 `unit_cost` 를 주면 입고 단가가 보존된다(금액 기준 배부 근거).

## 7. 공통비 배부 (T34, DLG-171·172)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `POST /finance/cost-allocations {category, supply_amount, vat_amount?, period_date?, method(NET_SALES·EQUAL·MANUAL_RATIO), basis_from?, basis_to?, targets[{business_entity_id, ratio?}], source_ref?, memo?}` + `Idempotency-Key` | `cost.manage` + 모든 대상 사업자 쓰기 범위 | 대상 2곳 이상. 사업자별 실제 비용 원장(`COST_ALLOCATION`), 줄마다 분자·분모 보존. `ALLOCATION_BASE_ZERO` |
| `POST /finance/cost-allocations/{id}/reallocate {method?, targets, basis_from?, basis_to?, memo?}` | 〃 | 새 버전, 이전 버전 원장 반대 거래, 이전 상태 `SUPERSEDED`(`409 ALLOCATION_SUPERSEDED`) |
| `GET /finance/cost-allocations?business_entity_id` · `GET /finance/cost-allocations/{id}` | `cost.read` | 범위 밖 사업자 몫은 금액 숨김 |

## 8. 사업자 간 재고 이전·내부거래 제거 (T35, DLG-173~176)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `POST /inventory/ownership-transfers {stock_id, to_business_entity_id, quantity, unit_price, tax_type?, reason}` (+ `Idempotency-Key` 선택) | `inventory.adjust` + 양쪽 사업자 쓰기 범위 | `202` 승인 요청(`INTERCOMPANY_TRANSFER`). 판매가능·자체 원장 거점만, 같은 사업자 `VALIDATION_FAILED`, 수량 초과 `STOCK_INSUFFICIENT` |
| `POST /approvals/{approval_id}/decision` | `approval.decide` + `cost.manage`(`APPROVER_NEEDS_COST_MANAGE`) | 본인 승인 불가. 승인 시 소유자 변경·재고 원장·내부 매출·원가 |
| `GET /inventory/ownership-transfers?business_entity_id` · `GET /inventory/ownership-transfers/{id}` | `inventory.read`(보낸·받은 사업자 중 하나 조회 범위) | 상태 `PENDING·COMPLETED·REJECTED·CANCELLED`, `tax_invoice_required` |

`GET /finance/profit` 응답의 `elimination`(범위에 양쪽 사업자가 있을 때): `internal_sales`, `unrealized_at_start`, `unrealized_at_end`, `cogs_adjustment`, `note`. 합계 행에만 반영되고 사업자별 행은 그대로다.

## 9. 부가세 신고자료 (M6 2차, docs/15, DLG-183~189)
국세청 자동 신고가 아니다. 금액은 원 단위 정수, 공급가·세액·합계 별도(환불은 음수).

| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `POST /vat/batches {channel_account_id, period_from, period_to}` | `vat.manage` + 판매계정 쓰기 범위 | 묶음(판매계정 × 기간), 같은 계정·기간 `409 VAT_BATCH_EXISTS` |
| `POST /vat/batches/{id}/files` (multipart `file`) | `vat.manage` | 표준 양식. `{row_count, new_rows, duplicate_rows, blocking_rows, warning_rows}`. 오류 `TEMPLATE_COLUMN_MISSING`·`VAT_FILE_INVALID`·`VAT_ENTITY_MISMATCH`, `409 VAT_FILE_DUPLICATE`·`VAT_BATCH_CONFIRMED` |
| `GET /vat/batches?business_entity_id&from&to` · `GET /vat/batches/{id}` | `settlement.read` + 범위 | 분류별 합계, 대사(`recon`), 파일, 문제·보정 행 |
| `POST /vat/rows/{id}/override {classification, reason}` | `vat.manage` | 원본 불변, 보정 기록(`EXCLUDED` 가능, `REVIEW` 불가) |
| `POST /vat/batches/{id}/confirm` | `vat.manage`(파일을 올리지 않은 담당자) | `VAT_SELF_CONFIRM_FORBIDDEN`, `VAT_BATCH_HAS_ISSUES`, `VAT_BATCH_EMPTY` |
| `GET /vat/reports?business_entity_id&from&to` | `settlement.read` | 사업자 1곳 × 기간 합계, 모두 확정 여부 |
| `POST /vat/exports {business_entity_id, period_from, period_to}` | `export.create` + `settlement.read` + 내보내기 범위 | `202 {job_id}` → `GET /jobs/{id}` → `GET /files/{file_id}/content`(요청자만, 24시간) |

분류: `CARD` 카드매출, `CASH_RECEIPT` 현금영수증, `MOBILE` 휴대폰, `OTHER` 기타, `TAX_INVOICE` 세금계산서, `FREE_CARD`·`FREE_CASH_RECEIPT`·`FREE_MOBILE`·`FREE_OTHER` 면세, `E_INVOICE` 전자계산서, `ZERO_RATED` 영세율(검토), `REVIEW` 검토 필요, `EXCLUDED` 제외(보정만).

