# Foundation API 계약 (M1 F0~F6, F9 인증 강화)

> docs/07 을 Foundation 범위에서 구체화한 계약이다. docs/07 에 없던 항목은 **[추가]** 로 표시했고 CHG-003 승인 대상이다.
> 구현: `backend/src/main/java/com/commerceos/**/**Controller.java`. 검증: `backend/src/test/java/com/commerceos/foundation/*`.

## 1. 공통 규칙
| 항목 | 규칙 |
|---|---|
| Base | `/api/v1` |
| 인증 | `Authorization: Bearer <access_token>` ([추가] `POST /auth/sessions` 로 발급). 절대 만료 12시간 + 비활동 만료 2시간(DLG-012). 응답의 `mfa_status`가 `NOT_REQUIRED`/`VERIFIED`가 아니면 2단계 인증 API·`GET /me`·로그아웃만 허용(DLG-014), 10분 안에 인증하지 않으면 세션 만료 |
| JSON | snake_case. 시간은 ISO-8601 + `+09:00`(DB 는 UTC). 금액은 decimal 문자열 + 통화 + 세금 구분(`shared/money`) |
| ID | 내부 UUIDv7. 외부 식별자는 별도 필드(`external_account_key`, `external_unit_key` 등) |
| 목록 | `limit`(1~200, 기본 50) / `cursor`(불투명) → `page.next_cursor` |
| 사업자 필터 | `business_entity_id` 생략 또는 `all` = 허용 범위 전체. 반복 파라미터 또는 콤마로 복수 지정. 범위 밖 지정 시 403 |
| 멱등 | 생성·실행 POST 는 `Idempotency-Key`(8~200자) 필수. 같은 키+같은 본문 → 저장된 응답 재생(`Idempotent-Replayed: true`), 다른 본문 → 422 `IDEMPOTENCY_KEY_REUSED`. 24시간 보관. 실패 응답은 저장하지 않음 |
| 동시 수정 [추가] | PATCH/PUT 은 `If-Match: "<version>"` 필수(없으면 428). 불일치 409 `VERSION_CONFLICT`. 단건 GET 은 `ETag` 반환 |
| 비동기 | `202 Accepted` + `job_id` → `GET /jobs/{id}` |
| 요청 추적 | 응답 헤더 `X-Request-Id`, 오류 본문 `request_id` |

### 오류 형식·코드
```json
{"error":{"code":"ACCESS_SCOPE_DENIED","message":"접근이 허용되지 않은 사업자 또는 판매계정입니다.","retryable":false,"details":{}},"request_id":"req-..."}
```
| HTTP | 코드(예) | 의미 |
|---|---|---|
| 401 | `UNAUTHENTICATED`, `LOGIN_FAILED`, `ACCOUNT_LOCKED` | 미인증·로그인 실패(존재 여부 비노출) |
| 403 | `PERMISSION_DENIED`, `ACCESS_SCOPE_DENIED`, `WRITE_SCOPE_DENIED`, `MFA_ENROLLMENT_REQUIRED`, `MFA_VERIFICATION_REQUIRED` | 권한·범위·2단계 인증 미완료 |
| 404 | `NOT_FOUND` | 없음, 다른 운영그룹, READ 범위 밖 객체 |
| 409 | `VERSION_CONFLICT`, `*_DUPLICATE`, `LAST_SUPER_ADMIN`, `MFA_ALREADY_ENROLLED`, `MFA_RESET_SHARED_USER`, `INVALID_STATE_TRANSITION`, `BUSINESS_ENTITY_HAS_ACTIVE_ACCOUNTS`, `EXTERNAL_KEY_IMMUTABLE` … | 충돌·상태 |
| 422 | `VALIDATION_FAILED`, `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_REUSED`, `PRIVILEGE_ESCALATION`, `CAPABILITY_NOT_AVAILABLE`, `CONNECTOR_NOT_AVAILABLE`, `JOB_TYPE_NOT_IMPLEMENTED`, `MFA_CODE_INVALID`, `MFA_NOT_ENROLLED`, `MFA_ENROLLMENT_NOT_STARTED`, `MFA_RESET_SELF`, `CURRENT_PASSWORD_MISMATCH` … | 검증·정책 |
| 423 | (M5 월 마감에서 사용) | 마감 잠금 |
| 428 | `IF_MATCH_REQUIRED` | 버전 누락 |

