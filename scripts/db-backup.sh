#!/usr/bin/env bash
# 운영 DB 백업(M7, docs/16 §운영, DLG-218). pg_dump 사용자 지정 형식 + SHA-256, 보관 일수 지난 백업 정리.
# 사용: PGHOST=… PGPORT=5432 PGUSER=… PGPASSWORD=… PGDATABASE=commerce_os BACKUP_DIR=/backups scripts/db-backup.sh
#   BACKUP_KEEP_DAYS(기본 14): 이보다 오래된 백업 파일 삭제. 백업 파일은 개인정보 암호문을 포함하므로 접근 제한 저장소에 둔다.
set -euo pipefail
BACKUP_DIR="${BACKUP_DIR:-./backups}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
DB="${PGDATABASE:-commerce_os}"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR" || true
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$BACKUP_DIR/${DB}-${STAMP}.dump"
pg_dump --format=custom --compress=6 --no-owner --file="$OUT" "$DB"
sha256sum "$OUT" > "$OUT.sha256"
chmod 600 "$OUT" "$OUT.sha256"
find "$BACKUP_DIR" -maxdepth 1 -name "${DB}-*.dump*" -type f -mtime "+${KEEP_DAYS}" -print -delete
echo "백업 완료: $OUT ($(du -h "$OUT" | cut -f1))"
