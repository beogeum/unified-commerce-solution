#!/usr/bin/env bash
# CI: 새 DB 에 마이그레이션 적용(백엔드 기동) → 백업 → 복구 리허설(M7, DLG-218).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
NAME="commerce-os-ci-src-$$"
PORT=55488
cleanup() { [[ -n "${BE_PID:-}" ]] && kill -- "-$BE_PID" 2>/dev/null || true; docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run -d --rm --name "$NAME" -e POSTGRES_DB=commerce_os -e POSTGRES_USER=commerce_os -e POSTGRES_PASSWORD=commerce_os -p "$PORT:5432" postgres:16-alpine >/dev/null
# 초기화 중 임시 서버(유닉스 소켓만)가 아니라 실제 서버가 TCP 로 받을 때까지 기다린다
ready=0; for i in $(seq 1 90); do docker exec "$NAME" pg_isready -h 127.0.0.1 -U commerce_os >/dev/null 2>&1 && { ready=1; break; }; sleep 1; done
[[ $ready -eq 1 ]] || { echo "PostgreSQL 이 준비되지 않았습니다"; docker logs "$NAME" 2>&1 | tail -20; exit 1; }
(cd "$ROOT/backend" && mvn -q -B -DskipTests package)
JAR="$(ls "$ROOT"/backend/target/*.jar | grep -v plain | head -1)"
DB_URL="jdbc:postgresql://localhost:$PORT/commerce_os" DB_USER=commerce_os DB_PASSWORD=commerce_os CRYPTO_ACTIVE_KEY_ID=k1 \
CRYPTO_KEY_K1="$(head -c32 /dev/urandom | base64)" CRYPTO_HMAC_KEY="$(head -c32 /dev/urandom | base64)" FILES_ROOT="$WORK/files" \
  setsid java -jar "$JAR" --server.port=18098 > "$WORK/backend.log" 2>&1 &
BE_PID=$!
for i in $(seq 1 90); do curl -sf http://localhost:18098/actuator/health >/dev/null 2>&1 && break; sleep 1; done
curl -sf http://localhost:18098/actuator/health >/dev/null || { tail -50 "$WORK/backend.log"; exit 1; }
kill -- "-$BE_PID"; BE_PID=""
PGHOST=localhost PGPORT=$PORT PGUSER=commerce_os PGPASSWORD=commerce_os PGDATABASE=commerce_os BACKUP_DIR="$WORK/backups" "$ROOT/scripts/db-backup.sh"
"$ROOT/scripts/db-restore-check.sh" "$(ls "$WORK"/backups/*.dump | head -1)"
