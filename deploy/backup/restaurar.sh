#!/usr/bin/env bash
# Restaura um backup do MesaMar.
#
#   deploy/backup/restaurar.sh /opt/mesamar/backups/mesamar-2026-10-01_0315.dump
#       Restaura num banco de TREINO ("mesamar_restauracao"), ao lado do de produção, sem tocar nele. Serve para
#       conferir que o backup abre e olhar os dados. Para apagar o banco de treino: restaurar.sh --limpar
#
#   deploy/backup/restaurar.sh ARQUIVO --producao
#       RECUPERAÇÃO DE DESASTRE: para a aplicação, APAGA o banco de produção, restaura o backup e sobe a aplicação de
#       novo. Pede que você digite RESTAURAR. Tudo que entrou depois do backup se perde.
set -euo pipefail
# shellcheck source=comum.sh
source "$(cd "$(dirname "$0")" && pwd)/comum.sh"

TREINO="${MESAMAR_BANCO_TREINO:-mesamar_restauracao}"
banco_descartavel "$TREINO"

recriar_banco() {
  local banco="$1"
  [[ "$banco" =~ ^[a-z0-9_]+$ ]] || falha "nome de banco inválido: $banco"
  # Derruba quem estiver conectado (a aplicação, se for a produção) para o DROP não ficar esperando
  psql_db postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$banco' AND pid <> pg_backend_pid()" > /dev/null
  psql_db postgres -c "DROP DATABASE IF EXISTS $banco" -c "CREATE DATABASE $banco" > /dev/null
}

restaurar_em() {
  local arquivo="$1" banco="$2"
  log "restaurando $arquivo em '$banco'…"
  # --no-owner: o dono vira o usuário da conexão (o do banco de destino), sem depender de papéis do servidor antigo
  $COMPOSE exec -T db pg_restore -U "$DB_USER" -d "$banco" --no-owner --no-privileges --exit-on-error < "$arquivo" \
    || falha "o pg_restore falhou. O banco '$banco' pode estar incompleto: não use."
  log "restauração concluída em '$banco'"
}

if [ "${1:-}" = "--limpar" ]; then
  conferir_banco
  psql_db postgres -c "DROP DATABASE IF EXISTS $TREINO" > /dev/null
  log "banco de treino '$TREINO' apagado"
  exit 0
fi

arquivo="${1:-}"
[ -n "$arquivo" ] || falha "uso: restaurar.sh ARQUIVO.dump [--producao]   (ou: restaurar.sh --limpar)"
[ -s "$arquivo" ] || falha "arquivo não encontrado ou vazio: $arquivo"
conferir_banco

if [ "${2:-}" != "--producao" ]; then
  recriar_banco "$TREINO"
  restaurar_em "$arquivo" "$TREINO"
  log "pronto. Para olhar: $COMPOSE exec db psql -U $DB_USER -d $TREINO   (e, ao terminar: restaurar.sh --limpar)"
  exit 0
fi

echo "ATENÇÃO: isto vai APAGAR o banco de produção '$DB_NAME' e trocá-lo pelo conteúdo de:"
echo "  $arquivo"
echo "A aplicação ficará fora do ar durante a restauração. O que entrou depois do backup será perdido."
read -r -p "Para continuar, digite RESTAURAR: " confirmacao
[ "$confirmacao" = "RESTAURAR" ] || falha "cancelado."

log "parando a aplicação…"
$COMPOSE stop app
recriar_banco "$DB_NAME"
restaurar_em "$arquivo" "$DB_NAME"
log "subindo a aplicação…"
$COMPOSE start app
log "pronto. Confira https://SEU-DOMINIO/actuator/health e abra o /admin e um painel de dono."
