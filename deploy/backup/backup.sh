#!/usr/bin/env bash
# Backup do PostgreSQL do MesaMar: pg_dump comprimido, conferido, com rotação de 14 dias.
#
#   deploy/backup/backup.sh
#
# Roda todo dia pelo cron (ver docs/deploy.md). Guarda os dumps em MESAMAR_BACKUP_DIR (padrão /opt/mesamar/backups),
# apaga os de mais de MESAMAR_BACKUP_DIAS dias (padrão 14) e, se MESAMAR_BACKUP_PING_URL estiver definida (ex.: um
# monitor do tipo "healthchecks"), avisa que o backup deu certo, para você ser alertado quando ele PARAR de rodar.
#
# Sai com código diferente de zero se qualquer passo falhar (o cron/monitor percebe).
set -euo pipefail
# shellcheck source=comum.sh
source "$(cd "$(dirname "$0")" && pwd)/comum.sh"

conferir_banco
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

arquivo="$BACKUP_DIR/mesamar-$(date +%F_%H%M).dump"
gerar_dump "$arquivo"
chmod 600 "$arquivo"
tamanho=$(du -h "$arquivo" | cut -f1)
log "backup ok: $arquivo ($tamanho)"

# Rotação: apaga o que passou do prazo (só arquivos com o nosso nome, nunca outra coisa que esteja na pasta)
apagados=$(find "$BACKUP_DIR" -maxdepth 1 -name 'mesamar-*.dump' -mtime "+$((BACKUP_DIAS - 1))" -print -delete | wc -l)
restantes=$(find "$BACKUP_DIR" -maxdepth 1 -name 'mesamar-*.dump' | wc -l)
log "rotação: $apagados apagado(s) com mais de $BACKUP_DIAS dias; $restantes backup(s) guardado(s)"

if [ -n "${MESAMAR_BACKUP_PING_URL:-}" ]; then
  if curl -fsS -m 15 --retry 3 -o /dev/null "$MESAMAR_BACKUP_PING_URL"; then
    log "monitor avisado"
  else
    log "AVISO: não consegui avisar o monitor"
  fi
fi
