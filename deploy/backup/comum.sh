#!/usr/bin/env bash
# Funções e variáveis dos scripts de backup. Não execute este arquivo: ele é lido pelos outros ("source").
#
# Tudo roda por "docker compose exec" no container do banco, então não precisa de pg_dump instalado na VPS e nada
# abre porta nova. Para a VPS, defina antes (ou no crontab):
#   export MESAMAR_COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env"
# Sem isso, usa o docker-compose.yml da pasta atual (o ambiente local e o do CI).

COMPOSE="${MESAMAR_COMPOSE:-docker compose}"
DB_USER="${DB_USER:-mesamar}"
DB_NAME="${DB_NAME:-mesamar}"

# Banco de treino ou de teste: nunca o de produção nem os bancos do próprio PostgreSQL. Um MESAMAR_BANCO_TREINO ou
# MESAMAR_BANCO_TESTE configurado errado (igual a DB_NAME) faria o DROP DATABASE apagar os dados de verdade.
banco_descartavel() {
  local banco="$1"
  [[ "$banco" =~ ^[a-z0-9_]+$ ]] || falha "nome de banco inválido: $banco"
  case "$banco" in
    "$DB_NAME" | postgres | template0 | template1)
      falha "o banco '$banco' não pode ser usado para treino ou teste (é o de produção ou um banco do sistema)" ;;
  esac
}
BACKUP_DIR="${MESAMAR_BACKUP_DIR:-/opt/mesamar/backups}"
BACKUP_DIAS="${MESAMAR_BACKUP_DIAS:-14}"

log() {
  printf '%s %s\n' "$(date '+%F %T')" "$*"
}

falha() {
  log "ERRO: $*" >&2
  exit 1
}

# psql dentro do container do banco, no banco indicado (padrão: o de produção).
psql_db() {
  local banco="$1"
  shift
  # </dev/null: o "docker compose exec -T" lê a entrada padrão e, dentro de um "while read", comeria o resto da lista
  $COMPOSE exec -T db psql -U "$DB_USER" -d "$banco" -v ON_ERROR_STOP=1 -X -q -t -A "$@" < /dev/null
}

# O banco está de pé e aceitando conexões?
conferir_banco() {
  $COMPOSE exec -T db pg_isready -U "$DB_USER" -d "$DB_NAME" < /dev/null > /dev/null 2>&1 \
    || falha "o banco não está respondendo. O container 'db' está de pé? (MESAMAR_COMPOSE='$COMPOSE')"
}

# Gera um dump no formato custom (comprimido, restaura tabela a tabela) em $1 e confere que ele é legível.
gerar_dump() {
  local destino="$1"
  local parcial="$destino.parcial"
  $COMPOSE exec -T db pg_dump -U "$DB_USER" -d "$DB_NAME" --format=custom --compress=6 > "$parcial" \
    || { rm -f "$parcial"; falha "o pg_dump falhou."; }
  [ -s "$parcial" ] || { rm -f "$parcial"; falha "o dump saiu vazio."; }
  # pg_restore --list lê o índice do arquivo: se estiver truncado ou corrompido, falha aqui
  $COMPOSE exec -T db pg_restore --list < "$parcial" > /dev/null \
    || { rm -f "$parcial"; falha "o dump gerado não é legível (pg_restore --list falhou)."; }
  mv "$parcial" "$destino"
}