## 2. 엔드포인트
| 영역 | 메서드·경로 | 권한 | 범위 | 비고 |
|---|---|---|---|---|
| 인증 [추가] | `POST /auth/sessions` | — | — | `{workspace_code, email, password}` → `{access_token, expires_at, mfa_status, …}` |
| | `DELETE /auth/sessions/current` | 로그인 | — | 로그아웃 |
| | `POST /auth/password` | 로그인(2단계 인증 완료) | — | `{current_password, new_password}` → 204. 비밀번호 정책 DLG-011, 현재 세션 외 이 사용자의 모든 세션 폐기 |
| 2단계 인증 [추가] | `GET /auth/mfa` | 로그인 | — | `{mfa_status, enrolled, recovery_codes_remaining}` |
| | `POST /auth/mfa/totp/enrollment` | 로그인 | — | 201 `{secret, otpauth_uri, period_seconds, digits}`(비밀키는 이때만 표시) |
| | `POST /auth/mfa/totp/enrollment/confirm` | 로그인 | — | `{code}` → `{mfa_status, recovery_codes[10]}`(복구 코드는 이때만 표시) |
| | `POST /auth/mfa/verify` | 로그인 | — | `{code}` 또는 `{recovery_code}`. 같은 30초 구간 코드 재사용 불가, 실패는 로그인 잠금 횟수에 포함 |
| | `POST /auth/mfa/recovery-codes` | 로그인(인증 완료) | — | 복구 코드 재발급(기존 무효) |
| | `POST /users/{userId}/mfa-reset` | `access.manage` | — | 기기 분실 시 관리자 초기화 → 대상 세션 전부 폐기. 본인·다른 운영그룹 소속 사용자는 불가 |
| 내 정보 [추가] | `GET /me` | 로그인 | — | 역할·유효 권한·범위 요약(메뉴 노출용) |
| 운영그룹 | `GET /workspaces/current` | 로그인 | — | 운영그룹 생성은 시스템 작업(API 없음) |
| 사업자 | `GET /business-entities` | `business_entity.read` | READ | `status`, `q` |
| | `POST /business-entities` | `business_entity.manage` | — | 등록번호 검증·그룹 내 유일 |
| | `GET /business-entities/{id}` | `business_entity.read` | READ | |
| | `PATCH /business-entities/{id}` | `business_entity.manage` | WRITE | 등록번호·법인/개인 변경 불가 |
| | `GET /business-entities/{id}/history` [추가] | `business_entity.read` | READ | 감사로그 기반 |
| 계좌 [추가] | `GET /business-entities/{id}/bank-accounts` | `business_entity.read` | READ | `bank_account.read` 없으면 마스킹 |
| | `POST /business-entities/{id}/bank-accounts` | `bank_account.manage` | WRITE | 응답은 항상 마스킹 |
| | `PATCH /business-entities/{id}/bank-accounts/{accountId}` | `bank_account.manage` | WRITE | 기본계좌 1개 |
| 판매채널 [추가] | `GET /sales-channels` | 로그인 | — | 기준표(연동 지원 여부 아님) |
| 판매계정 | `GET /channel-accounts` | `channel_account.read` | READ | `sales_channel_code`, `status`, `q` |
| | `POST /channel-accounts` | `channel_account.manage` | WRITE(사업자) | 기본 판매단위·귀속 이력 동시 생성 |
| | `GET/PATCH /channel-accounts/{id}` | read / manage | READ / WRITE | 외부 계정키는 한 번 정해지면 변경 불가 |
| | `POST /channel-accounts/{id}/attribution-changes` [추가] | `channel_account.reassign` | WRITE(이전·새 사업자) | `If-Match` + 멱등키. 즉시 적용 |
| | `GET /channel-accounts/{id}/attributions` [추가] | `channel_account.read` | READ | 귀속 이력 |
| 판매단위 | `GET/POST /channel-accounts/{id}/sales-units` | read / manage | READ / WRITE | 멀티숍 채널만 추가 가능 |
| | `PATCH /channel-accounts/{id}/sales-units/{unitId}` [추가] | `channel_account.manage` | WRITE | 이름·상태만 |
| 물류 [추가] | `GET /logistics-providers` | 로그인 | — | 기준표 |
| | `GET/POST /shipper-accounts`, `GET/PATCH /shipper-accounts/{id}` | `logistics_account.read/manage` | READ/WRITE | 화주계정 |
| 사용자 | `GET /users`, `GET /users/{id}` [추가] | `member.read` 또는 `access.manage` | — | `{id}` = user_id |
| | `POST /users`, `PATCH /users/{id}` [추가] | `access.manage` | — | 권한 상승·마지막 관리자 보호 |
| | `GET/PUT /users/{id}/access-policy` | `access.manage`(본인 조회는 허용) | — | 전체 교체, `If-Match` = access_policy_version |
| 역할 [추가] | `GET /roles`, `GET /permissions` | `member.read` / 로그인 | — | |
| 연동 | `GET /integrations/connectors` [추가] | 로그인 | — | 등록된 커넥터(현재 모의만) |
| | `GET /integrations/connections` | `integration.read` | READ | `channel_account_id`, `status` |
| | `POST /integrations/connections` [추가] | `integration.manage` (+ 인증정보 포함 시 `integration.credential.manage`) | WRITE | 인증정보는 응답·감사·멱등 기록에 남지 않음 |
| | `GET/PATCH /integrations/connections/{id}` [추가] | read / manage | READ / WRITE | 상태 변경은 `PAUSED`/`RESUME` 만 |
| | `POST /integrations/connections/{id}/credentials` [추가] | `integration.credential.manage` | WRITE | 교체(이전 것은 ROTATED) |
| | `GET /integrations/capabilities?connection_id=` | `integration.read` | READ | `executable`, `blocked_reasons` |
| | `PUT /integrations/connections/{id}/capabilities/{feature}/{operation}` [추가] | `integration.capability.verify` | WRITE | VERIFIED 는 근거 필수·커넥터 구현 필수 |
| | `POST /integrations/{id}/sync-jobs` | `integration.sync` | WRITE | `{id}`=연결 ID. `job_type` 기본 `INTEGRATION_HEALTH_CHECK`. 데이터 수집 유형은 지원 기능 가드 후 `JOB_TYPE_NOT_IMPLEMENTED` |
| 업무함 | `GET /work-items`, `GET /work-items/{id}` [추가] | `work_item.read` | READ | `status`, `item_type` |
| | `PATCH /work-items/{id}` | `work_item.update` | WRITE | 상태 전이 표(DEC-F09) |
| 작업 | `GET /jobs/{id}` | 요청자 본인 또는 작업의 사업자 READ | | |
| 감사 [추가] | `GET /audit-logs` | `audit.read` | READ | 범위 밖 사업자 기록 제외 |
| 파일 [추가] | `POST /files`(multipart), `GET /files/{id}`, `GET /files/{id}/content` | `file.upload` / `file.read` | WRITE / READ | 개인정보 포함 파일은 `pii.read`, 내보내기 파일은 요청자만 |
| 내보내기 | `POST /exports` | `export.create` + 대상 조회 권한 | EXPORT | `BUSINESS_ENTITIES`, `CHANNEL_ACCOUNTS`. 실행 시 범위 재평가 |
| 검색 | `GET /search?q=` [추가 — docs/07 미기재] | 객체별 조회 권한 | READ | 2자 이상 |
| 저장된 보기 [추가] | `GET/POST /saved-views`, `PATCH/DELETE /saved-views/{id}` | 로그인 | 본인 것만 | |

