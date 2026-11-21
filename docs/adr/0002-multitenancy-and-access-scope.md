# ADR-0002 멀티테넌시와 사용자별 접근 범위

- 상태: **승인됨(2026-11-21 위임 결정 DLG-002, DLG-022) — RLS 방어 계층 F8**
- 날짜: 2026-11-21
- 관련: FR-ORG-05/06, docs/02 §4, docs/07 §4, docs/08 §2, DEC-F01~F05

## 1. 운영그룹(테넌트) 격리 — 3중 방어
1. **세션 바인딩**: 접근 토큰은 (사용자, 운영그룹) 단위로 발급된다. 토큰으로 다른 운영그룹을 지정할 방법이 없다.
2. **쿼리 한정**: 모든 조회·수정 SQL 은 `workspace_id = :현재운영그룹` 조건을 포함한다. 다른 그룹 ID 는 항상 "없음(404)".
3. **DB 복합 FK**: 자식 행은 `(workspace_id, parent_id) → parent(workspace_id, id)` 로 부모를 참조한다. 다른 그룹 객체를 가리키는 행은 DB 가 거부한다(테스트: `database_rejectsCrossWorkspaceReferences`).

PostgreSQL RLS 는 4번째 방어선으로 F8 에서 검토한다(연결 풀 + `SET LOCAL` 운영 부담 평가 필요).

## 2. 범위 엔진
```
권한   = 역할 권한 ∪ 사용자 ALLOW − 사용자 DENY            (DENY 우선)
범위   = 차원(사업자, 판매계정, 거점) × 종류(READ, WRITE, EXPORT) 마다 mode ALL | SELECTED
eff(D,READ)   = allow(D,READ) − deny(D,READ)
eff(D,K)      = allow(D,K) ∩ eff(D,READ) − deny(D,K)
객체 허용     = 사업자 ∈ eff(사업자,K) ∧ (판매계정 없음 ∨ 판매계정 ∈ eff(판매계정,K))
```
- 설정이 없으면 `ALL` → 같은 운영그룹의 다른 사업자도 기본 조회 가능(CONF-003).
- 해석 결과를 운영그룹 내 실제 ID 집합으로 풀어 SQL `= any(:ids)` 로 적용한다. 목록·상세·검색·내보내기·감사 조회·작업 조회가 같은 집합을 쓴다.
- 백그라운드 작업(내보내기)은 **실행 시점**에 요청자 범위를 다시 계산한다.

## 3. 응답 코드
| 상황 | 응답 |
|---|---|
| 다른 운영그룹 객체 | 404 `NOT_FOUND` |
| 같은 그룹, READ 범위 밖 객체 ID | 404 `NOT_FOUND` + 감사 `DENIED` |
| READ 가능, WRITE 범위 밖 | 403 `WRITE_SCOPE_DENIED` |
| 범위 밖 사업자를 필터로 명시 | 403 `ACCESS_SCOPE_DENIED` |
| 권한 없음 | 403 `PERMISSION_DENIED` + 감사 `DENIED` |

## 4. 거래 시점 귀속
판매계정 귀속은 `channel_account_attribution` 이력이 진실원이며, `channel_account_business_at(계정, 시각)` 으로 거래 시점 사업자를 구한다. 주문(M4) 등 거래 객체는 이 값을 스냅샷으로 저장하고, 범위 판단은 **스냅샷 사업자**로 한다(REV-012/057).

## 5. 대안과 기각 이유
- 스키마/DB per tenant: 운영그룹 수가 늘면 마이그레이션·연결 관리 비용이 큼. 논리 격리 + 복합 FK 로 충분한 강도를 확보.
- 범위를 SQL 서브쿼리로만 표현: 쿼리마다 조인 로직이 흩어져 누락 위험. 해석 후 ID 집합 전달 방식이 검증·테스트가 쉽다.
