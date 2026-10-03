#!/usr/bin/env bash
# Configuração efetiva do Compose e uma trava compartilhada por backup/restauração.
set -euo pipefail
umask 077
log() { printf '%s %s\n' "$(date -u '+%FT%TZ')" "$*"; }
falha() { log "ERRO: $*" >&2; exit 1; }
BACKUP_DIAS="${MESAMAR_BACKUP_DIAS:-14}"
[[ "$BACKUP_DIAS" =~ ^[1-9][0-9]?$ ]] && (( BACKUP_DIAS <= 90 )) || falha 'MESAMAR_BACKUP_DIAS deve ser inteiro entre 1 e 90 (sem zeros iniciais).'
BACKUP_DIR="${MESAMAR_BACKUP_DIR:-/opt/mesamar/backups}"
# Arquivo/env são passados como argumentos; a .env nunca é executada como shell.
COMPOSE=(docker compose)
if [[ -n "${MESAMAR_COMPOSE:-}" ]]; then
  read -r -a COMPOSE <<< "$MESAMAR_COMPOSE" # compatibilidade com instalações anteriores
fi
[[ -z "${MESAMAR_COMPOSE_FILE:-}" ]] || COMPOSE+=(-f "$MESAMAR_COMPOSE_FILE")
[[ -z "${MESAMAR_ENV_FILE:-}" ]] || COMPOSE+=(--env-file "$MESAMAR_ENV_FILE")
# Valores do container em execução prevalecem sobre variáveis divergentes do shell.
DB_USER="$("${COMPOSE[@]}" exec -T db printenv POSTGRES_USER)"
DB_NAME="$("${COMPOSE[@]}" exec -T db printenv POSTGRES_DB)"
DB_MIGRATION_USER="$("${COMPOSE[@]}" exec -T db printenv DB_MIGRATION_USER || true)"
for nome in "$DB_USER" "$DB_NAME" "${DB_MIGRATION_USER:-migration}"; do
  [[ "$nome" =~ ^[a-z][a-z0-9_]{0,62}$ ]] || falha 'Nome de banco/conta inválido na configuração efetiva.'
done
# Extensão do Compose: retenção e destino usam a mesma .env/interpolação do banco.
config="$("${COMPOSE[@]}" config --format json)"
mapfile -t operacao < <(printf '%s' "$config" | python3 -c 'import json,sys; c=json.load(sys.stdin).get("x-operacao",{}); keys=["dias","diretorio","repositorio","senhaArquivo","awsArquivo","lgpdRegistro","pingBackup","rpoSegundos","rtoSegundos"]; print("\n".join(str(c.get(k,"")) for k in keys))')
unset config
BACKUP_DIAS="${operacao[0]:-$BACKUP_DIAS}"
[[ "$BACKUP_DIAS" =~ ^[1-9][0-9]?$ ]] && (( BACKUP_DIAS <= 90 )) || falha 'Retenção efetiva deve estar entre 1 e 90.'
BACKUP_DIR="${operacao[1]:-$BACKUP_DIR}"
export RESTIC_REPOSITORY="${operacao[2]:-${RESTIC_REPOSITORY:-}}"
export RESTIC_PASSWORD_FILE="${operacao[3]:-${RESTIC_PASSWORD_FILE:-}}"
AWS_ARQUIVO="${operacao[4]:-${MESAMAR_AWS_ARQUIVO:-}}"
export MESAMAR_LGPD_REGISTRO="${operacao[5]:-${MESAMAR_LGPD_REGISTRO:-/opt/mesamar/lgpd/exclusoes.txt}}"
BACKUP_PING="${operacao[6]:-${MESAMAR_BACKUP_PING_URL:-}}"
RPO_SEGUNDOS="${operacao[7]:-86400}"
RTO_SEGUNDOS="${operacao[8]:-7200}"
for prazo in "$RPO_SEGUNDOS" "$RTO_SEGUNDOS"; do [[ "$prazo" =~ ^[1-9][0-9]{0,6}$ ]] || falha 'RPO/RTO devem ser inteiros positivos.'; done
mkdir -p "$BACKUP_DIR"; chmod 700 "$BACKUP_DIR"
# FD herdado pelo filho LGPD: não adquire a mesma trava novamente.
if [[ "${MESAMAR_TRAVA_HERDADA:-}" != 1 ]]; then
  exec 9>"$BACKUP_DIR/.operacao.lock"
  flock -n 9 || falha 'Já existe um backup/restauração em andamento.'
  export MESAMAR_TRAVA_HERDADA=1
