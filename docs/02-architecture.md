# 기술 구조 및 설계 결정

## 1. 권장 초기 기술 스택 (확정 전 기본안)
- **Backend**: Java 21 LTS + 구현 시점에 지원되는 Spring Boot 릴리스, Spring Security, Spring Data/JPA + 중요한 원장/잠금 SQL은 명시적으로 관리.
- **Frontend**: React + TypeScript + 지원 중인 Next.js 릴리스; 한국어 관리자 UI, 모바일/PDA 분리 뷰.
- **DB**: PostgreSQL (OLTP/원장), 스키마 버전 마이그레이션(Flyway 등).
- **Cache/운영**: Redis 선택 사용(세션/캐시/호출제한). **재고 원장이나 결제 이벤트의 단일 진실원으로 사용하지 않음**.
- **비동기 작업**: DB 트랜잭션 Outbox + 작업자; 규모와 운영 요구에 따라 큐 브로커(RabbitMQ 등) 도입 검토.
- **파일**: 객체 저장소(S3 호환), 파일 바이러스 검사, 접근 제어, 원본 해시 보관.
- **Test**: JUnit 5, Testcontainers(PostgreSQL), API 통합, 계약 테스트, Playwright UI E2E.
- **Infra**: dev Docker Compose, 운영 환경은 컨테이너 배포 + 모니터링·시크릿 관리.

**이유:** 제품 담당 조직의 Java/Spring 경험과 주문·재고·금액의 트랜잭션 일관성을 고려. 프론트/API 분리는 유지하되 초기부터 마이크로서비스를 만들지 않는다. 버전은 개발 시작 시 최신 LTS·지원정책 확인 후 ADR로 고정한다.

## 2. 모듈형 모놀리스(논리 경계)
```
Identity & Access       운영그룹/사업자/사용자/정책
Catalog & Pricing       상품/옵션/SKU/BOM/가격/GWP
Channel Integration     판매계정/채널상품/Raw 주문/변환
Orders                  주문/주문상품/금액 스냅샷
Claims & CS             문의/취소/교환/반품/환불
Inventory               재고 소유권/LOT/원장/예약/실사/평가
Fulfillment & WMS       출고 배정/출고/송장/창고작업
Logistics Integration   3PL/화주/원본 재고/파일/요율
Procurement             공급처/발주/입고/매입원가
Finance                 매출조정/정산/입금/비용/마감
Profit & Analytics      손익/배부/집계/인사이트
Operations              업무함/검증/알림/감사/승인
```
모듈은 공개된 서비스/도메인 이벤트로 협력한다. 다른 모듈 테이블을 임의 UPDATE하지 않는다.

## 3. 이벤트·작업 처리
- 거래 데이터와 outbox 메시지를 같은 DB 트랜잭션에 쓰고 커밋 후 전송.
- 작업자 전달은 at-least-once를 전제로 멱등 처리. 외부 출고·환불 등은 **사전 조회/거래키 확인** 후 재시도.
- 큐/작업: `PENDING → RUNNING → SUCCEEDED|RETRY_WAIT|REVIEW_REQUIRED|FAILED`.
- 외부 리턴 지연/중복/역순을 허용. 표준 상태로 변환 시 전이 검증 + 이상 이벤트 보존.
- 계정별 Rate Limit/토큰/백오프/차단기(circuit breaker). 특정 계정 장애를 다른 계정으로 전파하지 않음.
- API 원문/응답/파일/연동 규칙 버전/커서/동기화 워터마크 보존.

## 4. 다중 사업자·보안 아키텍처
- `workspace_id`로 조직 간 논리·API 격리. 모든 핵심 조회/업데이트 서버에서 스코프 확인.
- 기본값은 워크스페이스 구성원 모두의 운영 주문 조회 가능; 명시 제한 적용 시 해당 사업자/판매계정 전체 API와 데이터 내보내기에서 제외.
- PII/원가/정산/손익/승인·일괄작업은 별도 권한. 사용자 역할 + 명시 Deny + 행 수준 범위.
- DB 계층에 FK/유일성/체크 제약. 중요 테이블은 서비스 스코프 강제와 필요 시 PostgreSQL RLS를 **방어적 보조수단**으로 검토.
- 물류사 화주계정과 재고 소유자 및 판매자 다를 수 있음; 기본 배정은 동일 소유자만.
- 과거 주문의 사업자/판매계정 귀속은 당시 Snapshot 고정.

## 5. 정합성 아키텍처
- 재고: append-only transaction + canonical stock bucket + 단일 reservation lifecycle. 상태전이는 같은 트랜잭션.
- 3PL: 외부 quantity semantic(총재고/가용/출고후예상/품질)과 반영 워터마크를 알고 내부 미반영 확정예약만 차감.
- 회계: 운영 매출 이벤트/환불/원가·비용·재고평가 원장 각각 별도; 손익은 유효한 금액 항목 합산.
- 스냅샷은 조회/기간 마감 최적화, 원본 원장을 대체하지 않음. 재계산 가능성을 보장.
- 계정별 API 지원 기능을 런타임 Capability로 관리; UI에서 가능한 액션만 활성화.

## 6. 읽기 모델/성능
- 원장과 원본은 정확성과 감사 우선. 목록/대시보드/손익 집계는 읽기 모델 또는 집계 테이블.
- 큰 테이블은 `workspace_id, business_entity_id, sales_unit_id, created_at, status` 중심 인덱스.
- 목록은 커서 페이지네이션, 백그라운드 Export. 원본/PII 다운로드는 감사 추적.
- 정산/손익 집계에 지연 시각과 최종 확정일을 표시.

## 7. 비기능 확인 필요
- 고객사별 트랜잭션량/최대 SKU/판매계정 수, P95 SLA, 백업 RPO/RTO, 데이터 보관기간, API Rate Limit 실제 수치, 웹훅 제공범위는 아직 확정되지 않음.
- 로그에 전화번호, 상세주소, API 토큰, 카드정보 등 원문을 남기지 않도록 구조화/마스킹. 저장된 Raw 주문도 암호화·보관 정책 수립.
