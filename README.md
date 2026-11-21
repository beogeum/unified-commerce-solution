# Commerce Operations OS (가칭, OQ-001)

국내 복수 사업자·다수 판매계정을 위한 커머스 통합 운영관리 플랫폼. 기획 기준 문서는 `docs/00-PRD.md` 이하, 작업 원칙은 `CLAUDE.md`.

> 인수인계 패키지에 원본 README.md 가 포함되지 않아 이 파일을 새로 작성했다(REV-000). 원본을 확보하면 대조가 필요하다.

## 현재 상태 (2026-11-21)
| 단계 | 상태 |
|---|---|
| M0 설계 정리 | 완료 — 검토 결과 `docs/11` §E~G, 세분화 계획 `docs/10` §12, ADR `docs/adr/` |
| M1 Foundation 백엔드(F0~F6) | 구현·테스트 완료 — 마이그레이션, 핵심 API, 사용자별 접근정책, 감사·Outbox·작업·멱등성, 모의 연동 |
| M1 프론트엔드(F7), RLS(F8), F9 | 미착수 |
| M2 이후 | 미착수 |

외부 판매채널·3PL 은 **모의 커넥터만** 있다. 실제 연동은 계정별 권한·API 버전 확인 후 채널마다 추가한다(ADR-0003).

## 저장소 구조
```
CLAUDE.md, README.md
docs/            기획 문서(00~16), adr/, api/foundation-api.md, permission-matrix.md
migrations/      Flyway SQL (V1 identity·access, V2 organization, V3 platform, V4 integration·work items)
backend/         Java 21 / Spring Boot 4.1 모듈형 모놀리스
  └ com.commerceos.{identity, channel, logistics, integration, operations, platform, shared}
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

`BOOTSTRAP_ENABLED=true` 이면 첫 기동 때 `BOOTSTRAP_WORKSPACE_CODE` 운영그룹과 최고관리자 계정을 만든다(개발 전용). 로그인:

```bash
curl -X POST localhost:8080/api/v1/auth/sessions -H 'Content-Type: application/json' -d '{"workspace_code":"demo","email":"admin@example.com","password":"<BOOTSTRAP_OWNER_PASSWORD>"}'
```

## 테스트
실제 PostgreSQL(Testcontainers)로 실행한다. Docker 가 필요하다.

```bash
cd backend && mvn test
```

수용 테스트 대응: `docs/10` §12.9 표 참고. 테스트 클래스는 `backend/src/test/java/com/commerceos/foundation/`.
