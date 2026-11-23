# 주문 API (M4 O1~O3)

공통 규칙(인증·오류 형식·If-Match·멱등키)은 `foundation-api.md` §1 과 같다. 주문은 **같은 운영그룹이면 기본 조회 허용**, 사용자별 사업자·판매계정 제한은 서버에서 거부(범위 밖 단건 404, 범위 밖 필터 403). 다른 운영그룹은 절대 노출하지 않는다(RLS).

## 1. 식별·귀속 (DLG-112, DLG-118)
- 주문 = `판매계정 + 판매단위 + 원천유형(ORDER·PLATFORM_FULFILLMENT·MANUAL) + 외부주문키` 유일. 주문상품 = 주문 + 외부 상품주문키(없으면 원천 줄 번호). 외부 키는 PK 가 아니다(내부 `order_no` = `O{yyMMdd}-{일련번호}`).
- 주문 사업자 = 주문 시각의 판매계정 귀속(`channel_account_business_at`). DB 트리거가 생성 시 검증하고, 식별·귀속 필드는 변경 불가. 귀속이 바뀌어도 기존 주문은 재계산하지 않는다.

## 2. 수집 파이프라인 (docs/04 §1, DLG-119)
| 단계 | 규칙 |
|---|---|
| 원본 | `order_raw` append-only. 내용 해시로 같은 원본은 한 번만 저장(재수신 = `DUPLICATE`). 원문 암호화 + 개인정보 마스킹 사본, 매핑 규칙 버전·작업·연결 추적 |
| 거절 | 형식 오류·없는 판매단위·주문 시각에 귀속 없음 → 원본 `REJECTED` + 업무함 `ORDER_INGEST_REJECTED`, 주문 미생성 |
| 역순 | 외부 갱신 시각 ≤ 반영된 값 → 원본만 `IGNORED_STALE`, 주문 불변 |
| 갱신 | 결제 확인·외부 상태·수령인 변경 반영, 취소 수량은 **늘어나기만**(출고된 수량 초과분·감소·주문 수량 변경은 반영하지 않고 업무함 `ORDER_EXTERNAL_ANOMALY`), 새 상품주문 추가 |
| 상품 연결 | 채널상품(판매단위 + 외부 상품·옵션) → 주문 시각에 유효한 매핑(없으면 현재 열린 매핑). 채널상품이 없으면 미연결 채널상품을 만들어 C3 화면에서 연결. 연결되면(`ChannelListingMapped`) 해당 주문 자동 재검증 |
| 검증·보류 | 시스템 보류 사유: `UNMAPPED_ITEM`, `AMOUNT_MISMATCH`(상품 금액 − 할인 + 배송비 ≠ 결제 금액, HIGH), `RECIPIENT_MISSING`(플랫폼 물류 제외), `SKU_NOT_SELLABLE`. 원인이 사라지면 자동 해제. 최저판매가 미만은 보류 없이 업무함 `ORDER_PRICE_BELOW_MIN`. 보류 업무함 `ORDER_ON_HOLD` |
| 상태 | 결제 전 `RECEIVED`, 결제 후 `CONFIRMED`, 모든 상품 취소 `CANCELLED`(DLG-113; `PROCESSING`·`COMPLETED` 는 O4~O5) |

