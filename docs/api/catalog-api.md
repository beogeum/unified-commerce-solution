# 상품·SKU API (M2 C1)

공통 규칙(인증·오류 형식·If-Match·멱등키)은 `foundation-api.md` §1 과 같다. 상품·SKU·브랜드·카테고리는 **운영그룹 공통 마스터**라 사업자 범위 필터가 없다(CONF-004). 사업자별 원가·가격·재고정책은 C2 `/business-entities/{id}/sku-policies` 에서 다룬다.

| 영역 | 메서드·경로 | 권한 | 비고 |
|---|---|---|---|
| 브랜드 | `GET /brands?status=` | `catalog.read` | 상품 수 포함 |
| | `POST /brands` | `catalog.manage` | `{name}`. 이름 운영그룹 내 유일(대소문자·앞뒤 공백 무시) → `BRAND_DUPLICATE` |
| | `PATCH /brands/{id}` | `catalog.manage` | `If-Match`. `{name?, status?: ACTIVE/INACTIVE}` |
| 카테고리 | `GET /categories` | `catalog.read` | 계층 경로(`path`) 포함, 최대 10단계 |
| | `POST /categories` | `catalog.manage` | `{name, parent_id?}`. 같은 위치 같은 이름 금지 |
| 상품 | `GET /products?q=&status=&brand_id=&category_id=` | `catalog.read` | `q`는 상품명·내부관리명·상품코드·SKU 코드·바코드 |
| | `POST /products` | `catalog.manage` | 항상 `DRAFT`. `{product_code, name, brand_id?, category_id?, tax_type?, options?: [{name, values[]}], …}` |
| | `GET /products/{id}` | `catalog.read` | `{product, options[], skus[]}`, `ETag` |
| | `PATCH /products/{id}` | `catalog.manage` | `If-Match`. 상태 전이 표 아래. `product_code` 변경 → `PRODUCT_CODE_IMMUTABLE` |
| | `POST /products/{id}/options` | `catalog.manage` | SKU 가 있으면 새 옵션 축 추가 불가 → `PRODUCT_HAS_SKUS` |
| | `POST /products/{id}/options/{optionId}/values` | `catalog.manage` | `{value}`. 중복 → `PRODUCT_OPTION_VALUE_DUPLICATE` |
| SKU | `GET /skus?q=&sku_type=&status=&product_id=` | `catalog.read` | |
| | `POST /skus` | `catalog.manage` | `{product_id, sku_code, sku_type, sellable?, purchasable?, inventory_managed?, barcode?, weight_g?, …, lot_managed?, expiry_managed?, min_remaining_shelf_days?, option_value_ids[]}`. 응답 `warnings[]` |
| | `GET /skus/{id}` | `catalog.read` | `ETag`, `warnings[]` |
| | `PATCH /skus/{id}` | `catalog.manage` | `If-Match`. `sku_code`·`product_id` → `SKU_IDENTITY_IMMUTABLE`, `sku_type` → `SKU_TYPE_IMMUTABLE` |
| 내보내기 | `POST /exports {export_type: "SKUS"}` | `export.create` + `catalog.read` | 원가·가격 미포함 |

## 상품 상태
| 현재 | 바꿀 수 있는 상태 | 조건 |
|---|---|---|
| `DRAFT` 초안 | `ACTIVE`, `ARCHIVED` | `ACTIVE`: 브랜드·카테고리·과세유형 + 판매 가능(`sellable`) 운영 SKU 1개 이상. 아니면 422 `PUBLISH_REQUIREMENTS_NOT_MET` `details.missing` |
| `ACTIVE` 판매중 | `INACTIVE` | 바로 보관 불가 |
| `INACTIVE` 판매중지 | `ACTIVE`, `ARCHIVED` | |
| `ARCHIVED` 보관 | — | 수정 불가 |
판매중 상품의 마지막 판매 가능 SKU 를 중지하면 409 `PRODUCT_NEEDS_ACTIVE_SKU`.

## SKU 유형별 기본값·제약
| 유형 | 판매 | 구매 | 재고관리 | 제약 |
|---|---|---|---|---|
| `NORMAL` 일반 | ● | ● | ● | |
| `BUNDLE` 가상묶음 | ● | | | 재고관리 불가(구성품 재고로 계산, C4) |
| `KIT` 실물세트 | ● | ● | ● | 재고관리 필수 |
| `MATERIAL` 부자재 | | ● | ● | 판매 불가·재고관리 필수 |
| `GIFT` 증정품 / `SAMPLE` 샘플 | | ● | ● | |
| `SERVICE` / `DIGITAL` | ● | | | 재고관리 불가 |
기본값은 입력으로 바꿀 수 있지만 제약 위반은 422 `SKU_TYPE_FLAGS_INVALID`. LOT 관리는 재고관리 SKU 만, 유통기한 관리는 LOT 관리와 함께, 남은 유통기한 최소일은 유통기한 관리 SKU 만.

