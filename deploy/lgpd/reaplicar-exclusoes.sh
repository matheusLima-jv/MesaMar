#!/usr/bin/env bash
# Refaz todos os pedidos de exclusão já atendidos (o registro de excluir-cliente.sh) num banco. O restaurar.sh chama
# este script logo depois de restaurar uma cópia: sem isso, uma cópia anterior ao pedido traria os dados de volta.
#
#   deploy/lgpd/reaplicar-exclusoes.sh [BANCO]      (padrão: o banco de produção)
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/comum.sh"

banco="${1:-$DB_NAME}"
[[ "$banco" =~ ^[a-z0-9_]+$ ]] || falha "nome de banco inválido: $banco"
if [ ! -s "$LGPD_REGISTRO" ]; then
  log "nenhum pedido de exclusão registrado em $LGPD_REGISTRO (nada a reaplicar)"
  exit 0
fi
total=0
while IFS=$'\t' read -r cliente _data; do
  [ -n "$cliente" ] || continue
  uuid_valido "$cliente" || { log "linha ignorada no registro: $cliente"; continue; }
  anonimizar_cliente "$cliente" "$banco"
  total=$((total + 1))
done < "$LGPD_REGISTRO"
log "exclusões reaplicadas em '$banco': $total"
