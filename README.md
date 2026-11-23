# Commerce Operations OS (가칭, OQ-001)

국내 복수 사업자·다수 판매계정을 위한 커머스 통합 운영관리 플랫폼. 기획 기준 문서는 `docs/00-PRD.md` 이하, 작업 원칙은 `CLAUDE.md`.

> 인수인계 패키지에 원본 README.md 가 포함되지 않아 이 파일을 새로 작성했다(REV-000). 원본을 확보하면 대조가 필요하다.

## 현재 상태 (2026-11-21)
| 단계 | 상태 |
|---|---|
| M0 설계 정리 | 완료 — 검토 결과 `docs/11` §E~G, 세분화 계획 `docs/10` §12, ADR `docs/adr/` |
| M1 Foundation 백엔드(F0~F6) | 구현·테스트 완료 — 마이그레이션, 핵심 API, 사용자별 접근정책, 감사·Outbox·작업·멱등성, 모의 연동 |
| M1 F9 인증 강화 | 완료 — 비밀번호 정책, 비활동 만료, 관리자 TOTP 2단계 인증·복구 코드, 인증정보 만료 경고 |
| M1 F8 RLS | 완료 — 비소유 DB 역할 + 운영그룹 격리 정책(기본 거부) |
| M1 F7 프론트엔드 | 완료 — Next.js 셸·로그인·설정·연동·업무함 화면, Playwright E2E |
| 화면 시안 | 76개 — `docs/design/` |
| M2 Catalog(C1~C6) | 완료 — 상품·옵션·SKU, 사업자별 정책·원가·승인, 채널상품 매핑, BOM, GWP, 일괄 가져오기 |
| M3 Inventory(I1~I6) | 완료 — 출고거점·화주계정, 재고 버킷·원장·ATP, 예약→할당→피킹→출고 확정, 외부(3PL) 재고 스냅샷·워터마크·보수적 판매 제한, 실사·재실사 기준·조정 승인·거점 이동, 재고 검증센터·출고거점 범위 |
| M4 Order/Claims/Fulfillment | 완료 — O1 주문 수집·중복/역순·귀속 스냅샷, O2 상품 연결·검증·보류, O3 사은품·묶음 구성 확정·개인정보 보관, O4 출고 배정·출고요청(자동 배정·멱등 전송·T11 사후조회), O5 출고 확정·송장·배송 추적·채널 발송처리, O6 취소·반품·교환·검수·환불·폐기, O7 자체 WMS 기본(웨이브·스캔 피킹·검수 포장·로케이션 이동). 고급 WMS 는 P1 |
| M5 Finance | 완료 — 단일 손익 원장(출고 매출·원가·예상 비용 자동 기록, 반품·교환·폐기 연동, 이중 손실 없음), 이동평균 원가, 예상→실제 대체, 재고 평가손실, 공헌이익·드릴다운, 정산 원본·대사(재업로드 중복 없음, 수수료 실제 대체), 입금 다대다 매칭(자동·직접·취소), 월 마감·마감 후 조정·재개방 승인, 입고 부대비용(취득원가·이중 차감 방지), 공통비 배부(근거·버전·재배부), 사업자 간 재고 이전(승인·내부거래·그룹 합계 제거) 완료 — M5 범위 완료 |
| M6 Integrations & Insights | 진행 중 — 운영 리포트(재고·판매·물류 KPI, 분모·계산불가 표시), 규칙 기반 인사이트 카드 6종(원인 분해·근거·미확인 표시·검토 업무·무시·자동 해결, 자동 실행 없음), 부가세 신고자료 표준화(표준 양식·검증·상호 배타 분류·보정·작성자≠확정자·대사·사업자 단위 내보내기), 발주·매입(공급처·MOQ·승인·부분 입고·실매입 원가·입고예정), 수동·B2B·샘플 주문, CS 통합함(문의 기록·응답기한·템플릿·채널 직접 등록 표시), 손익 시뮬레이션, 발주 제안, 상품 품질 점검, 추정 영업이익 완료. **실제 채널·3PL 커넥터는 실계정 권한 확인(docs/06 §7) 대기** |
| M7 운영 준비 | 1차 완료 — 운영 현황, 보관기간 정리, 백업·복구 리허설(CI), docker compose, 감사로그 파티셔닝 계획 |

남은 결정은 2026-11-21 사용자 위임에 따라 표준안으로 정했다(`docs/11` §H). 법률·세무 확인이 필요한 항목은 🔶로 표시했다.

외부 판매채널·3PL 은 **모의 커넥터만** 있다. 실제 연동은 계정별 권한·API 버전 확인 후 채널마다 추가한다(ADR-0003).

## 저장소 구조
```
CLAUDE.md, README.md
docs/            기획 문서(00~16), adr/, api/foundation-api.md, permission-matrix.md
migrations/      Flyway SQL (V1 identity·access, V2 organization, V3 platform, V4 integration·work items, V5 인증 강화, V6 RLS)
backend/         Java 21 / Spring Boot 4.1 모듈형 모놀리스
  └ com.commerceos.{identity, channel, logistics, integration, operations, platform, shared}
frontend/        Next.js 16 + TypeScript. 브라우저는 /bff/* 만 호출하고 토큰은 httpOnly 쿠키에 둔다
docs/design/     화면 시안(캔버스 파일) · tools/mockup-gen/ 시안 생성 스크립트
scripts/e2e.sh   E2E: PostgreSQL + 백엔드 + 프론트엔드를 띄우고 Playwright 실행
infra/           docker-compose(개발용 PostgreSQL)
```

## 실행
필요: Java 21, Maven 3.9, Docker.

```bash
docker compose -f infra/docker-compose.yml up -d
```

```bash
cd backend && mvn package -DskipTests
```

```bash
cp .env.example .env   # 값을 채운 뒤
set -a && . ./.env && set +a && java -jar backend/target/commerce-os-backend-0.1.0-SNAPSHOT.jar
```

`BOOTSTRAP_ENABLED=true` 이면 첫 기동 때 `BOOTSTRAP_WORKSPACE_CODE` 운영그룹과 최고관리자 계정을 만든다(개발 전용). 최고관리자·운영관리자는 첫 로그인 때 OTP 앱으로 2단계 인증을 등록해야 한다. 비밀번호는 2종 조합 10자 이상(또는 3종 8자 이상)이며 이메일 아이디를 넣을 수 없다.

프론트엔드(필요: Node 22):

```bash
cd frontend && npm ci && BACKEND_URL=http://localhost:8080 DEFAULT_WORKSPACE_CODE=demo npm run dev
```

API 직접 호출:

```bash
curl -X POST localhost:8080/api/v1/auth/sessions -H 'Content-Type: application/json' -d '{"workspace_code":"demo","email":"admin@example.com","password":"<BOOTSTRAP_OWNER_PASSWORD>"}'
```

## 테스트
실제 PostgreSQL(Testcontainers)로 실행한다. Docker 가 필요하다.

```bash
cd backend && mvn test
```

수용 테스트 대응: `docs/10` §12.9 표 참고. 테스트 클래스는 `backend/src/test/java/com/commerceos/foundation/`.

화면 E2E(Docker·Java·Node 필요, 처음 한 번 `npx playwright install chromium`):

```bash
scripts/e2e.sh
```
