# 판매채널·물류사 연동 전략 및 검증표

**조사 기준일: 2026-11-21.** 공개 문서가 존재한다는 뜻이지 **우리 솔루션의 사용 권한이나 모든 API 기능이 승인됐다는 뜻은 아니다.** 각 채널별 실제 계정·제휴 등록 후 계약/스펙/요청·응답 확인이 필요하다.

## 1. 연동 공통 기준
- 판매자(사업자) → 판매채널 계정 → 판매단위 → 연동 연결(복수 가능) 구분.
- 연결별 `API 버전, 인증/만료, 실제 권한, 개발사 승인/대행사 등록, API rate limit, 사용 가능 기능, 마지막 테스트 일시` 관리.
- 지원 기능은 `공식 제공(문서 확인)` / `권한·제휴 필요` / `일부` / `파일` / `수동` / `미확인` / `미지원`로 분리.
- 각 기능별 READ/WRITE/DELETE(또는 취소) 분리. 클레임 조회와 승인·환불 실행은 별개.
- 외부 식별자·상태·수량·금액의 **원본+변환 버전** 보관; 외부 정책 위반 가능할 때 명령 비활성화.
- 통합계정 로그인정보를 임의 수집하지 않고 공식 API/키/승인 절차를 사용.

## 2. 채널 목록과 연동 근거(공식 우선)
| 채널 | 분류/현재 판단 | 공개문서로 확인한 사항 | 개발 전 꼭 검증할 것 |
|---|---|---|---|
| 카페24 | 공식 Admin API | 상품/주문 Admin API, `mall_id`, 멀티숍 `shop_no` | 설치/OAuth 범위, 멀티숍/추가 계정, 상품·클레임 세부 |
| 네이버 스마트스토어 | 공식 Commerce API | 상품, 주문/발송, 정산, 문의, N배송/솔루션 연동 문서 | 판매자 인증, 주문/정산 필드 버전, 물류 대행 권한 |
| 쿠팡 마켓플레이스 | 공식 API | 상품/주문/클레임/정산 개발자센터 | 판매자키, Seller/Vendor ID, 옵션 ID 변경, 호출 제한 |
| 쿠팡 로켓그로스 | 공식 전용 API 일부 | 로켓그로스 주문·로켓창고 주문가능 재고 공식 문서 | 마켓플레이스와 독립 거래/재고 흐름, 실제 접근 권한, 상품 등록 범위 |
| 쿠팡 직매입/서플라이허브 | 별도 B2B | 공개 Marketplace/RG와 동일 연동으로 보장 불가 | 공급사 계약·B2B 서류·주문/납품/세금 방식 확인 |
| G마켓 | 공식 ESM API | 주문조회 및 ESM API 포털 | 계정 승인, 상품/클레임/정산 필드별 지원 |
| 옥션 | 공식 ESM API | ESM 포털 내 옥션 주문 API | G마켓과 채널·주문/수수료 구분, 계정 권한 |
| 11번가 | API 연동 사례/센터(기능별 재확인) | 기존 통합솔루션 연동 전제 | 실제 API 스펙/신청/상품·정산 지원범위 |
| 롯데ON | 공식 Open API | 상품, 주문/배송, 교환·반품, CS, 정산, 물류 안내 | 셀링툴사 계약/인증/채널별 예외 |
| SSG.COM | 제휴형 EAPI | EAPI 문서/파트너 방식 | 최신 상품 API 버전, 입점/인증, 기능별 사양 |
| 카카오톡스토어 | 공식 Kakao Shopping API | 상품/주문/클레임/정산 등 API 문서 | API KEY 발급, 대행사 등록, 테스트 정책 |
| 카카오 선물하기 | 공식 API + 추가 권한 | 동일 쇼핑 API에서 채널 구분, 별도 권한 요청 | **선물하기 API 권한 승인**, 정산 주기, 선물/배송 상태 |
| 토스쇼핑 | 공식 API | 상품/주문/클레임/배송/정산 영역, 취소 승인 문서 존재 | API 버전별 클레임 처리 실제 호출·권한·문서 충돌 검증 |
| 지그재그 | 공식 GraphQL Open API | 상품/주문 및 Access/Secret 키 인증 문서 | 클레임·CS·정산 등 각 기능별 범위 |
| 무신사 | 대행사/제휴형 | API 키/통합솔루션 연동 사례 | 솔루션사 등록 자격/유효기간/전체 문서 제공 |
| 에이블리 | 입점형 제휴/API token 사례 | 셀러스 입점형 외부 솔루션 연동 사례 | Sellers/Partners 유형, 판매자·솔루션사 API 권한 |
| 29CM | 외부연동 API 문서 일부 공개 | 상품/카테고리 외부연동 문서 | 주문/배송/클레임/정산 및 파트너키 |
| W컨셉 | 제휴형 API 사례 | 파트너센터 연동 요청 및 통합솔루션 사례 | 브랜드 다중 관리, 반품/교환 수집 가능 여부 |
| 오늘의집 | 솔루션 연동 사례 | 제휴형/운영 솔루션 사례 | 상품·주문·재고·CS별 실제 문서/계약 |
| 퀸잇 | 셀링툴 제휴 사례 | 외부 솔루션 연동 사례 | 직접 API 계약 가능 여부/기능명세 |
| 컬리 | 검증 필요 | 공식 판매자 API 범위 미확정 | 입점계약·주문/정산 파일/EDI |
| 올리브영 | 검증 필요 | 외부 판매자/공급사 연동 범위 미확정 | 직매입/입점 거래 유형 및 시스템 접근 |
| 알리익스프레스 | 후속·검증 필요 | 글로벌/국내 셀러 정책 구분 필요 | 한국 판매자 API·연동사 승인·정산 |
| Temu | 후속·제휴형 | 파트너 플랫폼 존재(한국 셀러 권한은 별도) | 로컬 셀러/파트너 서비스 API 권한·기능 |

