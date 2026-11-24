# 개발·배포·운영 인수인계 실행 체크리스트

## 개발환경 구성(권장)
- 문서 스택 기본안: Java 21 + Spring Boot, Next.js/React TypeScript, PostgreSQL, 테스트용 Redis/Outbox 작업자. 실제 버전과 Lockfile/Build Tool은 ADR로 확정.
- `docker compose`로 Postgres/선택적 Redis/Backend/Frontend 기본 실행. Secrets는 `.env.example`만 커밋, 실 토큰 금지.
- 마이그레이션/테스트와 샘플 데이터를 별도 실행. 테스트에는 가상 연락처/주소만 사용.

## CI 파이프라인
1. 코드 스타일/정적 분석/의존성 보안 스캔.
2. 스키마 마이그레이션 검사, 핵심 도메인 JUnit 테스트.
3. PostgreSQL Testcontainers 동시성/원장/권한 통합 테스트.
4. OpenAPI 계약 검증, Mock 외부 API 실패/중복/역순 테스트.
5. Frontend TypeScript/접근성/Playwright E2E.
6. 배포 전에 채널별 실제 권한·정산/환불/출고 테스트 승인 상태 확인.

## 운영 모니터링
- 주문 수집 지연·실패/중복, 출고 요청 실패/미수신, 3PL 재고 Stale, 외부 API 인증 만료·호출제한, 채널별 정산 대사 차이.
- Metrics: 계정별 수집 최근 성공, 오류율/재시도 수/대기 큐 길이, 비용 원장 누락, 사업자 권한 거부 이벤트.
- 감사: 재고 조정·폐기·환불·월마감·사업자 소유권 변경·접근정책 변경을 추적.

### 구현된 운영 도구(M7, 2026-11-23)
- **운영 현황**(`관리 > 운영 현황`, `GET /api/v1/ops/status`, 권한 `audit.read`): 전체 판정(정상·주의·위험)과 점검 항목.
  - 위험: 전달 실패한 내부 이벤트(`OUTBOX_FAILED`), 인증 만료 연동, 긴급 업무.
  - 주의: 이벤트 적체 10분 초과(`commerce.ops.outbox-warn`), 멈춘 작업(lease 만료), 24시간 실패 작업, 확인 필요 작업, 연동 연속 실패·24시간 동기화 없음(`commerce.ops.sync-stale`)·14일 내 인증 만료, 권한 거부 24시간 20건 이상.
  - 판매계정별 마지막 주문 수신 시각, 최근 7일 실패 작업 유형, 개인정보 원문 열람·내보내기 건수.
- **보관기간 정리**(매일 03:47 KST, `commerce.retention.*`): 만료 후 24시간 지난 내보내기 파일(본문·기록)과 만료된 멱등키 기록 삭제, 감사로그 `RETENTION_PURGED`. 원본 payload·감사로그·원장은 지우지 않는다(주문 개인정보 파기는 별도 작업 04:23).
- 정기 작업 시각(KST): 재고 검증 03:17, 보관기간 정리 03:47, 주문 개인정보 정리 04:23, 인사이트 분석 06:17, CS 응답기한 30분마다, 송장 감시 30분마다.

## 백업·복구(M7, DLG-218)
- 백업: `PGHOST=… PGUSER=… PGPASSWORD=… PGDATABASE=commerce_os BACKUP_DIR=/secure/backups scripts/db-backup.sh` — pg_dump 사용자 지정 형식 + SHA-256, `BACKUP_KEEP_DAYS`(기본 14) 지난 파일 정리. 백업에는 개인정보 암호문이 있으므로 접근 제한 저장소·암호화 볼륨에 둔다. 암호화 키(`CRYPTO_KEY_*`)는 백업과 **다른 곳**에 보관(키 없이는 개인정보 복호화 불가).
- 클라이언트 버전: `pg_dump`·`pg_restore` 는 서버와 같은 주 버전을 쓴다. 더 새 버전의 덤프는 옛 서버에서 복구되지 않을 수 있다(예: 17+ 의 `SET transaction_timeout` 은 16 에서 오류). 호스트 도구 버전이 다르면 `PG_CLIENT_IMAGE=postgres:16-alpine` 를 주면 두 스크립트가 그 이미지 안의 도구를 쓴다(CI 복구 리허설 기본값). 데이터베이스 준비 대기는 초기화용 임시 서버가 아니라 TCP 접속 기준.
- 복구 리허설: `scripts/db-restore-check.sh <백업 파일>` — 임시 PostgreSQL(도커)에 복구 후 체크섬·마이그레이션 이력·핵심 테이블 행 수·추가 전용 트리거·RLS 정책을 점검하고 지운다(운영 DB 를 건드리지 않음). CI 의 `restore-rehearsal` 작업이 매 빌드마다 새 스키마로 같은 절차를 돈다.
- 첨부·업로드 파일(`FILES_ROOT` 또는 객체 저장소)은 DB 와 같은 시점으로 별도 백업한다.
- RPO/RTO·백업 주기·보관 기간 수치는 운영계약 후 확정(docs/08 §6). 권장 초기값: 매일 전체 백업 + WAL 아카이브(시점 복구), 분기 1회 복구 리허설 기록.

## 로컬 실행
- `cd backend && mvn -B -DskipTests package && cd .. && docker compose up --build` (`.env` 에 `CRYPTO_KEY_K1`·`CRYPTO_HMAC_KEY`·`BOOTSTRAP_OWNER_PASSWORD`). 화면 http://localhost:3000.

## 운영 사고 처리
- 계정 인증 만료: 해당 연결 중지, 업무함·관리자 알림, 다른 계정은 운영 지속.
- 3PL 주문 전송 타임아웃: 외부 ID 조회/중복 확인, 안전 재처리.
- 외부 재고 Snapshot 지연: 데이터 기준시각 경고, 추가 판매가능수량 보수적 제한, 확인 업무 생성.
- 정산 불일치: 원본파일/계정/거래번호/변환 규칙 추적, 원장 직접 덮어쓰기 금지.
- 고객 데이터 유출 의심: 권한 회수·토큰 폐기·감사확보·별도 사고 대응 절차.

## 배포/롤백
- DB 변화는 전방 호환 migration(필드 추가→이전 데이터 backfill→사용전환→구 필드 정리)을 권장.
- 원장·재고 파괴적 변경은 롤백 SQL 하나로 되돌릴 수 없을 수 있음. 배포 전 영향도/복구 리허설.
- 외부 API 연결은 Feature Flag 및 계정별 점진 활성화, Mock/샌드박스→파일럿 판매자→운영 계정 순서.

## 출시 승인 게이트
- 모든 P0 테스트 통과, 권한·PII 우회 없음.
- 사업자·판매계정별 주문·재고/정산 귀속 확인.
- 정산/손익 출시 시 계산 Fixture 및 수수료/환불·원가 중복 검사 통과.
- 채널/3PL 각 실제 고객 계정에서 지원 기능·호출제한·데이터 소스 인증 확인.
- 데이터 백업·복구와 사고 알림 책임자/운영팀 지정.
