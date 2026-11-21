# 내부 API 계약·오류 규칙·엔드포인트 목록

## 1. 기본 약속
- Base: `/api/v1`. 내부 식별자는 UUID 권장, 사용자 표시번호는 별도.
- 인증: 사용자 세션/접근토큰. 모든 요청 서버에서 workspace/scope/action 권한 확인.
- 시간: DB UTC, UI Asia/Seoul. 응답 ISO 8601 offset. 금액 decimal string + currency + 세금구분.
- 외부 API 연결키/토큰/PII 원문을 응답에 포함하지 않는다.
- 목록 `limit/cursor/sort/filters` 및 `total_estimate`(정확한 총합이 아니면 표시).
- 수정/중복위험 POST는 `Idempotency-Key` 필요; 비동기 작업 `202 Accepted + job_id`.
- 409 충돌(이미 진행/수량부족), 422 검증, 403 권한, 423 마감 등 오류 코드와 대처 안내.

## 2. 응답 구조
```json
{
 "data": [{"id":"ord-1","business_entity_id":"biz-a","sales_unit_id":"store-1","status":"PROCESSING"}],
 "page": {"next_cursor":null},
 "meta": {"as_of":"2026-11-21T10:00:00+09:00","source_status":"CURRENT"}
}
```
오류:
```json
{
 "error":{"code":"INSUFFICIENT_AVAILABLE_STOCK","message":"추가 판매가능재고가 부족합니다.","retryable":false,"details":{"sku_id":"sku-1","requested":8,"available":2}},
 "request_id":"req-1"
}
```

## 3. 엔드포인트 카탈로그
| 영역 | HTTP | 경로 | 주요 역할 |
|---|---|---|---|
| 운영그룹 | GET | `/workspaces/current` | 워크스페이스 정보 |
| 사업자 | GET/POST | `/business-entities` | 목록·추가 |
| 사업자 | PATCH | `/business-entities/{id}` | 변경·이력 |
| 계정 | GET/POST | `/channel-accounts` | 여러 사업자/채널 계정 |
| 판매단위 | GET/POST | `/channel-accounts/{id}/sales-units` | shop_no 등 |
| 사용자 | GET/PUT | `/users/{id}/access-policy` | 업무범위/권한 설정 |
| 상품 | GET/POST | `/products` | 목록/등록 |
| 상품 | GET/PATCH | `/products/{id}` | 상세/변경 |
| SKU | GET/POST | `/skus` | SKU 목록/등록 |
| SKU | GET/PATCH | `/skus/{id}` | SKU 상세/변경 |
| 사업자 SKU | GET/PUT | `/business-entities/{id}/sku-policies/{skuId}` | 가격/원가/재고정책 |
| 묶음 | GET/POST | `/boms` | 묶음/조립 버전 |
| GWP | GET/POST | `/gwp-campaigns` | 조건/증정 정책 |
| 채널상품 | GET/POST | `/channel-listings` | 계정별 매핑 |
| 주문 | GET | `/orders` | 사업자/계정 전체 조회 |
| 주문 | GET | `/orders/{id}` | 주문/상품/출고/환불 상세 |
| 주문 | POST | `/orders/{id}/hold` | 보류 |
| 주문 | POST | `/orders/{id}/release-hold` | 보류 해제 |
| 재고 | GET | `/inventory/stocks` | 사업자·거점·LOT별 재고 |
| 재고 | GET | `/inventory/ledger` | 수량/상태 원장 |
| 재고 | POST | `/inventory/reservations` | 원자적 재고확보 |
| 재고 | POST | `/inventory/adjustments` | 승인/원장 기반 조정 |
| 재고 | POST | `/inventory/transfers` | 거점간 이동 |
| 재고 | GET/POST | `/inventory/counts` | 실사 |
| 재고 | GET | `/inventory/availability` | 안전한 ATP·외부시각 |
| 출고 | GET/POST | `/fulfillment-orders` | 출고목록/생성 |
| 출고 | POST | `/fulfillment-orders/{id}/dispatch` | 물류 전송 비동기 |
| 출고 | POST | `/fulfillment-orders/{id}/cancel` | 출고중지 검증 |
| 배송 | GET | `/shipments` | 송장/배송 조회 |
| 클레임 | GET/POST | `/claims` | 취소/반품/교환 요청 |
| 클레임 | GET | `/claims/{id}` | 상태축·관련 거래 |
| 반품 | POST | `/returns/{id}/inspections` | 검수 수량별 결과 |
| 환불 | POST | `/claims/{id}/refund-requests` | 중복방지·권한·승인 |
| CS | GET/POST | `/inquiries` | 문의·응답 초안 |
| 공급처 | GET/POST | `/suppliers` | 공급처 관리 |
| 발주 | GET/POST | `/purchase-orders` | 발주/부분입고 |
| 정산 | GET | `/settlements` | 정산원본/확정 |
| 정산 | POST | `/settlement-imports` | 파일/원본 수집·미리보기 |
| 정산 | POST | `/settlements/reconcile` | 주문·입금 대사 작업 |
| 입금 | GET/POST | `/bank-deposits` | 수동/파일 입금 |
| 손익 | GET | `/profit/orders/{id}` | 주문 공헌이익 |
| 손익 | GET | `/profit/summary` | 사업자·계정·SKU 집계 |
| 마감 | POST | `/period-closes` | 월 마감 승인 |
| 업무함 | GET/PATCH | `/work-items` | 업무 조회/배정/상태 |
| 연동 | GET | `/integrations/connections` | 계정별 상태 |
| 연동 | GET | `/integrations/capabilities` | 기능별 실제 권한 |
| 연동 | POST | `/integrations/{id}/sync-jobs` | 수집/재처리 |
| 검증 | GET/POST | `/data-validations` | 정합성 검사 |
| 내보내기 | POST | `/exports` | 권한 검증 후 비동기 |
| 작업 | GET | `/jobs/{id}` | 비동기 진행률·결과 |

