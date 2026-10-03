#!/usr/bin/env bash
# Ensaio isolado, executado SOMENTE no container descartável do CI/local.
set -euo pipefail
[[ "${MESAMAR_OPERACAO_TESTE:-}" == 1 ]] || { echo 'Exige MESAMAR_OPERACAO_TESTE=1 num container descartável'; exit 1; }
raiz="${1:-/workspace}"
admin="${POSTGRES_USER:-postgres}"
origem="${POSTGRES_DB:-mesamar}"
clonado="ensaio_$(cat /proc/sys/kernel/random/uuid | tr -d - | cut -c1-12)"
pg_dump -U "$admin" -d "$origem" -Fc > /tmp/entrada-ensaio.dump
createdb -U "$admin" "$clonado"
pg_restore -U "$admin" -d "$clonado" --no-owner --no-privileges --exit-on-error < /tmp/entrada-ensaio.dump
export POSTGRES_DB="$clonado" POSTGRES_USER="$admin" MESAMAR_BACKUP_DIR="/tmp/mesamar-ensaio-$clonado"
mkdir -p "$MESAMAR_BACKUP_DIR"
# Adapter só substitui o transporte Compose; pg_dump/restore/psql são os binários reais do PostgreSQL.
docker() {
  shift
  case "$1" in
    exec) shift 3; "$@";;
    config) printf '{"x-operacao":{"diretorio":"%s","dias":"%s"}}\n' "$MESAMAR_BACKUP_DIR" "${MESAMAR_BACKUP_DIAS:-14}";;
    stop) touch "$MESAMAR_BACKUP_DIR/app-parado";;
    up) if [[ "${MESAMAR_FALHA_SUBIDA:-}" == 1 && "$*" == *--wait* ]]; then return 1; fi; touch "$MESAMAR_BACKUP_DIR/app-subiu";;
    *) echo 'Comando inesperado no adapter' >&2; return 1;;
  esac
}
export -f docker
source "$raiz/deploy/backup/comum.sh"
limpar() {
  psql -U "$admin" -d postgres -Atc "SELECT datname FROM pg_database WHERE datname LIKE 'mesamar_teste_%' OR datname LIKE 'mesamar_recuperacao_%' OR datname LIKE 'mesamar_resgate_%' OR datname='$clonado'" |
    while read -r db; do dropdb -U "$admin" "$db" || true; done
}
trap limpar EXIT
bash "$raiz/deploy/backup/testar-restauracao.sh"
arquivo="$MESAMAR_BACKUP_DIR/prova.dump"
gerar_dump "$arquivo"
validar_arquivo "$arquivo"
for dias in 0 -1 abc 08 91; do
  if MESAMAR_BACKUP_DIAS="$dias" bash "$raiz/deploy/backup/testar-restauracao.sh" "$arquivo" >/dev/null 2>&1; then echo 'Retenção inválida foi aceita'; exit 1; fi
done
if env -u MESAMAR_TRAVA_HERDADA bash "$raiz/deploy/backup/testar-restauracao.sh" "$arquivo" >/dev/null 2>&1; then echo 'Trava concorrente falhou'; exit 1; fi
cp "$arquivo" "$arquivo.original"
python3 - "$arquivo" <<'PY'
import sys
with open(sys.argv[1], 'r+b') as f:
    f.seek(-1,2); b=f.read(1); f.seek(-1,2); f.write(bytes([b[0]^1]))
PY
if bash "$raiz/deploy/backup/restaurar.sh" "$arquivo" --producao >/dev/null 2>&1; then echo 'Dump corrompido aceito'; exit 1; fi
[[ ! -e "$MESAMAR_BACKUP_DIR/app-parado" ]] || { echo 'Aplicação parou antes da validação'; exit 1; }
mv "$arquivo.original" "$arquivo"
# Mesmo número de pagamentos, valores diferentes: o manifesto deve detectar a diferença.
cp "$arquivo.financeiro" "$arquivo.financeiro.original"
printf 'totais-financeiros-incorretos\n' > "$arquivo.financeiro"
(cd "$MESAMAR_BACKUP_DIR"; sha256sum prova.dump prova.dump.tabelas prova.dump.financeiro > prova.dump.sha256)
if bash "$raiz/deploy/backup/testar-restauracao.sh" "$arquivo" >/dev/null 2>&1; then echo 'Valores financeiros divergentes aceitos'; exit 1; fi
mv "$arquivo.financeiro.original" "$arquivo.financeiro"
(cd "$MESAMAR_BACKUP_DIR"; sha256sum prova.dump prova.dump.tabelas prova.dump.financeiro > prova.dump.sha256)
# Falha ao subir após a troca: o banco original deve voltar automaticamente.
antes=$(psql_db "$clonado" -c "SELECT value FROM app_metadata WHERE key='schema_baseline'")
if printf 'RESTAURAR\n' | MESAMAR_FALHA_SUBIDA=1 bash "$raiz/deploy/backup/restaurar.sh" "$arquivo" --producao; then echo 'Falha injetada não ocorreu'; exit 1; fi
[[ "$(psql_db "$clonado" -c "SELECT value FROM app_metadata WHERE key='schema_baseline'")" == "$antes" && -e "$MESAMAR_BACKUP_DIR/app-subiu" ]] || { echo 'Banco anterior não foi recuperado'; exit 1; }
echo 'ENSAIO OK: conteúdo, finanças, checksum, retenção, exclusão mútua e falha de ativação.'
