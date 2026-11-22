# 출고거점·재고 API (M3 I1~I3)

공통 규칙(인증·오류 형식·If-Match·멱등키)은 `foundation-api.md` §1 과 같다. 재고는 **소유 사업자** 단위로 집계하며, 모든 읽기·쓰기에 사용자별 사업자 범위(ADR-0002)를 적용한다. 출고거점(FULFILLMENT_NODE) 범위 차원은 I6 에서 연다(DLG-087).

## 1. 출고거점 (I1)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /fulfillment-nodes` | `logistics_account.read` | 화주계정 연결 목록은 조회 범위 안의 사업자만 표시 |
| `POST /fulfillment-nodes` | `logistics_account.manage` | `{code, name, logistics_provider_code, inventory_source?, cutoff_time, address_note?}`. `inventory_source` 기본값: 자체 WMS = `INTERNAL`, 3PL·플랫폼 = `EXTERNAL`. 출고 마감 필수(DLG-040). 코드 중복 → `FULFILLMENT_NODE_DUPLICATE` |
| `GET /fulfillment-nodes/{id}` | `logistics_account.read` | `ETag` |
| `PATCH /fulfillment-nodes/{id}` | `logistics_account.manage` | `If-Match`. `{name?, cutoff_time?, address_note?, status?: ACTIVE/SUSPENDED/CLOSED}`. 재고·진행 중 확보가 남으면 닫기 거부 `NODE_HAS_STOCK`, 닫은 거점 변경 `NODE_CLOSED` |
| `POST /fulfillment-nodes/{id}/shippers` | `logistics_account.manage` + 화주계정 사업자 쓰기 범위 | `{shipper_account_id}`. 3PL·플랫폼 거점만(`NODE_SHIPPER_NOT_APPLICABLE`), 물류사업자 일치(`NODE_SHIPPER_PROVIDER_MISMATCH`), 운영 중 계정만, 사업자당 1개(`NODE_SHIPPER_DUPLICATE`) |
| `GET/POST /fulfillment-nodes/{id}/locations` | read / manage | 자체 WMS 거점만(`NODE_LOCATION_NOT_APPLICABLE`). `{code, zone?}` |

- 3PL·플랫폼 거점에 사업자의 화주계정이 연결되어 있지 않으면 그 사업자의 재고를 입고·확보할 수 없다 → `422 OWNER_NOT_ON_NODE` (T05).

## 2. 재고 버킷·집계·원장 (I2)
- **버킷**(`inventory_stock`): (소유 사업자, SKU, 거점, 로케이션, LOT, 품질, 보류사유)별 실물 수량.
- **집계**(`inventory_position`): (소유 사업자, SKU, 거점)별 판매가능 실물(`sellable_on_hand`)·확보수량(`committed`). 동시 확보의 잠금 단위. DB 제약 `committed ≤ sellable_on_hand`.
- **원장**(`inventory_transaction`): append-only(수정·삭제 트리거 거부). 실물(`on_hand_delta`)·판매가능(`sellable_delta`)·확보(`committed_delta`) 변화를 따로 기록.
- **ATP**: 거점 ATP = 판매가능 실물 − 확보. 사업자 전체 ATP = Σ거점 ATP − 사업자 SKU 정책 안전재고(0 미만이면 0).

| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /inventory/positions?business_entity_id=&node_id=&sku_id=&q=&non_zero=` | `inventory.read` | 단계별(`reserved`·`allocated`·`picking`) 확보 수량, `atp`, `non_sellable_on_hand` 포함 |
| `GET /inventory/stocks?business_entity_id=&node_id=&sku_id=&q=&quality_status=` | `inventory.read` | 수량 > 0 버킷. LOT·유통기한·로케이션 |
| `GET /inventory/transactions?…&txn_type=&limit=&cursor=` | `inventory.read` | 최신순 커서 페이지 |
| `GET /inventory/availability?business_entity_id=&sku_id=` | `inventory.read` + 사업자 조회 범위 | `{safety_stock, sellable_on_hand, committed, atp, nodes[]}` |
| `GET /inventory/verification` | `inventory.read` | 불일치 목록(정상 = 빈 배열): 집계 vs 판매가능 버킷 합, 확보 vs 활성 예약 합, 버킷 vs 원장 실물 누적, 확보 vs 원장 확보 누적 |
| `POST /inventory/receipts` | `inventory.operate` + 사업자 쓰기 범위, `Idempotency-Key` | `{business_entity_id, sku_id, node_id, quantity, quality_status?=SELLABLE, hold_reason?, lot_code?, manufactured_on?, expires_on?, location_code?, reference_type?, reference_id?, reason?}`. 재고관리 SKU 만(`SKU_NOT_INVENTORY_MANAGED`), LOT·유통기한 관리 SKU 는 필수, 같은 LOT 다른 유통기한 `LOT_EXPIRY_MISMATCH`, 물류사 재고 기준 거점 `NODE_INVENTORY_EXTERNAL` |
| `POST /inventory/quality-changes` | `inventory.operate` + 사업자 쓰기 범위, `Idempotency-Key` | `{stock_id, quantity, to_quality_status, to_hold_reason?, reason}`(사유 필수). 확보된 수량은 판매 불가로 돌릴 수 없음 → `409 STOCK_COMMITTED {movable}`. 원장 2행(빠지는 버킷·들어가는 버킷) + 감사로그 |

## 3. 확보(예약 → 할당 → 피킹 → 출고 확정) (I3, docs/04 §4)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `POST /inventory/reservations` | `inventory.operate` + 사업자 쓰기 범위, `Idempotency-Key` | `{business_entity_id, node_id, demand_type, demand_ref, lines: [{sku_id, quantity}], expires_at?}`. **전부 확보하거나 전부 실패**(DLG-089): 부족 시 `409 INSUFFICIENT_AVAILABLE_STOCK {lines: [{sku_id, requested, available, node_atp, total_atp_after_safety_stock}]}`. 같은 수요의 활성 확보가 같은 내용이면 그대로 반환, 다르면 `409 RESERVATION_EXISTS` |
| `GET /inventory/reservations?business_entity_id=&node_id=&sku_id=&state=ACTIVE|…&demand_type=&demand_ref=` | `inventory.read` | |
| `GET /inventory/reservations/{id}` | `inventory.read` | 범위 밖 → 404 |
| `POST /inventory/reservations/{id}/allocate` | `inventory.operate` | RESERVED → ALLOCATED. 거점 운영·소유자 허용 재검사. 수량 변화 0 |
| `POST /inventory/reservations/{id}/pick` | `inventory.operate` | ALLOCATED → PICKING. 수량 변화 0 |
| `POST /inventory/reservations/{id}/consume` | `inventory.operate` | PICKING → CONSUMED. 판매가능 실물 −Q(유통기한 빠른 LOT 부터, FEFO), 확보 −Q, ATP 변화 0 |
| `POST /inventory/reservations/{id}/release` | `inventory.operate` | 활성 → RELEASED. 확보 −Q, ATP +Q. 피킹 중이면 `{pick_return_confirmed: true}` 필수(`PICK_RETURN_CONFIRMATION_REQUIRED`) |
| `POST /inventory/reservations/release-by-demand` | `inventory.operate` | `{demand_type, demand_ref, reason?, pick_return_confirmed?}`. 피킹 중 줄이 있는데 확인이 없으면 전체 거절 |

- 이미 목표 단계면 같은 결과를 돌려준다(재시도 안전). 그 밖의 전환 → `409 RESERVATION_STATE_INVALID {state, target}`.
- 만료(`expires_at`)가 지난 `RESERVED` 확보는 작업자가 1분마다 자동 해제한다(원장 사유 "확보 만료 자동 해제", 처리자 시스템). 할당 이후 단계는 만료로 풀지 않는다.
- 모든 확보 변화는 Outbox 이벤트 `InventoryAvailabilityChanged {business_entity_id, sku_id, node_id}` 를 남긴다(채널 재고 전송 M5 용).

## 4. 오류 코드 요약
`OWNER_NOT_ON_NODE`, `NODE_INVENTORY_EXTERNAL`, `NODE_NOT_ACTIVE`, `NODE_HAS_STOCK`, `NODE_CLOSED`, `NODE_SHIPPER_*`, `NODE_LOCATION_*`, `SKU_NOT_INVENTORY_MANAGED`, `SKU_ARCHIVED`, `LOT_EXPIRY_MISMATCH`, `STOCK_INSUFFICIENT`, `STOCK_COMMITTED`, `STOCK_BUCKET_MISMATCH`, `INSUFFICIENT_AVAILABLE_STOCK`, `RESERVATION_EXISTS`, `RESERVATION_STATE_INVALID`, `PICK_RETURN_CONFIRMATION_REQUIRED`.