fi
banco_descartavel() {
  [[ "$1" =~ ^[a-z][a-z0-9_]{0,62}$ ]] || falha 'Nome de banco descartável inválido.'
  case "$1" in "$DB_NAME"|postgres|template0|template1) falha 'Banco protegido não pode ser descartado.';; esac
}
psql_db() { local banco="$1"; shift; "${COMPOSE[@]}" exec -T db psql -U "$DB_USER" -d "$banco" -v ON_ERROR_STOP=1 -X -q -t -A "$@" < /dev/null; }
conferir_banco() { "${COMPOSE[@]}" exec -T db pg_isready -U "$DB_USER" -d "$DB_NAME" < /dev/null > /dev/null || falha 'Banco indisponível.'; }
# Conteúdo de todas as colunas, com ordenação canônica e hash por tabela. Não emite dados pessoais no log.
fingerprint() {
  local banco="$1" destino="$2" snapshot="${3:-}" tabela hash linhas
  local inicio='BEGIN ISOLATION LEVEL REPEATABLE READ;'
  [[ -z "$snapshot" ]] || inicio+=" SET TRANSACTION SNAPSHOT '$snapshot';"
  local tabelas
  tabelas=$(psql_db "$banco" -c "$inicio SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename; COMMIT;")
  : > "$destino"
  while read -r tabela; do
    [[ "$tabela" =~ ^[a-z][a-z0-9_]+$ ]] || falha 'Tabela inesperada no esquema.'
    linhas=$(psql_db "$banco" -c "$inicio SELECT count(*) FROM \"$tabela\"; COMMIT;")
    hash=$(psql_db "$banco" -c "$inicio SET LOCAL timezone='UTC'; COPY (SELECT to_jsonb(t)::text FROM \"$tabela\" t ORDER BY to_jsonb(t)::text COLLATE \"C\") TO STDOUT; COMMIT;" | sha256sum | cut -d' ' -f1)
    printf '%s\t%s\t%s\n' "$tabela" "$linhas" "$hash" >> "$destino"
  done <<< "$tabelas"
}
financeiro() {
  local banco="$1" snapshot="${3:-}" inicio='BEGIN ISOLATION LEVEL REPEATABLE READ;'
  [[ -z "$snapshot" ]] || inicio+=" SET TRANSACTION SNAPSHOT '$snapshot';"
  psql_db "$banco" -c "$inicio SELECT count(*),coalesce(sum(valor_centavos),0),coalesce(sum(valor_recebido_centavos),0) FROM pagamento; COMMIT;" > "$2"
  psql_db "$banco" -c "$inicio SELECT count(*) FROM pg_constraint WHERE connamespace='public'::regnamespace AND NOT convalidated; COMMIT;" >> "$2"
}
gerar_dump() {
  local destino="$1" parcial snapshot
  parcial=$(mktemp "${destino}.parcial.XXXXXX")
  # O dump e os hashes enxergam o MESMO snapshot, mesmo com vendas em andamento.
  coproc SNAP { "${COMPOSE[@]}" exec -T db psql -U "$DB_USER" -d "$DB_NAME" -X -q -t -A -v ON_ERROR_STOP=1; }
  local snap_pid="$SNAP_PID" snap_in="${SNAP[1]}" snap_out="${SNAP[0]}"
  printf 'BEGIN ISOLATION LEVEL REPEATABLE READ;\nSELECT pg_export_snapshot();\n' >&"$snap_in"
  read -r snapshot <&"$snap_out"
  [[ "$snapshot" =~ ^[0-9A-Fa-f-]+$ ]] || falha 'Snapshot inválido.'
  if ! "${COMPOSE[@]}" exec -T db pg_dump -U "$DB_USER" -d "$DB_NAME" --format=custom --compress=6 --snapshot="$snapshot" > "$parcial"; then
    rm -f "$parcial"; kill "$snap_pid" || true; falha 'pg_dump falhou.'
  fi
  fingerprint "$DB_NAME" "$destino.tabelas" "$snapshot"
  financeiro "$DB_NAME" "$destino.financeiro" "$snapshot"
  printf 'COMMIT;\n\\q\n' >&"$snap_in"; wait "$snap_pid"
  "${COMPOSE[@]}" exec -T db pg_restore --list < "$parcial" > /dev/null
  mv "$parcial" "$destino"
  (cd "$(dirname "$destino")"; sha256sum "$(basename "$destino")" "$(basename "$destino").tabelas" "$(basename "$destino").financeiro" > "$(basename "$destino").sha256")
}
validar_arquivo() {
  local arquivo="$1"
  for sufixo in '' .tabelas .financeiro .sha256; do [[ -s "$arquivo$sufixo" ]] || falha 'Dump/manifesto ausente ou vazio.'; done
  # Manifesto só pode apontar os três arquivos esperados, sem caminhos arbitrários.
  python3 "$(dirname "${BASH_SOURCE[0]}")/verificar-dump.py" "$arquivo" || falha 'Checksum do dump/manifesto inválido.'
  "${COMPOSE[@]}" exec -T db pg_restore --list < "$arquivo" > /dev/null || falha 'Dump ilegível.'
}
criar_banco() {
  banco_descartavel "$1"
  psql_db postgres -c "CREATE DATABASE $1" > /dev/null
  if [[ -n "$DB_MIGRATION_USER" ]]; then "${COMPOSE[@]}" exec -T db psql -U "$DB_USER" -d "$1" -f /opt/mesamar-db/permissoes.sql > /dev/null; fi
}
restaurar_validado() {
  local arquivo="$1" banco="$2" argumentos=() dir
  validar_arquivo "$arquivo"
  criar_banco "$banco"
  [[ -z "$DB_MIGRATION_USER" ]] || argumentos+=(--role "$DB_MIGRATION_USER")
  "${COMPOSE[@]}" exec -T db pg_restore -U "$DB_USER" -d "$banco" --no-owner --no-privileges --exit-on-error "${argumentos[@]}" < "$arquivo"
  dir=$(mktemp -d "$BACKUP_DIR/verificacao.XXXXXX")
  fingerprint "$banco" "$dir/tabelas"
  financeiro "$banco" "$dir/financeiro"
  cmp -s "$arquivo.tabelas" "$dir/tabelas" && cmp -s "$arquivo.financeiro" "$dir/financeiro" || falha "Conteúdo financeiro/integridade diverge; banco $banco não deve ser ativado."
  rm -f "$dir/tabelas" "$dir/financeiro"; rmdir "$dir"
}
configurar_restic() {
  command -v restic > /dev/null || falha 'Instale Restic 0.19.1 ou versão compatível validada.'
  [[ "$RESTIC_REPOSITORY" == s3:https://* ]] || falha 'Configure destino S3 HTTPS externo em RESTIC_REPOSITORY.'
  [[ -s "$RESTIC_PASSWORD_FILE" ]] || falha 'Arquivo da chave Restic ausente.'
  [[ -s "$AWS_ARQUIVO" ]] || falha 'Arquivo de credenciais S3 ausente.'
  # Arquivo JSON, lido como dados, não como comandos de shell. Nenhum segredo vai ao log.
  mapfile -t credenciais < <(python3 -c 'import json,sys; c=json.load(open(sys.argv[1])); print(c["accessKeyId"]); print(c["secretAccessKey"])' "$AWS_ARQUIVO")
  [[ ${#credenciais[@]} == 2 && -n "${credenciais[0]}" && -n "${credenciais[1]}" ]] || falha 'Credenciais S3 incompletas.'
  export AWS_ACCESS_KEY_ID="${credenciais[0]}" AWS_SECRET_ACCESS_KEY="${credenciais[1]}"
  unset credenciais
}