## 3. 엔드포인트
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /orders?business_entity_id=&channel_account_id=&status=&on_hold=&hold_reason=&q=&from=&to=&limit=&cursor=` | `order.read` | 개인정보는 마스킹(`recipient_name_masked`) |
| `GET /orders/{id}` | `order.read` | `{order, items[], events[], raws[](마스킹 원본)}`, `ETag` |
| `POST /orders/{id}/pii` | `order.read` + `pii.read` | `{reason}` 필수. 구매자·수령인 원문, 열람마다 감사로그 `ORDER_PII_REVEALED` |
| `POST /orders/{id}/hold` | `order.update` + 쓰기 범위 | `If-Match`, `{note}` 필수. 사유 `MANUAL` 추가(`ORDER_ALREADY_HELD`) |
| `POST /orders/{id}/release-hold` | 같음 | `If-Match`. `MANUAL` 만 해제, 없으면 `ORDER_HOLD_NOT_MANUAL`(시스템 보류는 원인 해결로) |
| `POST /orders/{id}/revalidate` | `order.update` | 다시 연결·검증 |
| `POST /orders/imports` | `order.update` + 판매계정 쓰기 범위, `Idempotency-Key` | `{channel_account_id, orders: [표준 주문]}` 1~500건. 주문별 결과 `{created, updated, duplicate, stale, rejected, results[]}` |
| `POST /integrations/{connectionId}/sync-jobs {job_type: "ORDER_FETCH"}` | `integration.sync` + 지원 기능 `ORDER/READ` | 구간 = 마지막 워터마크 − 30분 ~ 지금(첫 실행 3일), 쪽 커서, 주문마다 별도 트랜잭션, 워터마크 `integration_sync_state` |

표준 주문(JSON, snake_case): `external_order_key, source_type?, sales_unit_key?(기본 DEFAULT), ordered_at, paid_at?, external_status?, external_updated_at?, shipping_fee?, total_amount, buyer{name, phone, email}, recipient{name, phone, postal_code, address1, address2, memo}, items[{external_item_key?, external_product_id, external_option_id?, product_name, option_name?, quantity, canceled_quantity?, unit_price, line_amount, seller_discount?, channel_discount?, external_status?}]`. 금액은 부가세 포함 원 단위.

## 4. 출고 구성 확정·사은품 (O3, DLG-121~122)
- 결제 확인 + 시스템 보류 없음 → 사은품 판정·지급(0원 주문상품, `gift_source = INTERNAL`) → 출고 구성 확정(`composition_status = FIXED`). 상세 응답 `components[]`(재고 SKU·필요 수량·근거 DIRECT/BOM/GIFT·BOM 버전), `gwp_result`(지급·미지급 사유).
- 보류 사유 추가: `GIFT_OUT_OF_STOCK`(행사 정책 '주문 보류' + 사은품 재고 없음), `BOM_MISSING`(가상묶음에 유효한 판매묶음 BOM 없음).
- 표준 주문 상품 필드 추가: `gift`(채널 사은품 여부), `channel_campaign_ref`(채널 행사 번호).

## 5. 개인정보 보관 (DLG-120)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /orders/privacy-policies/{businessEntityId}` | `order.read` | `{active_days(기본 90), retention_years(기본 5), configured}` |
| `PUT /orders/privacy-policies/{businessEntityId}` | `access.manage` | `If-Match`(설정 후). 30~3650일, 1~10년 |
- 상세 `pii_state`: `ACTIVE` → (주문 종료 + 운영 열람 기간) `ARCHIVED` → (주문 시각 + 보존기간) `PURGED`.
- `POST /orders/{id}/pii`: `ARCHIVED` 는 `access.manage` + `purpose`(DISPUTE·LEGAL_REQUEST·TAX_AUDIT) 필수(`PII_ARCHIVED`), `PURGED` 는 `409 PII_PURGED`.

## 6. 출고 배정·출고지시 (O4, DLG-123~128)
출고지시(`fulfillment_order`) = (주문, 출고거점) 단위. 재고 소유 = 주문 사업자. 확보(I3)는 출고지시마다 하나의 수요(`FULFILLMENT_ORDER`, 출고지시 id)로 묶이고, 배정 즉시 `ALLOCATED`(출고지시 결합)로 전환된다 — 예약→할당 단계에서 같은 수량을 두 번 차감하지 않는다.

| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `POST /orders/{id}/allocate` | `order.update` + `inventory.operate`, 사업자·판매계정 쓰기 범위 | `{outcome: ALLOCATED·FAILED·NOTHING_TO_ALLOCATE·NOT_REQUIRED, fulfillment_order_id, node_name, shortages[]}`. 보류 중 `409 ORDER_ON_HOLD`, 구성 확정 전 `409 ORDER_NOT_READY` |
| `GET /fulfillment-orders?business_entity_id&status&node_id&order_id` | `order.read` + 사업자·판매계정·출고거점 읽기 범위 | 최신순 300건 |
| `GET /fulfillment-orders/{id}` | 같음 | 범위 밖·다른 워크스페이스는 `404` |
| `POST /fulfillment-orders/{id}/dispatch` | `order.update` + `inventory.operate` + 거점 쓰기 범위 | `FAILED → ALLOCATED` 후 재전송 |
| `POST /fulfillment-orders/{id}/manual-accept {external_ref, accepted_at?}` | 같음 | 수동 전달분 물류사 접수 기록, 전달 업무 종료 |
| `POST /fulfillment-orders/{id}/cancel {reason, pick_return_confirmed?}` | 같음 | 전송 전: 즉시 취소·확보 해제. 전송 후: `CANCEL_REQUESTED`(확보 유지 + 업무 `FULFILLMENT_CANCEL_CONFIRM`) → `pick_return_confirmed: true` 로 확정. `SHIPPED` 는 `409 FULFILLMENT_ALREADY_SHIPPED`(반품으로 처리, O6) |

