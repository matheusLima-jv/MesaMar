#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/comum.sh"
conferir_banco
inicio=$(date +%s)
teste="mesamar_teste_$(cat /proc/sys/kernel/random/uuid | tr -d - | cut -c1-16)"
arquivo="${1:-}"
dir=''
limpar() { psql_db postgres -c "DROP DATABASE IF EXISTS $teste" > /dev/null 2>&1 || true; if [[ -n "$dir" ]]; then find "$dir" -maxdepth 1 -type f -delete; rmdir "$dir"; fi; }
trap limpar EXIT
if [[ -z "$arquivo" ]]; then dir=$(mktemp -d "$BACKUP_DIR/ensaio.XXXXXX"); arquivo="$dir/teste.dump"; gerar_dump "$arquivo"; fi
restaurar_validado "$arquivo" "$teste"
duracao=$(( $(date +%s) - inicio ))
(( duracao <= RTO_SEGUNDOS )) || falha "RTO ultrapassado: $duracao segundos."
printf '{"duracaoSegundos":%s,"rtoSegundos":%s,"rpoSegundos":%s,"conteudoIdentico":true}\n' "$duracao" "$RTO_SEGUNDOS" "$RPO_SEGUNDOS" > "$BACKUP_DIR/ultimo-ensaio.json"
log "RESTAURAÇÃO OK: hashes de todos os valores, totais financeiros e restrições conferidos; ${duracao}s / meta ${RTO_SEGUNDOS}s."