## 3. 주요 공식 자료 URL(개발 시 다시 확인)
- 카페24 Admin API: https://developers.cafe24.com/app/front/app/develop/api/adminapi
- 카페24 mall_id/shop_no 예시: https://developers.cafe24.com/app/front/app/refer/adminapisamplecode
- 네이버 커머스API: https://apicenter.commerce.naver.com/docs/commerce-api/current
- 네이버 수수료 상세: https://apicenter.commerce.naver.com/docs/commerce-api/current/find-commission-details-pay-settle
- 쿠팡 개발자센터: https://developers.coupang.com/ko/api?market=KR
- 쿠팡 로켓그로스 주문: https://developers.coupang.com/ko/api/rocket-growth/api
- 쿠팡 로켓창고 재고: https://developers.coupang.com/ko/api/rocket-growth/rg-inventory-api
- G마켓/옥션 ESM: https://etapi.gmarket.com/
- 롯데ON API: https://api.lotteon.com/apiGuide
- 카카오쇼핑 API: https://shopping-developers.kakao.com/hc/ko/categories/4406596840975-API-Docs
- 카카오 인증/선물하기 권한: https://shopping-developers.kakao.com/hc/ko/articles/4578909656975-API-%EA%B3%B5%ED%86%B5-%EC%9D%B8%EC%A6%9D-%ED%97%A4%EB%8D%94-%EA%B5%AC%EC%84%B1
- 카카오 테스트 환경: https://shopping-developers.kakao.com/hc/ko/articles/4578925569167-API-%ED%85%8C%EC%8A%A4%ED%8A%B8
- 토스쇼핑 API: https://shopping-docs.toss.im/dev/api-intro
- 토스쇼핑 클레임: https://shopping-docs.toss.im/dev/api-2/claim
- 지그재그 API: https://zigzag.kr/_openapi/docs/
- 지그재그 인증: https://zigzag.kr/_openapi/docs/authorization/
- 29CM 외부연동: https://partner-stage.one.musinsa.com/29cm_category_v2_api.html
- 에이블리 셀러스 연동 사례(서드파티): https://www.plto.com/customer/HelpDesc/gmp/14462/
- W컨셉 연동 사례(서드파티): https://help-sellmate.notion.site/W-API-10c26f180a7780b59ac7f70fb411ce5e

