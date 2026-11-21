# ADR-0001 기술 스택 확정(Foundation)

- 상태: **승인됨(2026-11-21 위임 결정 DLG-001)**
- 날짜: 2026-11-21
- 관련: docs/02 §1, ARCH-001, docs/16 개발환경

## 결정
| 영역 | 선택 | 버전(2026-11-21 기준) | 비고 |
|---|---|---|---|
| 언어 | Java | 21 LTS | ARCH-001 그대로 |
| 프레임워크 | Spring Boot | **4.1.3** | 4.2.0 은 2026-11-19 출시 직후라 안정 패치 라인 4.1.x 선택. 4.2 전환은 별도 PR |
| 보안 | Spring Security | Boot BOM | 무상태 Bearer 토큰 |
| DB | PostgreSQL | 16 | Testcontainers 이미지 `postgres:16-alpine` |
| 마이그레이션 | Flyway | Boot BOM | 파일 위치는 저장소 최상위 `migrations/`(docs/10 §0) |
| 데이터 접근 | Spring JDBC `JdbcClient` | Boot BOM | ADR-0004 |
| JSON | Jackson 3 | Boot BOM | snake_case, 시간 ISO-8601 +09:00 |
| 테스트 | JUnit 5, Testcontainers 2, MockMvc, ArchUnit | | 실제 PostgreSQL 로 동시성·제약 검증 |
| 빌드 | Maven | 3.9 | |
| 프론트엔드 | Next.js + React + TypeScript | F7 PR 에서 확정 | 이번 PR 범위 밖 |
| 비동기 | DB Outbox + 폴링 작업자(SKIP LOCKED) | | 브로커(RabbitMQ 등)는 규모 확인 후(docs/02 §1) |
| Redis | 미도입 | | 캐시 필요가 생기면 도입. 원장·결제 진실원으로 쓰지 않음 |

## 구조
단일 Maven 모듈 안에서 패키지로 모듈 경계를 나눈다(`identity`, `channel`, `logistics`, `integration`, `operations`, `platform`, `shared`). docs/10 §0 의 `backend/identity/...` 디렉터리 구조와 같은 경계를 패키지로 표현했다. 경계 규칙 일부는 `ArchitectureTest`(ArchUnit)로 강제한다. 모듈 수가 늘어 빌드 분리가 필요해지면 Maven 멀티모듈로 전환한다.

## 결과
- 장점: 트랜잭션 일관성, 운영 단순성, 실제 DB 기반 테스트.
- 비용: Spring Boot 4 / Jackson 3 의 API 변경(패키지 `tools.jackson.*`)을 따른다.
