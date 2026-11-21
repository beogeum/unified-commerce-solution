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

# 채널상품·매핑 (M2 C3)

채널상품 식별은 **판매단위 + 외부상품ID + 외부옵션ID**(옵션 없으면 빈 문자열). 같은 번호라도 판매계정·판매단위가 다르면 다른 채널상품이다. 권한은 `catalog.read`/`catalog.manage` + 판매계정·사업자 범위(READ/WRITE). 범위 밖은 404, 범위 밖 판매계정 필터는 403.

| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /channel-listings?mapping=UNMAPPED|MAPPED|REMAPPED&channel_account_id=&sales_unit_id=&business_entity_id=&q=` | `catalog.read` | `REMAPPED` = 이력상 서로 다른 SKU 가 2개 이상 |
| `POST /channel-listings` | `catalog.manage` | 직접 등록(`source=MANUAL`). 중복 → 409 `CHANNEL_LISTING_DUPLICATE`(`details.existing_id`) |
| `GET /channel-listings/{id}` | `catalog.read` | `{listing, history[], previous_listing_id}` |
| `GET /channel-listings/{id}/suggestions` | `catalog.read` | 바코드·외부 SKU 코드 일치 = `HIGH`, 상품명 단어 = `LOW`. `enabled_for_business` 포함 |
| `POST /channel-listings/{id}/mapping` | `catalog.manage` | `{sku_id, quantity?, match_basis?, reason?, confirm_remap?}`. 같은 연결은 멱등. 이력에 다른 SKU 가 있으면 409 `MAPPING_REMAP_CONFIRMATION_REQUIRED`(`details.previous_sku_codes`) → `confirm_remap:true` + `reason` 필수. 판매 불가·중지 SKU → `SKU_NOT_MAPPABLE`. 응답 `warnings[]`(`SKU_NOT_ENABLED_FOR_BUSINESS`) |
| `DELETE /channel-listings/{id}/mapping?reason=` | `catalog.manage` | 열린 매핑을 닫는다(이력 보존) |
| `POST /channel-listings/{id}/external-id-change` | `catalog.manage` | `{external_product_id, external_option_id, reason}`. 새 채널상품 생성, 기존은 `REPLACED`, 매핑을 `ID_CHANGE` 로 이관 |

- 매핑 이력은 수정·삭제 불가(열린 매핑 닫기만 허용, DB 트리거), 같은 채널상품의 매핑 기간은 겹칠 수 없다(DB 배제 제약), 열린 매핑은 하나(부분 유일 인덱스).
- `ChannelListingService.resolve(판매단위, 외부상품ID, 외부옵션ID, 시각)`: 그 시각에 유효했던 SKU·수량. M4 주문 수집이 사용한다.
- 미연결 채널상품은 판매단위별 업무(`CHANNEL_LISTING_UNMAPPED`) 1건으로 모이고 등록될 때마다 발생 횟수가 늘며, 모두 연결되면 자동으로 완료된다. 연결 해제로 다시 생기면 새로 열린다.

# BOM (M2 C4)

| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /boms?parent_sku_id=&bom_type=&status=&packaging_profile_code=` | `catalog.read` | |
| `POST /boms` | `catalog.manage` | 초안 생성. `{bom_type, parent_sku_id | packaging_profile_code, components:[{sku_id, quantity}], split_shipment_allowed?, note?}`. 버전 자동(대상·유형별 +1) |
| `GET /boms/{id}` | `catalog.read` | `ETag` = `version_lock` |
| `PUT /boms/{id}/components` | `catalog.manage` | 초안만. `If-Match` |
| `POST /boms/{id}/publish` | `catalog.manage` | `{valid_from?}`(기본 지금, 과거 불가 `BOM_BACKDATE_FORBIDDEN`). 열린 이전 게시 버전을 새 시작 시각에 닫음. 기간 겹침 `BOM_PERIOD_OVERLAP` |
| `POST /boms/{id}/retire` | `catalog.manage` | `{reason}`. 지금 시각으로 종료 |
| `DELETE /boms/{id}` | `catalog.manage` | 초안만 |
| `GET /skus/{skuId}/boms/effective?bom_type=&at=` | `catalog.read` | 그 시각의 게시 버전(M4 주문 스냅샷용). 없으면 404 |
| `GET /boms/{id}/cost?business_entity_id=` | `cost.read` + READ | 구성품 현재 원가 × 수량. 원가 없는 구성품이 있으면 `total_cost:null` + `missing_cost_sku_codes` |

| 유형 | 상위 | 허용 구성품 |
|---|---|---|
| `SALES_BUNDLE` 판매묶음(가상) | 가상묶음 | 일반·실물세트·증정품·샘플·가상묶음·서비스·디지털 |
| `PROMOTION` 프로모션 | 가상묶음 | 일반·실물세트·증정품·샘플·가상묶음 |
| `ASSEMBLY` 조립 | 실물세트 | 일반·증정품·샘플·부자재·실물세트 |
| `MANUFACTURING` 제조 | 일반·실물세트 | 일반·부자재 |
| `PACKAGING` 포장 | 포장 프로필 코드 | 부자재 |

- 자기참조 `BOM_SELF_REFERENCE`, 순환 `BOM_CYCLE`(`details.cycle` 경로). 순환은 모든 버전(초안 포함, 포장 제외)의 구성 관계로 판단하고 운영그룹 단위 잠금으로 동시 요청을 직렬화한다.
- 게시본은 구성·대상·버전·시작 시각을 바꿀 수 없다(DB 트리거). 같은 대상·유형의 게시 버전 기간은 겹칠 수 없다(DB 배제 제약).
- 가상묶음 판매가능 수량은 `BundleAvailability`(분할출고 불가: 거점별 MIN(floor(부품 가용÷소요량)) 합, 허용: 허용 거점 합계 기준)로 계산하며 M3 재고에서 값을 넣는다.
