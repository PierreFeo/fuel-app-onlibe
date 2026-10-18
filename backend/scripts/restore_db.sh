#!/usr/bin/env bash
# Восстановление prod-базы из дампа backup_db.sh. ВСЕ текущие данные базы заменяются!
#   bash /opt/fuel-app/backend/scripts/restore_db.sh /opt/fuel-app/backups/fuel-2026-10-18_0330.dump
# На время восстановления api остановлен — телефоны получат ошибку сети и синхронизируются позже.
# Переменные для проверки на dev: COMPOSE_FILE, ENV_FILE, API_SERVICE, YES=1 (без вопроса).
set -euo pipefail

if [ $# -ne 1 ] || [ ! -f "$1" ]; then
  echo "Использование: $0 <файл .dump>" >&2
  exit 1
fi
DUMP="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"

BACKEND_DIR="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env.prod}"
API_SERVICE="${API_SERVICE:-api}"
cd "$BACKEND_DIR"
dc() { docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"; }

if [ "${YES:-}" != "1" ]; then
  read -r -p "Заменить данные базы содержимым $(basename "$DUMP")? Введите yes: " answer
  [ "$answer" = "yes" ] || { echo "Отменено"; exit 1; }
fi

dc stop "$API_SERVICE"
# Сервер запускаем обратно в любом случае, даже если восстановление упало
trap 'dc start "$API_SERVICE"' EXIT

# --clean --if-exists — удалить таблицы перед созданием; --single-transaction — при ошибке
# база остаётся как была, а не наполовину восстановленной
dc exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --single-transaction' < "$DUMP"
echo "Готово: база восстановлена из $(basename "$DUMP")"
