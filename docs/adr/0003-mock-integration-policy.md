# ADR-0003 외부 연동은 모의 커넥터로만 개발

- 상태: **승인됨(사용자 지시 2026-11-21) — 실제 커넥터는 계정 권한 확인 후 채널별 ADR**
- 관련: CLAUDE.md, docs/06, OQ-005/006/014, DEC-F08

## 결정
1. 커넥터 SPI(`ChannelConnector`, `LogisticsConnector`)는 docs/06 §6 계약을 그대로 따른다. 응답은 `ConnectorEnvelope`(source_system, account_id, raw_payload_ref, external_id, mapped_objects, mapping_version, source_timestamp, fetched_at, capability_version)로 감싼다.
2. 등록된 구현은 `mock-channel`, `mock-3pl` 뿐이며 `commerce.integration.mock-connectors-enabled=true`(개발·테스트)일 때만 로드된다. 기본값은 false.
3. 모의 커넥터는 실제 채널 API 형식을 추정해 흉내 내지 않는다. 장애 분류(인증 만료·권한 없음·호출 제한·일시 장애·미지원)와 격리 흐름만 검증한다.
4. 모든 연결의 지원 기능은 `UNVERIFIED / NOT_VERIFIED` 로 시작한다. 실행 가능 = 커넥터 구현 ∧ 지원수준 OFFICIAL_DOCUMENTED|PARTIAL ∧ 계정 검증 VERIFIED(근거 필수) ∧ 연결 ACTIVE.
5. `mock-3pl` 은 반품검수(RETURN_INSPECTION)를 일부러 구현하지 않아, 검수 API 미제공 물류사에서 서버가 검증 완료 기록과 실행을 거부하는지 확인한다.

## 실제 커넥터 추가 절차(채널별)
docs/06 §7 체크리스트 10개 항목 확인 → 테스트 계정 요청·응답을 비식별화해 `contracts/fixtures/<channel>/` 에 저장 → 계약 테스트 작성 → 커넥터 구현 → 계정별 지원 기능 검증 기록 → Feature Flag 로 파일럿 계정부터 활성화(docs/16).