## 3. 상태값
| 객체 | 상태 |
|---|---|
| 사업자 | `ACTIVE`, `INACTIVE` |
| 판매계정·판매단위·화주계정 | `ACTIVE`, `SUSPENDED`, `CLOSED`(종료 후 재개 불가) |
| 구성원 | `ACTIVE`, `SUSPENDED`, `REMOVED`(중지·제거 시 세션 즉시 폐기) |
| 연동 연결 | `PENDING_SETUP` → 점검 성공 `ACTIVE`; 실패 시 `DELAYED`/`ERROR`(연속 3회)/`AUTH_EXPIRED`; 사용자 `PAUSED`, 재개 시 `PENDING_SETUP` |
| 작업 | `PENDING` → `RUNNING` → `SUCCEEDED`/`RETRY_WAIT`/`REVIEW_REQUIRED`/`FAILED` |
| 업무 | `OPEN`, `IN_PROGRESS`, `ON_HOLD` ↔ 서로 전환; `DONE`, `IGNORED` 는 종료 |
| 지원 기능 | 지원수준 `OFFICIAL_DOCUMENTED/PERMISSION_REQUIRED/PARTIAL/FILE/MANUAL/UNVERIFIED/UNSUPPORTED` × 계정검증 `NOT_VERIFIED/VERIFIED/DENIED` |

## 4. 도메인 이벤트(Outbox, Foundation 발행분)
`BusinessEntityCreated`, `BusinessEntityUpdated`, `ChannelAccountCreated`, `ChannelAccountUpdated`, `ChannelAccountReassigned`, `IntegrationConnectionCreated`, `IntegrationConnectionAuthExpired`, `IntegrationConnectionErrored`, `IntegrationConnectionRecovered`. 필드는 docs/04 §10 형식(`outbox_event` 테이블).
