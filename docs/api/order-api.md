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
