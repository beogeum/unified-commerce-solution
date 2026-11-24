#!/usr/bin/env bash
# F7 E2E: PostgreSQL(도커) + 백엔드(jar) + 프론트엔드(next start)를 띄우고 Playwright 를 실행한다.
# 사용: scripts/e2e.sh   (필요: docker, java 21, maven, node 22)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${E2E_WORK_DIR:-$(mktemp -d)}"
PG_PORT="${E2E_PG_PORT:-55432}"
BE_PORT="${E2E_BACKEND_PORT:-18080}"
FE_PORT="${E2E_FRONTEND_PORT:-3000}"
PG_NAME="commerce-os-e2e-$$"
for p in "$BE_PORT" "$FE_PORT"; do
  if curl -s -o /dev/null "http://localhost:$p" 2>/dev/null; then echo "포트 $p 가 이미 사용 중입니다. 먼저 종료하세요."; exit 1; fi
done

cleanup() {
  # 각 서버는 setsid 로 별도 프로세스 그룹에서 띄워 자식(next-server 등)까지 함께 종료한다
  [[ -n "${FE_PID:-}" ]] && kill -- "-$FE_PID" 2>/dev/null || true
  [[ -n "${BE_PID:-}" ]] && kill -- "-$BE_PID" 2>/dev/null || true
  docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run -d --rm --name "$PG_NAME" -e POSTGRES_DB=commerce_os -e POSTGRES_USER=commerce_os -e POSTGRES_PASSWORD=commerce_os \
  -p "$PG_PORT:5432" postgres:16-alpine >/dev/null
# 초기화 중 임시 서버(유닉스 소켓만)가 아니라 실제 서버가 TCP 로 받을 때까지 기다린다
ready=0; for i in $(seq 1 90); do docker exec "$PG_NAME" pg_isready -h 127.0.0.1 -U commerce_os >/dev/null 2>&1 && { ready=1; break; }; sleep 1; done
[[ $ready -eq 1 ]] || { echo "PostgreSQL 이 준비되지 않았습니다"; docker logs "$PG_NAME" 2>&1 | tail -20; exit 1; }

(cd "$ROOT/backend" && mvn -q -B -DskipTests package)
JAR="$(ls "$ROOT"/backend/target/*.jar | grep -v plain | head -1)"

export E2E_ADMIN_EMAIL="admin@e2e.test"
export E2E_ADMIN_PASSWORD="E2e-Owner-pass-7391"
export E2E_WORKSPACE="e2e"
DB_URL="jdbc:postgresql://localhost:$PG_PORT/commerce_os" DB_USER=commerce_os DB_PASSWORD=commerce_os \
CRYPTO_ACTIVE_KEY_ID=k1 CRYPTO_KEY_K1="$(head -c32 /dev/urandom | base64)" CRYPTO_HMAC_KEY="$(head -c32 /dev/urandom | base64)" \
FILES_ROOT="$WORK/files" MOCK_CONNECTORS_ENABLED=true \
BOOTSTRAP_ENABLED=true BOOTSTRAP_WORKSPACE_CODE="$E2E_WORKSPACE" BOOTSTRAP_OWNER_EMAIL="$E2E_ADMIN_EMAIL" BOOTSTRAP_OWNER_PASSWORD="$E2E_ADMIN_PASSWORD" \
  setsid java -jar "$JAR" --server.port="$BE_PORT" > "$WORK/backend.log" 2>&1 &
BE_PID=$!
for i in $(seq 1 90); do curl -sf "http://localhost:$BE_PORT/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
curl -sf "http://localhost:$BE_PORT/actuator/health" >/dev/null || { echo "backend failed to start"; tail -50 "$WORK/backend.log"; exit 1; }

cd "$ROOT/frontend"
[[ -d node_modules ]] || npm ci --no-audit --no-fund
npx next build >/dev/null
BACKEND_URL="http://localhost:$BE_PORT" DEFAULT_WORKSPACE_CODE="$E2E_WORKSPACE" NODE_ENV=production COOKIE_INSECURE=true \
  setsid npx next start -p "$FE_PORT" > "$WORK/frontend.log" 2>&1 &
FE_PID=$!
for i in $(seq 1 60); do curl -sf "http://localhost:$FE_PORT/login" >/dev/null 2>&1 && break; sleep 1; done

E2E_BASE_URL="http://localhost:$FE_PORT" npx playwright test --trace off "$@"
