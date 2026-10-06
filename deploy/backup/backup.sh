#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/comum.sh"
conferir_banco
inicio=$(date +%s)
# A idade de uma cópia é a dos dados dela: o instante antes do dump, não o fim da verificação e do envio ao S3.
dados_em=$(date -u +%FT%TZ)
arquivo="$BACKUP_DIR/mesamar-$(date -u +%Y%m%dT%H%M%S)-$(cat /proc/sys/kernel/random/uuid).dump"
gerar_dump "$arquivo"
# Só considera sucesso operacional depois de recuperar o conteúdo completo num banco temporário.
verificacao="mesamar_verificacao_$(cat /proc/sys/kernel/random/uuid | tr -d - | cut -c1-16)"
banco_descartavel "$verificacao"
trap 'psql_db postgres -c "DROP DATABASE IF EXISTS $verificacao" > /dev/null 2>&1 || true' EXIT
restaurar_validado "$arquivo" "$verificacao"
psql_db postgres -c "DROP DATABASE $verificacao" > /dev/null
# Cada backup validado vai para o S3; não há janela semanal diferente do RPO diário.
configurar_restic
fontes=("$arquivo" "$arquivo.sha256" "$arquivo.tabelas" "$arquivo.financeiro")
[[ ! -f "$MESAMAR_LGPD_REGISTRO" ]] || fontes+=("$MESAMAR_LGPD_REGISTRO")
timeout 3600 restic backup --tag mesamar --tag "rpo-${RPO_SEGUNDOS}" "${fontes[@]}" > /dev/null
timeout 3600 restic check --read-data-subset=1/7 > /dev/null
# Agrupar toda a tag mesamar: trocar de VPS/RPO não conserva um grupo antigo indefinidamente.
# Rotação externa antes da local. Precisa das credenciais de manutenção autorizadas para prune.
timeout 3600 restic forget --tag mesamar --group-by "" --keep-within "${BACKUP_DIAS}d" --prune > /dev/null
timeout 3600 restic check --read-data-subset=1/7 > /dev/null
# Retenção só remove conjuntos completos e somente após sucesso remoto.
while IFS= read -r -d '' antigo; do rm -f "$antigo" "$antigo.sha256" "$antigo.tabelas" "$antigo.financeiro"; done < <(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'mesamar-*.dump' -mmin "+$((BACKUP_DIAS * 1440))" -print0)
printf '%s\n' "$dados_em" > "$BACKUP_DIR/ultima-copia-externa.txt"
if [[ -n "$BACKUP_PING" ]]; then curl -fsS -m 15 --retry 3 -o /dev/null "$BACKUP_PING"; fi
log "Backup local e externo verificados; retenção ${BACKUP_DIAS} dias; duração $(( $(date +%s) - inicio )) segundos."
