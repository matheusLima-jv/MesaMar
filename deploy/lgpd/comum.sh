#!/usr/bin/env bash
# Funções dos scripts de LGPD. Não execute este arquivo: ele é lido pelos outros ("source").
# Usa o mesmo MESAMAR_COMPOSE, DB_USER e DB_NAME dos scripts de backup (ver deploy/backup/comum.sh).
LGPD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../backup/comum.sh
source "$LGPD_DIR/../backup/comum.sh"

# Onde ficam os pedidos de exclusão já atendidos. É um arquivo FORA do banco, de propósito: se o banco for restaurado de
# uma cópia anterior ao pedido, o registro no banco também voltaria no tempo. Só tem UUIDs de cliente e a data (nada
# que identifique a pessoa sozinho). Faça cópia dele junto com a cópia dos backups.
LGPD_REGISTRO="${MESAMAR_LGPD_REGISTRO:-/opt/mesamar/lgpd/exclusoes.txt}"

uuid_valido() {
  [[ "$1" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]
}

# Anonimiza um cliente num banco (padrão: o de produção). $1 = UUID do cliente.
anonimizar_cliente() {
  local cliente="$1" banco="${2:-$DB_NAME}"
  uuid_valido "$cliente" || falha "UUID de cliente inválido: $cliente"
  $COMPOSE exec -T db psql -U "$DB_USER" -d "$banco" -v ON_ERROR_STOP=1 -X -q -v "cliente=$cliente" -f - < "$LGPD_DIR/anonimizar-cliente.sql" > /dev/null \
    || falha "não deu para anonimizar o cliente $cliente em '$banco'."
}
