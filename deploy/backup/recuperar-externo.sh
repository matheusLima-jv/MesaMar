#!/usr/bin/env bash
# Recuperação em uma VPS nova: repositório/chave/credenciais devem estar fora da VPS antiga.
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/comum.sh"
configurar_restic
inicio=$(date +%s)
alvo=$(mktemp -d "$BACKUP_DIR/externo.XXXXXX")
snapshot="${1:-latest}"
timeout "$RTO_SEGUNDOS" restic restore "$snapshot" --tag mesamar --verify --target "$alvo" > /dev/null
mapfile -d '' -t dumps < <(find "$alvo" -type f -name '*.dump' -print0)
[[ ${#dumps[@]} == 1 ]] || falha 'Snapshot deve conter exatamente um dump.'
validar_arquivo "${dumps[0]}"
# Reaplica o registro remoto de exclusões; não sobrescreve o registro mais recente da VPS sobrevivente.
registro=$(find "$alvo" -type f -name "$(basename "$MESAMAR_LGPD_REGISTRO")" -print -quit)
if [[ -n "$registro" ]]; then mkdir -p "$(dirname "$MESAMAR_LGPD_REGISTRO")"; touch "$MESAMAR_LGPD_REGISTRO"; cat "$MESAMAR_LGPD_REGISTRO" "$registro" | sort -u > "$alvo/exclusoes-unidas.txt"; cp "$alvo/exclusoes-unidas.txt" "$MESAMAR_LGPD_REGISTRO"; fi
bash "$(dirname "$0")/testar-restauracao.sh" "${dumps[0]}"
(( $(date +%s) - inicio <= RTO_SEGUNDOS )) || falha 'Ensaio externo excedeu a meta de RTO.'
log "Recuperação externa verificada. Dump: ${dumps[0]}. Use restaurar.sh --producao após conferir."
