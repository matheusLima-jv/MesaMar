#!/usr/bin/env bash
# Atende um pedido de CORREÇÃO de dados: troca o nome e/ou o WhatsApp do cliente de uma comanda.
#
#   deploy/lgpd/corrigir-cliente.sh --comanda UUID [--nome "Nome certo"] [--whatsapp +5513999998888 | --whatsapp apagar]
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/comum.sh"

comanda="" nome="" zap="" tem_nome="" tem_zap=""
while [ $# -gt 0 ]; do
  case "$1" in
    --comanda) comanda="${2:-}"; shift 2 ;;
    --nome) nome="${2:-}"; tem_nome=1; shift 2 ;;
    --whatsapp) zap="${2:-}"; tem_zap=1; shift 2 ;;
    *) falha "opção desconhecida: $1" ;;
  esac
done
uuid_valido "$comanda" || falha "uso: corrigir-cliente.sh --comanda UUID [--nome ...] [--whatsapp ...]"
[ -n "$tem_nome$tem_zap" ] || falha "diga o que corrigir: --nome e/ou --whatsapp"
conferir_banco

sets=()
if [ -n "$tem_nome" ]; then
  nome=$(printf '%s' "$nome" | tr -s '[:space:]' ' ' | sed 's/^ //; s/ $//')
  [ -n "$nome" ] && [ "${#nome}" -le 40 ] || falha "o nome precisa ter de 1 a 40 letras."
  sets+=("nome = '${nome//\'/\'\'}'")
fi
if [ -n "$tem_zap" ]; then
  if [ "$zap" = "apagar" ]; then
    sets+=("whatsapp_e164 = NULL")
  else
    [[ "$zap" =~ ^\+[0-9]{10,15}$ ]] || falha "WhatsApp no formato +5513999998888 (ou 'apagar')."
    sets+=("whatsapp_e164 = '$zap'")
  fi
fi
lista=$(IFS=,; echo "${sets[*]}")
linhas=$(psql_db "$DB_NAME" -c "UPDATE cliente SET $lista WHERE anonimizado_em IS NULL AND id = (SELECT cliente_id FROM comanda WHERE id = '$comanda') RETURNING id")
[ -n "$linhas" ] || falha "comanda não encontrada, ou o cliente já foi anonimizado (não há o que corrigir)."
log "dados do cliente $linhas corrigidos"
