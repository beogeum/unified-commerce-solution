# 발주·매입 API (P1, FR-FIN-01)

금액은 원 단위 정수(공급가 기준, 부가세 별도). 단가·금액은 `cost.read` 또는 `purchase.manage` 가 있을 때만 값이 나오고 아니면 `null`.

## 1. 공급처 (DLG-190)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /suppliers?business_entity_id&q` · `GET /suppliers/{id}` | `inventory.read` + 사업자 범위 | 공급 SKU 수 포함 |
| `POST /suppliers {business_entity_id, name, business_registration_no?, lead_time_days?, payment_terms?, memo?}` | `purchase.manage` | 사업자 안에서 이름 유일(`409 SUPPLIER_DUPLICATE`) |
| `PATCH /suppliers/{id}` (+ If-Match 선택) | `purchase.manage` | 상태 ACTIVE·INACTIVE |
| `GET /suppliers/{id}/skus` · `PUT /suppliers/{id}/skus {sku_id, unit_cost, tax_type?, moq?, order_multiple?, lead_time_days?, supplier_sku_code?, status?}` | 조회 `inventory.read` / 저장 `purchase.manage` | 공급처 × SKU 하나(덮어쓰기, 감사 이력) |

## 2. 발주 (DLG-191~195)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `POST /purchase-orders {supplier_id, node_id, expected_on?, memo?, lines[{sku_id, quantity, unit_cost?, moq_override_reason?}]}` | `purchase.manage` | DRAFT. `SUPPLIER_SKU_MISSING`, `MOQ_NOT_MET`, `NODE_INVENTORY_EXTERNAL` |
| `PATCH /purchase-orders/{id}` (+ If-Match) | `purchase.manage` | 작성 중만(`409 PO_NOT_DRAFT`), lines 를 주면 전체 교체 |
| `POST /purchase-orders/{id}/submit` | `purchase.manage` | 승인 요청 `PURCHASE_ORDER` → `POST /approvals/{id}/decision`(본인 불가, 승인자 `purchase.manage`) |
| `POST /purchase-orders/{id}/supplier-confirm {expected_on?, supplier_ref?}` | `purchase.manage` | PLACED → SUPPLIER_CONFIRMED |
| `POST /purchase-orders/{id}/receipts {note?, lines[{po_line_id, good_qty, rejected_qty?, unit_cost?, reject_reason?, lot_code?, manufactured_on?, expires_on?, location_code?}]}` | `inventory.operate` + 거점 범위 | 정상 → 재고 입고(실매입 단가), 불량 → 반송 업무. `PO_OVER_RECEIPT` |
| `POST /purchase-orders/{id}/cancel {reason}` · `POST /purchase-orders/{id}/close {reason}` | `purchase.manage` | 입고 전 취소 / 부분 입고 후 잔량 종료 |
| `GET /purchase-orders?business_entity_id&status&supplier_id` · `GET /purchase-orders/{id}` | `inventory.read` | 발주·입고·반송 수량, 단가 차이, 입고 이력 |
| `GET /purchase-orders/inbound?business_entity_id&sku_id` | `inventory.read` | 입고예정(사업자 × SKU × 거점, 가장 빠른 예정일) |

입고 부대비용: `POST /finance/landed-costs` 에 `purchase_order_id` 를 주면 그 발주 입고분에 배부(DLG-196). 재고 리포트 행에 `inbound_qty`(입고예정).

## 2-1. 단계별 입고 (DLG-220~225)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `POST /purchase-orders/{id}/inbound-shipments {expected_on?, carrier?, tracking_no?, note?, lines[{po_line_id, quantity}]}` | `purchase.manage` | EXPECTED. `INBOUND_OVER_PLAN`(잔량 − 진행 중 다른 입고 건 초과) |
| `POST /inbound-shipments/{id}/arrive {lines[{line_id, arrived_qty}], note?}` (+ If-Match) | `inventory.operate` + 거점 범위 | → ARRIVED, 전 품목 필수(안 온 품목 0). 재고 미반영 |
| `POST /inbound-shipments/{id}/start-inspection` (+ If-Match) | 〃 | → INSPECTING |
| `POST /inbound-shipments/{id}/inspect {lines[{line_id, good_qty, rejected_qty?, over_qty?, reject_reason?, unit_cost?, lot_code?, manufactured_on?, expires_on?}]}` (+ If-Match) | 〃 | 정상+불량+초과 = 도착. 정상 → 재고 입고(검수 대기·`PUTAWAY_PENDING`, 판매불가), 불량·초과 → 반송 업무, 차이 → `INBOUND_DISCREPANCY`. → PUTAWAY(정상 0 이면 완료) |
| `POST /inbound-shipments/{id}/putaway {lines[{line_id, quantity?, location_code?}]}` (+ If-Match) | 〃 | 판매가능 전환 + 로케이션 이동, 부분 가능. 다 끝나면 COMPLETED \| DISCREPANCY |
| `POST /inbound-shipments/{id}/cancel {reason}` (+ If-Match) | `purchase.manage` | 검수 확정 전만 |
| `GET /inbound-shipments?business_entity_id&status(OPEN=진행 중)&po_id` · `GET /inbound-shipments/{id}` | `inventory.read` + 거점 범위 | 품목별 예정·도착·차이·정상·불량·초과·적치, 상태 전환 이력 |

진행 중(EXPECTED·ARRIVED·INSPECTING) 입고 건이 있는 발주 품목은 `POST /purchase-orders/{id}/receipts`·`cancel`·`close` 가 `409 INBOUND_SHIPMENT_ACTIVE`.

## 3. 발주 제안 (DLG-210·211)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /purchase-orders/suggestions?business_entity_id&cover_days(14)` | `inventory.read`(금액은 원가·발주 권한) | 일평균·가용·입고예정·리드타임·목표 일수·부족·제안 수량(MOQ·발주단위)·근거 |
| `POST /purchase-orders/from-suggestions {node_id, items[{supplier_sku_id, quantity}]}` | `purchase.manage` | 공급처별 작성 중 발주 |