## 옵션 조합
- 옵션이 있는 상품의 SKU 는 옵션마다 값 하나(완전한 조합). 빠지면 `SKU_OPTION_INCOMPLETE`(`details.missing_options`), 다른 상품 값·같은 옵션 두 값이면 `SKU_OPTION_INVALID`.
- 같은 조합의 SKU 는 하나(보관 SKU 제외) → 409 `SKU_OPTION_COMBINATION_DUPLICATE`. DB 부분 유일 인덱스로 동시 요청도 보장.
- 바코드 중복은 **경고**(`warnings[].code = BARCODE_DUPLICATE`, 같은 바코드의 SKU 코드 목록)이며 저장은 된다.

# 사업자 SKU 정책·원가 (M2 C2)

금액은 **KRW 원 단위 정수**이고 JSON 에서는 **문자열**로 주고받는다(입력은 숫자·문자열 모두 허용, 소수점 있으면 422). 가격은 부가세 포함, 원가는 공급가(부가세 제외)다. 사업자 READ 범위 밖이면 404, 변경은 WRITE 범위.

| 영역 | 메서드·경로 | 권한 | 비고 |
|---|---|---|---|
| 정책 | `GET /business-entities/{id}/sku-policies?q=` | `catalog.read` | 설정된 SKU 만 |
| | `GET /business-entities/{id}/sku-policies/{skuId}` | `catalog.read` | 미설정이면 `configured:false` 와 SKU 기본값. `ETag` |
| | `PUT /business-entities/{id}/sku-policies/{skuId}` | `catalog.manage` + WRITE | 없으면 생성(If-Match 생략), 있으면 `If-Match` 필수. `{sell_enabled, purchase_enabled, base_price, min_price, safety_stock, note, min_price_reason}` |
| | `GET /skus/{skuId}/business-policies` | `catalog.read` | 조회 범위 안 활성 사업자별 정책(T04 비교) |
| 원가 | `GET /business-entities/{id}/sku-costs/{skuId}` | `cost.read` + READ | 이력(최신순), `current` 표시 |
| | `POST /business-entities/{id}/sku-costs/{skuId}` | `cost.manage` + WRITE | `{unit_cost, effective_from?, reason}`. 과거 시점 → `COST_BACKDATE_FORBIDDEN`, 가상묶음·서비스·디지털 → `SKU_COST_NOT_APPLICABLE` |

- 응답의 `base_price_supply`·`base_price_vat` 는 DLG-030(부가세 = 포함금액 ÷ 11 원 미만 절사)으로 계산한 값. 상품 과세유형이 없으면 null.
- `cost.read` 가 없으면 `cost_visible:false`, `current_cost:null`.
- 판매 불가 SKU 의 `sell_enabled:true` → `SKU_NOT_SELLABLE`. 기준 판매가 < 최저판매가 → `PRICE_BELOW_MIN_PRICE`(DB CHECK 도 강제).
- **최저판매가 변경은 즉시 반영되지 않고 승인 요청**(`SKU_MIN_PRICE_CHANGE`)이 된다. 응답 `pending_min_price` 에 요청 ID. 같은 대상의 대기 요청이 있으면 `APPROVAL_ALREADY_PENDING`.

# 승인 (공통)

| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /approvals?status=&mine=` | 로그인 | `approval.decide` 가 없으면 본인 요청만. 범위 밖 사업자 요청 제외 |
| `GET /approvals/{id}` | 요청자 또는 `approval.decide` | |
| `POST /approvals/{id}/decision` | `approval.decide` + 대상 사업자 WRITE | `{decision: APPROVE/REJECT, note}`. 본인 요청 → `SELF_APPROVAL_FORBIDDEN`(DB CHECK 도 강제), 처리된 요청 → `APPROVAL_NOT_PENDING`, 요청 이후 대상이 바뀜 → 요청을 `CANCELLED` 로 닫고 409 `APPROVAL_STALE` |
| `POST /approvals/{id}/cancel` | 요청자 | 대기 중일 때만 |
