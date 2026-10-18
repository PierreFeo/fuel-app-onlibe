#!/usr/bin/env bash
# Резервная копия prod-базы: pg_dump из контейнера db → backups/fuel-ГГГГ-ММ-ДД_ЧЧММ.dump,
# копии старше KEEP_DAYS дней удаляются. Запуск по cron раз в сутки (docs/02_ARCHITECTURE.md):
#   30 3 * * * bash /opt/fuel-app/backend/scripts/backup_db.sh
# Переменные для проверки на dev: COMPOSE_FILE, ENV_FILE, BACKUP_DIR, KEEP_DAYS.
set -euo pipefail

BACKEND_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env.prod}"
BACKUP_DIR="${BACKUP_DIR:-$BACKEND_DIR/../backups}"
KEEP_DAYS="${KEEP_DAYS:-14}"

mkdir -p "$BACKUP_DIR"
LOG="$BACKUP_DIR/backup.log"
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"; }
name="fuel-$(date '+%Y-%m-%d_%H%M').dump"
tmp="$BACKUP_DIR/$name.tmp"
trap 'rm -f "$tmp"; log "ОШИБКА: бэкап не создан"' ERR

cd "$BACKEND_DIR"

# Логин и имя базы берём из переменных контейнера — пароли в скрипте не нужны.
# -Fc — сжатый формат pg_restore; -T — без терминала (cron).
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T db \
  sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$tmp"
# Пишем во временный файл и переименовываем — недописанный дамп не выглядит готовым
mv "$tmp" "$BACKUP_DIR/$name"

find "$BACKUP_DIR" -maxdepth 1 -name 'fuel-*.dump' -mtime "+$((KEEP_DAYS - 1))" -delete
find "$BACKUP_DIR" -maxdepth 1 -name 'fuel-*.dump.tmp' -delete

log "OK $name ($(du -h "$BACKUP_DIR/$name" | cut -f1))"
