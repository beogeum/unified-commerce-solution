# B2B 거래처·외상 API (DLG-226~233)

모든 경로는 `/api/v1`. 금액은 원 단위 정수 문자열(부가세 포함).

## 1. 거래처·단가표
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /b2b-customers?business_entity_id&q&status` · `GET /b2b-customers/{id}` | `order.read` + 사업자 범위 | 담당자는 마스킹 |
| `POST /b2b-customers {business_entity_id, code, name, business_registration_no?, contact_name?, contact_phone?, credit_limit?, payment_terms_days?, memo?}` | `customer.manage` (한도·기한은 `receivable.manage`) | `CUSTOMER_DUPLICATE` |
| `PATCH /b2b-customers/{id}` (+ If-Match) `{name?, status?, hold_reason?, credit_limit?, payment_terms_days?, contact_*?}` | 〃 | ON_HOLD 는 사유 필수 |
| `POST /b2b-customers/{id}/contact/reveal` | `pii.read` | 열람 감사로그 |
| `GET /b2b-customers/{id}/prices` · `POST /b2b-customers/{id}/prices {prices[{sku_id, unit_price, effective_from?}]}` | `order.read` / `customer.manage` | 적용일별 추가 전용, `PRICE_DUPLICATE` |

## 2. B2B 주문
`POST /orders/manual` 에 `kind: "B2B"`, `customer_id`, `payment_terms: "PREPAID" | "CREDIT"`. 단가 생략 시 단가표. 외상이면 `CREDIT_LIMIT_EXCEEDED`·`CREDIT_OVERDUE`·`CREDIT_NOT_ALLOWED`, 보류·중지 거래처는 `CUSTOMER_NOT_ACTIVE`.

## 3. 외상
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /receivables?business_entity_id&overdue_only` | `settlement.read` | 거래처별 잔액·연체·사용 중·남은 한도·가장 오래된 기한 |
| `GET /b2b-customers/{id}/receivables` | 〃 | 잔액·연체·미출고 외상 주문·연령·미결 청구·원장 |
| `POST /b2b-customers/{id}/payments {amount, received_on?, method?, reference?, memo?}` + `Idempotency-Key` | `receivable.manage` | 같은 키 = 같은 기록 |
| `POST /receivable-entries/{id}/reverse {reason}` | 〃 | 수금만, 한 번만(`ENTRY_ALREADY_REVERSED`) |
| `POST /b2b-customers/{id}/write-offs {amount, reason}` | 〃 | 202 승인 요청 `RECEIVABLE_WRITE_OFF`, 승인자도 `receivable.manage` |

청구(CHARGE)·반품 차감(CREDIT_NOTE)은 출고·클레임 완료 사건에서 자동 기록(API 없음).

## 4. 수동·B2B 주문 파일 일괄 등록 (DLG-234~238)
일괄 가져오기 API(`POST /files` purpose `GENERIC_IMPORT` → `POST /imports {file_id, import_type: "MANUAL_ORDER"}` → `GET /imports/{id}/rows` → `POST /imports/{id}/execute {confirm_rows}` + If-Match)를 그대로 쓴다. 양식: `GET /imports/templates/MANUAL_ORDER/file`.

| 열 | 필수 | 비고 |
|---|---|---|
| 주문 묶음 키 | ✓ | 같은 판매계정 + 키 = 주문 하나, 한 번만 등록 |
| 판매채널 코드 · 판매계정 외부키 | ✓ | 판매계정 쓰기 범위 |
| 주문 종류 | | MANUAL(기본)·B2B·SAMPLE |
| 거래처 코드 · 거래처명 · 결제 조건 | | 거래처 코드를 쓰면 결제 조건 PREPAID/CREDIT 필수 |
| 받는 분 · 연락처 · 주소 (우편번호·상세 주소·배송 메모) | ✓ | **민감 필드**: 미리보기 마스킹, 암호화 보관, 처리 후 삭제 |
| 배송비 · 주문 메모 | | 묶음 안 같아야 함 |
| SKU 코드 · 수량 · 단가(부가세 포함) | ✓(단가 제외) | 단가 비우면 거래처 단가표 |

처리 후 원본 파일 본문은 삭제(`FILE_PURGED`), 7일 미실행 배치는 자동 취소. 템플릿 목록 `GET /imports/templates` 에 `required_permission`, 필드에 `sensitive`.
