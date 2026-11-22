# 업무 상태전이·예외·이벤트 규칙

## 1. 주문 수집 파이프라인
```
원본 수집 → raw 보관 → 중복 판단 → 표준 주문 파싱 → 판매단위/사업자 귀속 확인
 → 외부 옵션/SKU 연결 → 상품·가격/할인·과세 검증 → 채널 행사 GWP 확인
 → 내부 GWP 적용 → BOM 구성 고정 → 재고 확보(원자적) → 출고 배정 →
 출고요청 생성 → 3PL/WMS 전달(비동기) → 물류사 접수 확인 → 피킹/패킹
 → 출고 확정 → 송장 수신·판매채널 전송 → 배송 추적 → 정산/손익
```
- 실패 단계와 원본/변환버전, 주문상품 ID를 기억. 정상 후속 단계는 중복 실행 금지.
- 상품 미연결/할인 불일치/채널 권한 부족/주소 오류는 `업무함` 생성.
- 로켓그로스·직매입 등 플랫폼 물류/거래유형은 동일 출고 명령을 요구하지 않음. 각 채널 지원기능에 따른 읽기/쓰기를 분리.

## 2. 상태축 분리
| 축 | 표준 상태 | 금지할 단순화 |
|---|---|---|
| 주문 | RECEIVED, CONFIRMED, PROCESSING, COMPLETED, CANCELLED | 배송완료만 보고 주문완료 자동 동일시 금지 |
| 결제 | PENDING, PAID, PARTIALLY_REFUNDED, REFUNDED, FAILED | 반품신청=환불완료 금지 |
| 출고 | UNALLOCATED, ALLOCATED, READY, REQUESTED, ACCEPTED, PICKING, PACKING, SHIPPED, FAILED | 외부 지연 이벤트에 상태 역행 금지 |
| 배송 | LABEL_CREATED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION | 송장 발급=배송완료 금지 |
| 클레임 | REQUESTED, REVIEWING, APPROVED, REJECTED, PROCESSING, COMPLETED, CANCELLED | 요청만으로 매출/원가 확정 금지 |
| 환불 | NOT_REQUIRED, EXPECTED, REQUESTED, CONFIRMED, FAILED | 검수 완료=환불완료 금지 |

## 3. 주문상품 수량 불변조건
- `original_qty ≥ canceled_qty ≥ 0`; `active_qty = original_qty - canceled_qty`.
- `shipped_qty ≤ active_qty` (단 교환 재출고는 별도의 출고/교환 참조로 계산).
- `returned_accepted_qty ≤ shipped_qty` (반복 반품과 수량 수정은 원인 확인 후 정정).
- 한 주문은 여러 Fulfillment/Shipment로 분할할 수 있고 채널의 분할발송 허용 여부를 검사.
- 일부 상품 취소/환불/반품 상태는 주문상품 수량과 Claim 집계로 파생.

## 4. 예약·할당·피킹·출고
| 이전 | 명령/조건 | 다음 | 재고 및 기록 |
|---|---|---|---|
| NONE | 정상 주문·원자적 ATP 확인 | RESERVED | 예약 +Q, ATP -Q |
| RESERVED | 허용 거점/소유자/물류형태 검사 | ALLOCATED | 동일 Q 단계 변경(추가 ATP 감소 없음) |
| ALLOCATED | WMS/3PL 작업 지시 접수 | PICKING | 동일 Q 단계 변경 |
| PICKING | 출고 실물확정 | CONSUMED | on_hand -Q, active reserved -Q; ATP 추가 변화 없어야 함 |
| RESERVED | 출고 전 취소 | RELEASED | active reserved -Q, ATP +Q |
| ALLOCATED | 출고 취소가 확인됨 | RELEASED | active reserved -Q, ATP +Q |
| PICKING | 작업복귀 확인 | RELEASED | 출고중지 확인 후 활성수량 해제 |
- 유효한 재고 원본이 EXTERNAL일 때는 외부 예약/출고반영 여부와 Snapshot 워터마크를 매칭; 반영 여부 불명확이면 안전 정책 및 업무함.
- 한 주문상품이 여러 구성부품을 사용하면 각 부품의 확보가 원자적(전부 또는 실패)이도록 처리.

## 5. 반품 → 재고품질 → 재무
```
고객 반품요청 → 클레임 검토/승인 → (환불 예정액 계산)
 ↘ 실제 회수요청 → 회수중 → 도착 → 실물 SKU·수량 검증 → 검수대기
    → 정상 복귀 / 재포장 / 불량 / 파손 / 공급처 반송 / 폐기대기
 ↘ 환불 요청 → 외부 결제/채널 확정 → 매출/세액/수수료 등 조정 원장
 ↘ 회수비/검수비/재포장비/폐기비/재고평가 변동 → 비용 및 재고가치 원장
 → 대사·마감·손익 집계
```
- 환불 완료와 물류 검수완료가 동시일 필요 없음.
- 원주문 없는 입고·다른 상품·수량초과는 `UNIDENTIFIED_RETURN` 등 업무함, 곧바로 판매가능재고로 전환 금지.
- 검수 `정상`은 가용재고 증가; `재포장`은 재포장 완료 후; `불량/파손`은 비가용·불용, 재무 평가정책 적용.
- 폐기 때 이미 평가손실로 인식한 금액을 다시 손실로 인식하지 않음.
- GWP는 최초 증정 출처와 반품 회수 정책에 따라 회수/원가 유지/조정 기록.