## 4. 3PL·풀필먼트 후보
- 자체 WMS/자체창고, 파스토, 품고, CJ 물류 파트너, 아워박스, 테크타카/아르고, 기타 일반 3PL, 네이버 N배송 제휴 물류, 쿠팡 로켓그로스(플랫폼 물류).
- 물류사업자마다 API 사용·반품검수·입고·재고조정·물류비 자동 조회 가능 여부가 다르다. **제공사가 공개 API/제휴 문서로 허용한 것만 활성화**.
- 품고 시점 재고 API에는 같은 상품이라도 로케이션/LOT에 따라 행이 나뉘며 수량 필드의 의미를 변환해야 함: https://guide.poomgo.com/2b18aea0-7620-81d7-841e-ddb214ae4d77
- 파스토 Open API는 공개 가이드상 반품 검수 세부내역을 API로 제공하지 않는다고 명시: https://guide-kr.fassto.ai/linkage/open_api
- 파스토에는 운영 UI로 세부 반품 검수 상태를 확인하는 안내가 있지만 **UI 존재가 Open API 제공을 뜻하지 않음**.

## 5. 물류사별 표준 지원 기능(실제 계정별 체크)
재고 조회 / 재고 원본 의미 / 입고요청 / 입고상태 / 출고요청 / 출고취소 / 출고상태 / 송장 / 반품요청 / 반품검수 결과 / 물류비/청구 / 재고조정 / LOT·유통기한 / 파일출력·수신 / Webhook.

## 6. 연동 개발 계약(모듈 interface)
- `getCapabilities(connectionId)` : 기능별 읽기·쓰기/실험/권한 상태.
- `fetchOrders(cursor, window)`, `fetchProducts()`, `fetchInventory()` : 원본과 커서·기준시각 포함.
- `requestFulfillment(command, idempotencyKey)`, `checkFulfillment(externalRef)` : 중복/타임아웃 방지.
- `fetchTracking()`, `fetchClaims()`, `processClaimAction()` : 지원 기능 없으면 명시적 미지원.
- `fetchSettlement(period)`, `fetchLogisticsBilling(period)` : 주문/배송비/수수료 항목 분리.
- `parseInboundFile(templateVersion)` : 미지원 API를 파일/CSV/SFTP 연동으로 보완.
- 응답은 `source_system, account_id, raw_payload_ref, external_id, mapped_object, mapping_version, source_timestamp, fetched_at, capability_version` 동반.

## 7. 필수 실제 검증 체크리스트
1. 사용자 판매자 계정에서 API 인증 발급/갱신이 되는가?
2. 해당 개발사가 대행사/솔루션사로 등록·승인 가능한가?
3. 조회와 쓰기(취소승인/반품승인/재고전송) 권한이 각각 있는가?
4. 다사업자·다계정 연결이 인증·호출제한상 허용되는가?
5. 샌드박스 유무(예: 카카오 공식문서상 별도 일반 테스트 환경 없음)와 안전 테스트 계정 유무?
6. 주문/클레임/정산의 식별키 안정성, 데이터 제공 시점, 조회 기간 제한?
7. 3PL 재고가 `on_hand`, `available`, `post_outbound_estimate` 중 무엇인가?
8. API 버전/지원종료 공지/요율·필드 변경을 어떻게 탐지할 것인가?
9. 법률·약관상 고객 개인정보 목적외 저장·사용 금지 사항은 없는가?
10. 각 기능의 실제 요청/응답 Sample을 테스트 계정으로 캡처해 계약 테스트를 작성했는가?