- 상태: `ALLOCATED → REQUESTED → ACCEPTED → (PICKING → PACKING → SHIPPED: O5·O7)`, `FAILED`, `CANCEL_REQUESTED → CANCELLED`. 변경은 `fulfillment_event`(추가 전용)와 감사로그에 남는다.
- 주문 상세 `allocation_status`: `PENDING · ALLOCATED · FAILED · NOT_REQUIRED(플랫폼 물류)`. 배정되면 주문 상태 `CONFIRMED → PROCESSING`, 모든 출고지시가 취소되면 되돌아간다.
- 자동 배정: 출고 구성 확정(`OrderComposed` outbox) 뒤 시스템 자동 처리 주체로 같은 배정 로직을 실행(`commerce.fulfillment.auto-allocate`, 기본 켬).
- 출고지시 전송(`FULFILLMENT_DISPATCH` 작업, 최대 6회): 자체 WMS → `INTERNAL` 즉시 접수. 3PL → 사업자 화주계정 연결 + 지원 기능 `OUTBOUND_REQUEST/WRITE` 확인 시 API(멱등 키 = `dispatch_key`), 아니면 `MANUAL` + 업무 `FULFILLMENT_DISPATCH_MANUAL`. 재시도는 먼저 같은 키로 사후조회(T11). 5회 연속 응답 불명 → `DISPATCH_UNKNOWN` 검토 업무.
- 출고거점 `routing_priority`(0~10000, 작을수록 먼저, 기본 100): `PATCH /fulfillment-nodes/{id} {routing_priority}`.

## 7. 출고 확정·송장·배송 추적·채널 발송처리 (O5, DLG-129~136)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /carriers` | 로그인 | 내부 택배사 코드(CJ·LOTTE·HANJIN·EPOST·LOGEN·KDEXP·DAESIN·ILYANG·CVSNET·CU_POST·DIRECT·OTHER) |
| `POST /fulfillment-orders/{id}/ship {shipped_at, parcels[{carrier_code, tracking_no, items?[{sku_id, quantity}]}]}` | `order.update` + `inventory.operate` + 사업자·판매계정·거점 쓰기 범위 | 출고지시 전체를 출고 확정. 박스 1~20. 품목을 적으면 합이 출고지시와 같아야 함(`422 PARCEL_ITEMS_MISMATCH`), 안 적으면 첫 박스에 전부. 같은 송장 재요청 = 중복(200), 다른 송장 = `409 FULFILLMENT_ALREADY_SHIPPED`, 취소된 출고 = `409 FULFILLMENT_CANCELLED`. 미래 시각·배정 전 시각은 `422` |
| `GET /shipments?business_entity_id&status&channel_dispatch_status&order_id&fulfillment_order_id&q` | `order.read` + 사업자·판매계정·거점 읽기 범위 | 최신순 300건, `q` = 송장번호·주문번호·출고번호 |
| `GET /shipments/{id}` | 같음 | 품목·배송 이력 포함, 범위 밖·다른 워크스페이스 `404` |
| `POST /shipments/{id}/tracking-events {status, occurred_at?, description?, location?}` | `order.update` + 쓰기 범위 | `{result: APPLIED·IGNORED_STALE·DUPLICATE, shipment}`. 이전 시각·역행 기록은 이력에만 남음 |
| `POST /shipments/{id}/channel-dispatch/retry` | 같음 | `FAILED`·`MANUAL_REQUIRED` → `PENDING` 후 재전송 |
| `POST /shipments/{id}/channel-dispatch/manual-done {note?}` | 같음 | 판매자센터에서 직접 발송처리한 사실 기록, 업무 종료 |
| `POST /shipments/imports` (multipart `file`) | `order.update` + `inventory.operate` | 열: 출고번호·택배사(코드·이름)·송장번호·출고일시(선택, KST). 출고지시마다 따로 반영, `{row_count, shipped, duplicate, failed, results[]}` |
| `POST /integrations/{connectionId}/sync-jobs {job_type: "SHIPMENT_SYNC"}` | `integration.sync` + 지원 기능 `OUTBOUND_STATUS/READ` 또는 `TRACKING/READ` | 3PL 출고상태(PICKING·PACKING·SHIPPED+송장)·배송 단계 수신. 정기 실행 10분 |

