#!/usr/bin/env bash
# 백업 복구 리허설(M7, DLG-218): 백업 파일을 임시 PostgreSQL(도커)에 복구하고 무결성을 점검한 뒤 지운다. 운영 DB 는 건드리지 않는다.
# 사용: scripts/db-restore-check.sh backups/commerce_os-20261123T000000Z.dump
# 점검: 체크섬, 복구 성공, 스키마 마이그레이션 이력 마지막 버전·실패 없음, 핵심 테이블 행 수, 추가 전용 트리거·RLS 정책 존재.
set -euo pipefail
DUMP="${1:?백업 파일 경로를 주세요}"
[[ -f "$DUMP" ]] || { echo "파일이 없습니다: $DUMP"; exit 1; }
if [[ -f "$DUMP.sha256" ]]; then
  (cd "$(dirname "$DUMP")" && sha256sum -c "$(basename "$DUMP").sha256" >/dev/null) || { echo "체크섬 불일치"; exit 1; }
  echo "체크섬 확인"
fi
NAME="commerce-os-restore-check-$$"
PORT="${RESTORE_CHECK_PORT:-55499}"
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run -d --rm --name "$NAME" -e POSTGRES_DB=restore_check -e POSTGRES_USER=restore -e POSTGRES_PASSWORD=restore -p "$PORT:5432" postgres:16-alpine >/dev/null
# 초기화 중 임시 서버(유닉스 소켓만)가 아니라 실제 서버가 TCP 로 받을 때까지 기다린다
ready=0; for i in $(seq 1 90); do docker exec "$NAME" pg_isready -h 127.0.0.1 -U restore >/dev/null 2>&1 && { ready=1; break; }; sleep 1; done
[[ $ready -eq 1 ]] || { echo "PostgreSQL 이 준비되지 않았습니다"; docker logs "$NAME" 2>&1 | tail -20; exit 1; }
export PGHOST=localhost PGPORT="$PORT" PGUSER=restore PGPASSWORD=restore PGDATABASE=restore_check
# 원본의 역할(소유자·비소유 애플리케이션 역할)을 먼저 만들어 권한 설정까지 복구한다. 역할 이름: RESTORE_ROLES(공백 구분)
for r in ${RESTORE_ROLES:-commerce_os commerce_app}; do
  psql -q -v ON_ERROR_STOP=1 -c "do \$\$ begin if not exists (select 1 from pg_roles where rolname = '$r') then create role $r nologin; end if; end \$\$;"
done
START=$(date +%s)
pg_restore --no-owner --exit-on-error --dbname=restore_check "$DUMP"
echo "복구 완료($(( $(date +%s) - START ))초)"
fail=0
q() { psql -At -v ON_ERROR_STOP=1 -c "$1"; }
LAST=$(q "select max(version::int) from flyway_schema_history where success" 2>/dev/null || echo "")
FAILED=$(q "select count(*) from flyway_schema_history where not success" 2>/dev/null || echo "?")
echo "스키마 마지막 버전: V${LAST:-없음}, 실패한 마이그레이션: ${FAILED}"
[[ -n "$LAST" && "$FAILED" == "0" ]] || fail=1
for t in workspace business_entity sales_order order_item inventory_transaction financial_entry audit_log settlement_line vat_row; do
  if q "select 1 from information_schema.tables where table_name = '$t'" | grep -q 1; then
    echo "  $t: $(q "select count(*) from $t") 행"
  else
    echo "  $t: 테이블 없음"; fail=1
  fi
done
TRIGGERS=$(q "select count(*) from pg_trigger where tgname like '%append_only%' or tgname like '%guard%'")
RLS=$(q "select count(*) from pg_class where relrowsecurity")
echo "추가 전용·보호 트리거 ${TRIGGERS}개, RLS 적용 테이블 ${RLS}개"
[[ "$TRIGGERS" -gt 10 && "$RLS" -gt 30 ]] || fail=1
if [[ $fail -ne 0 ]]; then echo "복구 리허설 실패"; exit 1; fi
echo "복구 리허설 통과"