## 6. 교환
`교환 요청 → 회수(또는 선출고 허용 검증) → 교환품 재고 확보 → 재출고 → 회수품 검수 → 차액 결제/환불/비용 확정`.
- 동일상품 무상 교환: 원판매 매출을 또 만들지 않음. 교환품 출고원가와 회수품의 상태별 평가를 연결.
- 다른 SKU 교환: 신상품 출고·원상품 회수와 차액결제/환불을 독립 거래로 표현.
- 교환 배송과 반품 회수는 각각 배송건/비용.

## 7. GWP/묶음
- 채널 자체 사은품 인식 → 내부 GWP 캠페인 중복 그룹 검사 → 0원 증정상품 생성/재고 확보 → 피킹 부품과 Packaging BOM 확장.
- 가상묶음: 출고전 거점별 충분한 구성부품 재고 확인; 분할출고 미허용 채널이면 복수 거점 조합 금지.
- 실물세트: 조립 작업의 구성원장·완제품원장을 연결. 세트 재고만 출고.
- GWP 부족: 캠페인의 `END|SUBSTITUTE|SKIP|HOLD` 정책. 결제 총액만 보고 공급 불가능한 GWP를 임의 추가하지 않음.

## 8. 재고 이동·입고·발주
- 발주: DRAFT→APPROVAL_PENDING→PLACED→SUPPLIER_CONFIRMED→PARTIALLY_RECEIVED→RECEIVED|CANCELLED.
- 입고: EXPECTED→ARRIVED→INSPECTING→PUTAWAY→COMPLETED|DISCREPANCY. 발주 1:N 입고 가능.
- 재고이동: REQUESTED→ALLOCATED→SHIPPED→IN_TRANSIT→RECEIVED→COMPLETED. 이동중 재고 자산을 잃지 않음.
- 사업자간 소유권 이전: 일반 이동과 별개 승인/매입·매출·세금/증빙/소유권 이전 거래.
- 재고실사: 범위/스냅샷→Blind Count→차이 검토→승인→조정원장.

## 9. 3PL API 장애/중복/역순
- 출고요청 타임아웃: 같은 출고번호로 외부 접수여부 조회 → 미접수 확인 시 멱등 재전송.
- 늦게 도착한 상태: Raw 이력 보관, 유효 상태전이와 외부 기준시각 확인, 임의 역행 금지.
- 출고요청 성공·송장 미수신: SLA 기준 업무함 생성.
- 외부 정상재고와 내부 상세 품질분류 불일치: 보완정보 보존 + 확인업무.
- 인증 만료: 계정만 중지/업무함; 다른 계정 정상 처리.

## 10. 표준 도메인 이벤트(내부)
OrderReceived, OrderNormalized, OrderItemMapped, GwpApplied, BomExpanded, StockReserved, StockAllocated, FulfillmentCreated, FulfillmentRequested, FulfillmentAccepted, PickingStarted, ShipmentDispatched, TrackingReceived, CancellationRequested, CancellationConfirmed, ReturnReceived, ReturnInspected, RefundConfirmed, ExchangeReplacementDispatched, StockConditionChanged, StockDisposed, SettlementImported, SettlementMatched, CostFinalized, PeriodClosed.

모든 이벤트에 `event_id, workspace_id, business_entity_id, aggregate_type/id, event_type, occurred_at, source_event_id, correlation_id, schema_version` 필드. 전달은 반복될 수 있으나 업무 적용은 멱등.

---

## 개정 이력(2026-11-21, 위임 결정 docs/11 §H)
- CHG-009: 예약은 항상 (소유 사업자, SKU, 거점) 재고 버킷 단위이며, `ALLOCATED`는 출고 지시와 결합된 상태로 정의한다.
- CHG-010: 반품 검수 판정 어휘는 docs/13 §5 기준 한 벌의 enum으로 통일한다: 정상 `SELLABLE` · 검수대기 `PENDING_INSPECTION` · 재포장 `REPACK` · 불량 `DEFECTIVE` · 파손 `DAMAGED` · 만료 `EXPIRED` · 폐기대기 `DISPOSAL_PENDING` · 미확정 `UNDETERMINED`.
- DLG-040: 미결제 예약 만료 72시간, 반품 검수 기한 입고 후 2영업일(설정값).
- DLG-113~115(2026-11-22): 주문 상태는 주문상품에서 파생(전부 취소 → CANCELLED, 구매확정 또는 배송완료 + 구매확정 기간 경과 + 열린 클레임 없음 → COMPLETED), 보류는 상태가 아닌 별도 표시. 출고 상태에 CANCEL_REQUESTED·CANCELLED, 배송 상태에 RETURNED_TO_SENDER·LABEL_CANCELLED·LOST 추가. 클레임은 클레임·회수·환불·재출고 4개 축으로 병렬 표현, 환불에 PARTIALLY_CONFIRMED·CANCELLED 추가.