- 배송 단계: `LABEL_CREATED → PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED`, `EXCEPTION`(배송완료 전까지, 업무 `SHIPMENT_EXCEPTION`). 송장 등록은 `LABEL_CREATED` 일 뿐 배송완료가 아니다.
- 채널 발송처리 `channel_dispatch_status`: `PENDING · SENT · MANUAL_REQUIRED · MANUAL_DONE · FAILED · NOT_REQUIRED`(추가 박스에 새 품목 없음·수동 주문). 판매계정 연결 + `SHIPMENT_DISPATCH/WRITE` 확인 시만 API(멱등 키 = `shipment_no`).
- 주문: `order_item.shipped_qty`(구성품 전부 출고된 단위), `shipping_status`(`NONE · PARTIALLY_SHIPPED · SHIPPED · DELIVERED`), `delivered_at`. 전부 배송완료 후 8일(`commerce.order.complete-after-delivery-days`)이 지나고 진행 중 출고가 없으면 `COMPLETED`.
- 출고지시 `tracking_sla_hours`(거점, 기본 24): 물류사 접수 후 이 시간 안에 송장이 없으면 업무 `FULFILLMENT_TRACKING_MISSING`. `PATCH /fulfillment-nodes/{id} {tracking_sla_hours}`.

## 8. 클레임·반품 입고/검수·환불·폐기 (O6, DLG-137~146)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /claims?business_entity_id&type&status(OPEN 포함)&order_id&q` | `order.read` + 사업자·판매계정 범위 | |
| `POST /claims {order_id, claim_type, reason_code, reason_note?, fault_party?, items[{order_item_id, quantity, replacement_sku_id?, replacement_quantity?}], return_shipping_fee?, price_difference?, ship_before_return?}` | `order.update` + 쓰기 범위 | 수량 한도: 취소 = 활성 − 출고 − 대기 취소, 반품·교환 = 출고 − 접수된 반품·교환(`422 CLAIM_QUANTITY_EXCEEDED`). 플랫폼 물류 주문은 채널 수집만 |
| `GET /claims/{id}` | `order.read` | 상품·환불·이력 포함 |
| `POST /claims/{id}/approve {note?}` · `/reject {note}` · `/withdraw {note?}` | `order.update` | 거부·철회는 회수품 입고·환불 진행·취소 반영 전까지 |
| `POST /claims/{id}/return-shipment {carrier_code, tracking_no}` | `order.update` | 회수 `IN_TRANSIT` |
| `POST /claims/{id}/channel-action/manual-done` | `order.update` | 채널 처리 수동 완료 |
| `POST /claims/{id}/refund-requests {amount, executor(CHANNEL·PG·MANUAL), note?}` + `Idempotency-Key` | `claim.refund` ★ | 요청 합 ≤ 환불 예정액(`422 REFUND_EXCEEDS_EXPECTED`), 같은 키 재요청 = 기존 결과 |
| `POST /refunds/{id}/confirm {confirmed_amount, external_ref?, confirmed_at?}` · `/refunds/{id}/fail {reason}` | `claim.refund` ★ | 확정액 ≤ 요청액 |
| `GET /returns/receipts?business_entity_id&status&identification&claim_id` · `GET /returns/receipts/{id}` | `inventory.read` + 사업자·거점 범위 | |
| `POST /returns/receipts {node_id, claim_id? \| business_entity_id, received_at?, carrier_code?, tracking_no?, note?, lines[{sku_id, quantity, claim_item_id?, lot_code?, expires_on?}]}` | `inventory.operate` + 거점 쓰기 | 일치 줄 = 검수대기, 불일치·원주문 없음 = 미확정(보류 `UNIDENTIFIED_RETURN`) + 업무 `UNIDENTIFIED_RETURN`. 3PL 거점은 재고 이동 없음 |
| `POST /returns/receipts/{id}/inspections {lines[{receipt_line_id, decisions[{condition, quantity, reason?}]}]}` | `inventory.operate` | 판정 = `SELLABLE·REPACK·DEFECTIVE·DAMAGED·EXPIRED·DISPOSAL_PENDING·UNDETERMINED`, 줄마다 합 = 남은 입고 수량(`422 INSPECTION_QUANTITY_MISMATCH`). 불일치 줄은 `422 RETURN_LINE_UNIDENTIFIED` |
| `POST /inventory/disposals {stock_id, quantity, reason, evidence_ref?}` + `Idempotency-Key` | 요청 `inventory.operate`, 승인 `approval.decide` + `inventory.dispose` ★(요청자 ≠ 승인자) | `202 {approval_id, book_value}`. 폐기대기·불량·파손·만료 버킷만, 3PL 거점 불가 |
| `POST /integrations/{connectionId}/sync-jobs {job_type: "CLAIM_FETCH"}` | `integration.sync` + `CLAIM_CANCEL·CLAIM_RETURN·CLAIM_EXCHANGE/READ` 중 하나 | 확인된 유형 행만 반영 |

