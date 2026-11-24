#!/usr/bin/env bash
# 운영 DB 백업(M7, docs/16 §운영, DLG-218). pg_dump 사용자 지정 형식 + SHA-256, 보관 일수 지난 백업 정리.
# 사용: PGHOST=… PGPORT=5432 PGUSER=… PGPASSWORD=… PGDATABASE=commerce_os BACKUP_DIR=/backups scripts/db-backup.sh
#   PG_CLIENT_IMAGE: 서버와 같은 버전의 pg_dump 를 도커 이미지로 쓸 때(예: postgres:16-alpine)
#   BACKUP_KEEP_DAYS(기본 14): 이보다 오래된 백업 파일 삭제. 백업 파일은 개인정보 암호문을 포함하므로 접근 제한 저장소에 둔다.
set -euo pipefail
# 클라이언트 도구 버전은 서버와 같아야 한다(새 pg_dump 의 덤프는 옛 서버에서 복구되지 않을 수 있음).
# PG_CLIENT_IMAGE(예: postgres:16-alpine)를 주면 그 이미지 안의 도구를 쓴다(호스트 네트워크, 작업 폴더만 연결).
pgtool() {
  if [[ -n "${PG_CLIENT_IMAGE:-}" ]]; then
    docker run --rm -i --network host --user "$(id -u):$(id -g)" -e PGHOST -e PGPORT -e PGUSER -e PGPASSWORD -e PGDATABASE \
      -v "$PG_MOUNT:$PG_MOUNT" -w "$PG_MOUNT" "$PG_CLIENT_IMAGE" "$@"
  else
    "$@"
  fi
}
BACKUP_DIR="${BACKUP_DIR:-./backups}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
DB="${PGDATABASE:-commerce_os}"
mkdir -p "$BACKUP_DIR"
BACKUP_DIR="$(cd "$BACKUP_DIR" && pwd)"
PG_MOUNT="$BACKUP_DIR"
chmod 700 "$BACKUP_DIR" || true
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$BACKUP_DIR/${DB}-${STAMP}.dump"
pgtool pg_dump --format=custom --compress=6 --no-owner --file="$OUT" "$DB"
sha256sum "$OUT" > "$OUT.sha256"
chmod 600 "$OUT" "$OUT.sha256"
find "$BACKUP_DIR" -maxdepth 1 -name "${DB}-*.dump*" -type f -mtime "+${KEEP_DAYS}" -print -delete
echo "백업 완료: $OUT ($(du -h "$OUT" | cut -f1))"
