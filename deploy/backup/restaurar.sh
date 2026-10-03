#!/usr/bin/env bash
# Valida integralmente em staging; só depois interrompe produção e troca os nomes.
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/comum.sh"
conferir_banco
arquivo="${1:-}"
[[ -n "$arquivo" ]] || falha 'Uso: restaurar.sh ARQUIVO.dump [--producao]'
estagio="mesamar_recuperacao_$(cat /proc/sys/kernel/random/uuid | tr -d - | cut -c1-16)"
restaurar_validado "$arquivo" "$estagio"
bash "$(dirname "$0")/../lgpd/reaplicar-exclusoes.sh" "$estagio"
if [[ "${2:-}" != --producao ]]; then log "Treino validado disponível em $estagio. Apague-o com DROP DATABASE após conferir."; exit 0; fi
printf 'O banco atual será preservado. Dados posteriores ao backup deixam de aparecer.\n'
read -r -p 'Para ativar o backup validado, digite RESTAURAR: ' confirmacao
[[ "$confirmacao" == RESTAURAR ]] || falha "Cancelado; produção intacta, treino $estagio preservado."
anterior="mesamar_resgate_$(cat /proc/sys/kernel/random/uuid | tr -d - | cut -c1-16)"
trocou=0
recuperar() {
  local codigo=$?
  if (( codigo != 0 && trocou == 1 )); then
    "${COMPOSE[@]}" stop app || true
    psql_db postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='$DB_NAME' AND pid<>pg_backend_pid()" > /dev/null || true
    psql_db postgres -c "ALTER DATABASE $DB_NAME RENAME TO $estagio" -c "ALTER DATABASE $anterior RENAME TO $DB_NAME" || { log 'Falha de retorno: aplicação permanece parada. Consulte o procedimento de recuperação.'; exit "$codigo"; }
    "${COMPOSE[@]}" up -d --no-deps app || true
    log "Ativação falhou; banco anterior recolocado. Banco incompleto preservado em $estagio."
  fi
  exit "$codigo"
}
trap recuperar EXIT
"${COMPOSE[@]}" stop app
psql_db postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='$DB_NAME' AND pid<>pg_backend_pid()" > /dev/null
psql_db postgres -c "ALTER DATABASE $DB_NAME RENAME TO $anterior"
# Falha entre renames também recoloca o banco original.
if ! psql_db postgres -c "ALTER DATABASE $estagio RENAME TO $DB_NAME"; then psql_db postgres -c "ALTER DATABASE $anterior RENAME TO $DB_NAME"; "${COMPOSE[@]}" up -d --no-deps app; falha 'Troca cancelada.'; fi
trocou=1
if [[ -n "$DB_MIGRATION_USER" ]]; then "${COMPOSE[@]}" run --rm migrate; fi
"${COMPOSE[@]}" up -d --no-deps --wait --wait-timeout 180 app
log "Produção ativada. Banco anterior preservado em $anterior: retenha protegido por no máximo $BACKUP_DIAS dias; confira dados/health antes de removê-lo."