- 상태축: 클레임 `REQUESTED → APPROVED → PROCESSING → COMPLETED`(`REJECTED`·`CANCELLED`), 회수 `NOT_REQUIRED·REQUESTED·IN_TRANSIT·RECEIVED·INSPECTED`, 환불 `NOT_REQUIRED·EXPECTED·REQUESTED·PARTIALLY_CONFIRMED·CONFIRMED·FAILED·CANCELLED`, 재출고 `NOT_REQUIRED·PENDING·SHIPPED`, 채널 반영 `NOT_REQUIRED·PENDING·SENT·MANUAL_REQUIRED·MANUAL_DONE·FAILED`. 완료 = 취소 반영·회수 검수·환불 확정·재출고가 각각 끝났거나 필요 없음.
- 주문 영향: 취소 승인 → `canceled_qty`(출고 전이면 즉시, 물류 전달 후면 출고 취소 확인 뒤), 검수 → `returned_qty`, 교환 → 0원 `EXCHANGE_REPLACEMENT` 주문상품 + 출고 배정, 환불 확정 → 결제 `PARTIALLY_REFUNDED·REFUNDED`. 열린 클레임이 있으면 주문 완료로 넘기지 않는다.
- 금액(부가세 포함 원): 상품 환불 = (상품 금액 − 판매자·채널 할인) × 수량 비례(원 미만 버림, 마지막 수량이 나머지), 출고 전 전체 취소면 배송비 환불, 고객 부담 반품 배송비는 환불에서 차감. 교환 차액 +는 고객 추가 결제, −는 환불 예정. 매출·원가·평가손실 원장 반영은 M5(N1·N5).

## 9. 자체 WMS 기본 (O7, DLG-147~152)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `POST /inventory/location-moves {stock_id, quantity, to_location_code, reason?}` + `Idempotency-Key` | `inventory.operate` + 거점 쓰기 | 같은 거점 안 이동(적치·보충). 실물·판매가능 불변, 원장 `LOCATION_MOVE` 두 줄. 피킹 계획 수량은 `409 STOCK_PLANNED_FOR_PICK`, 3PL 거점 불가 |
| `GET /wms/candidates?node_id` | `inventory.read` | 웨이브에 넣을 수 있는(물류 접수·웨이브 없음) 출고지시 수 |
| `POST /wms/waves {node_id, fulfillment_order_ids?, limit?}` · `GET /wms/waves?node_id&status` · `GET /wms/waves/{id}` | `inventory.operate` / `inventory.read` + 거점 범위 | 자체 WMS·내부 원장 거점만(`422 WMS_NODE_NOT_SELF`). 확보마다 버킷(유통기한 → LOT → 로케이션) 계획, 계획 못 한 출고지시는 `skipped` + 업무 `WMS_PICK_PLAN_SHORT`. 출고지시·확보 → `PICKING` |
| `POST /wms/waves/{id}/picks {location_code, barcode, quantity}` | `inventory.operate` | 바코드 = SKU 바코드 또는 SKU 코드. 모르는 바코드·다른 로케이션·초과는 `422 SCAN_REJECTED`(스캔 기록에 남김) |
| `POST /wms/pick-tasks/{id}/short {reason}` | `inventory.operate` | 남은 수량을 다른 버킷으로 재계획, 없으면 업무 `WMS_PICK_SHORT`. 로케이션 실사 업무 `WMS_LOCATION_DISCREPANCY` |
| `GET /wms/fulfillment-orders/{id}/packing` · `POST /wms/fulfillment-orders/{id}/pack {scans[{barcode, quantity}]}` | `inventory.read` / `inventory.operate` | 피킹 완료 후 스캔 합계가 출고지시 수량과 정확히 같아야 `PACKING`(아니면 `422 SCAN_REJECTED`) |

- 웨이브 출고지시의 출고 확정(`/fulfillment-orders/{id}/ship`)은 `PACKING` 이어야 하고(`409 WMS_NOT_PACKED`), 피킹한 버킷·수량 그대로 차감한다. 웨이브는 출고지시가 모두 출고·취소되면 `CLOSED`.