## 4. 사업자별 기본 조회 / 개별 제한
`GET /orders?business_entity_id=all`은 **현재 사용자가 허용받은 운영그룹 내 사업자 전체**를 뜻함. 다른 그룹 접근 불가. 명시적으로 제한된 사업자는 쿼리 파라미터에 직접 기재해도 403/404 정책에 따라 조회되지 않음. 집계와 다운로드에도 같은 범위를 강제.

## 5. 요청 예시
재고 확보:
```http
POST /api/v1/inventory/reservations
Idempotency-Key: reserve-ord-100-item-1
Content-Type: application/json
```
```json
{
 "order_item_id":"item-1",
 "owner_business_entity_id":"biz-a",
 "sku_id":"sku-1",
 "fulfillment_node_id":"node-1",
 "quantity":2
}
```
검증: 주문의 실제 판매사업자·허용 소유권 정책·거점/LOT/품질·ATP를 서버에서 대조. 클라이언트의 사업자·수량을 무조건 신뢰하지 않음.

반품 검수:
```json
{
 "return_item_id":"return-item-1",
 "received_quantity":2,
 "decisions":[
   {"condition":"NORMAL","disposition":"RESTOCK","quantity":1},
   {"condition":"DAMAGED","disposition":"UNSALEABLE","quantity":1,"reason":"PACKAGE_DAMAGED"}
 ]
}
```
검증: 검수결과 수량의 합 = 해당 반품상품 실입고량, 상태전이 유효, 승인/권한 유효.

정산 파일 미리보기:
```json
{
 "business_entity_id":"biz-a",
 "channel_account_id":"account-1",
 "source_file_id":"file-1",
 "period_start":"2026-11-01",
 "period_end":"2026-11-30",
 "preview_only":true
}
```
결과: `rows.total/matched/review_required/invalid`, 원본과 행별 오류 보고서. 별도 확정 명령이 필요.

손익 응답:
```json
{
 "order_id":"ord-1","business_entity_id":"biz-a",
 "currency":"KRW","basis":"EXCLUDING_VAT",
 "amounts":{"net_revenue":"27000","product_cogs":"9000","selling_fee":"1500","shipping":"3200","return_shipping":"3000","gwp":"1200","contribution_profit":"9100"},
 "profit_status":"PARTIALLY_CONFIRMED", "pending":["ACTUAL_LOGISTICS_BILL"]
}
```

## 6. 감사·보안·멱등성
- API로 사업자/계정/소유권을 변경하는 모든 명령은 요청자·요청 범위·이전/이후 값 감사.
- 응답 마스킹과 `export`에도 개인정보·원가/손익 권한 적용.
- 외부 출고·환불 명령은 한 번 전송 후 타임아웃 시 결과조회/중복확인 필수; 무조건 재POST 금지.
- SQL·OpenAPI 스타터는 전체 기능 완성명세가 아니므로 실제 구현 때 각 모듈의 request/response DTO/schema를 확장하고 계약 테스트를 생성할 것.
