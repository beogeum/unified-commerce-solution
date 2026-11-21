# Claude Code 구현 로드맵과 인수인계 절차

## 0. 프로젝트 저장소 기본 구조
```
repo/
├─ CLAUDE.md
├─ README.md
├─ docs/
├─ backend/              # Java/Spring Boot modular monolith
│  ├─ identity/
│  ├─ catalog/
│  ├─ integration/
│  ├─ order/
│  ├─ claim/
│  ├─ inventory/
│  ├─ fulfillment/
│  ├─ procurement/
│  ├─ finance/
│  └─ operations/
├─ frontend/             # Next.js / React / TypeScript
├─ migrations/           # DB 마이그레이션(Flyway 등)
├─ contracts/            # OpenAPI/연동 DTO/원본 fixture
├─ infra/                # docker-compose 등
└─ tests/                # 통합/UAT/E2E
```
현재 이 인수인계 ZIP은 `backend/frontend` 소스코드가 아니라 **문서 패키지**임. Claude Code가 계획 검토 후 생성할 것.

## 1. M0 — 착수 전 설계 정리
산출물: 스택 ADR, 도메인 Boundaries, 사용자별 권한 매트릭스, 사업자/계정/판매단위 식별 체계, 원장/이벤트 계약, 테스트 데이터, 외부 API 검증 Backlog.
게이트: `docs/11`의 P0 미확정 정책이 구현에 미치는 영향 정리; 외부 API 미확정은 모의 연동으로 격리.

## 2. M1 — Foundation
- 운영그룹/사업자/사용자/역할/명시 Deny/판매계정·판매단위·연동 연결정보.
- 계정/사업자 필터가 UI·API·Export에서 동일하게 작동.
- 공통 Layout/KPI/Table/Filter/Drawer/Modal/Saved View/비동기 Jobs/통합 검색 기본.
- 감사로그, Outbox, 파일 업로드·원본 보존, 권한/에러 표준.
- 테스트: T01-05, T22-25, T21(인증 장애 격리 mock).

## 3. M2 — Catalog/SKU/BOM/GWP
- 상품·옵션·SKU/사업자별 운영정책·외부 상품 매핑/가격/원가 Snapshot.
- 가상묶음·실물 KIT·포장 BOM·증정/중복/반품 정책.
- 상품정보 품질/기본 Excel import.
- 테스트: T04, T12-13, SKU 매핑 이력, BOM 순환 및 동시부품 확보.

## 4. M3 — Inventory
- 재고 소유사업자/거점·LOT/품질/보류/원장/예약 단계와 ATP.
- 동시예약/실사·조정·재고이동/외부 재고 Snapshot.
- 재고 검증센터 및 업무함.
- 테스트: T05-09, T38, T40.

## 5. M4 — Order/Claims/Fulfillment
- Raw 주문 표준화, 분할출고, 배송/SLA, 반품·취소·교환, 환불/검수, WMS 기본.
- 3PL 인터페이스 + Mock + 한 개 실제 연동(승인된 대상에 한해).
- 테스트: T10-20, T37, T39.

## 6. M5 — Finance and Profit
- 매출·매출조정, 공급자 매입원가, 수수료/PG/배송/광고·GWP, 예상/확정 비용 대사.
- 정산/입금 다대다 매칭, 클레임 원가/평가손실, 사업자·계정별 공헌이익, 월 마감.
- 테스트: T26-36.

## 7. M6 — Multiple Integrations and Insights
- 채널별 API 인증/실제 권한 확인, 채널·물류 연동 확대.
- 상품·광고·물류·재고 성과, 이상 탐지/추천/시뮬레이션(초기 승인 후 실행).
- 세무 자료 표준화·사업자별 내보내기.

## 8. PR 단위 개발규칙
1. 파일 `templates/feature-spec.md`를 복사해 사용자 시나리오·도메인 불변조건/권한/외부API 지원 범위 정의.
2. 변경할 DB 마이그레이션, DTO/API, UI, 정합성 테스트 목록부터 작성.
3. 테스트 작성 → 도메인/트랜잭션 구현 → API/권한 → UI/알림/감사 → E2E → 문서 업데이트.
4. DB 스키마가 변경되면 기존 데이터 마이그레이션/롤백/재처리 검증.
5. 외부 API 실제 호출 전에 해당 채널의 최신 공개 문서/계정별 권한/UAT 확인.
6. 기능을 한꺼번에 다 구현하라고 하지 말고 작은 PR로 계속 축적.

## 9. Definition of Done
- 성공/실패/중복/동시성/권한(특히 사업자 제한)/외부 지연/재처리 테스트 통과.
- 한국어 Empty/Loading/Error/권한없음, Drawer, 검색/필터/일괄작업 중 범위에 맞는 UI 완성.
- API 요청/응답·변경이력·원장·사용자별 접근정책·성능 및 모니터링.
- PR 설명에 변경된 정책과 관련 FR-ID, 수용 테스트 ID 기록.
- 정산·손익의 원본금액/추정금액 구분, 중복 차감 검증.
- 실 API 제공자의 계정 접근/기능 확인 안 된 부분은 구현 완료라 주장하지 않음.

## 10. Claude Code에게 붙여넣을 시작 프롬프트
```
이 저장소는 국내 다사업자·복수 판매계정의 커머스 통합 운영관리 플랫폼입니다.
우선 README.md와 CLAUDE.md, docs/00-PRD.md, docs/03-domain-and-ERD.md,
docs/04-workflows-states.md, docs/05-finance-rules.md, docs/09-acceptance-tests.md,
docs/11-decisions-and-open-questions.md를 읽으세요.

아직 코드를 수정하지 말고 먼저:
1. 문서 상충, 데이터 무결성 위험, 빠진 핵심 도메인, API 미검증 항목 분석
2. 모듈형 모놀리스의 첫 4개 PR 설계안(각 PR의 DB/API/UI/테스트/의존성)
3. 스택 확정 ADR 초안
4. P0 불변조건을 자동 검증할 테스트 계획
을 Markdown으로 작성하세요.

특히 동일 운영그룹 내 다른 사업자 주문은 기본 조회 가능하지만
사용자별 명시 제한이 우선하며, 사업자간 재고 소유권과 정산은 분리됩니다.
예약/할당/피킹 및 외부 3PL 재고는 중복 차감하지 않습니다.
외부 API 실연동은 판매계정별 승인/권한/버전 확인 전까지 모의 연동으로 처리합니다.
```

## 11. 후속 개발 프롬프트(계획 합의 후)
```
M1의 첫 PR을 구현하세요. 범위는 워크스페이스/사업자/판매계정/판매단위와
기본 읽기 범위/사용자 명시 제한, PostgreSQL migration 및 JUnit/Testcontainers 테스트입니다.
관련 PRD 요구사항 FR-ORG-01~07과 수용 테스트 T01~T05, T22~T25를 참조하세요.
다른 모듈은 인터페이스/스텁만 만들고 실제 연동은 구현하지 마세요.
완료 후 API 계약, 마이그레이션, 테스트 결과, 남은 OPEN-QUESTION을 보고하세요.
```
