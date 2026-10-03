#!/usr/bin/env bash
# Atende um pedido de EXCLUSÃO de dados (ou retirada de consentimento) de um cliente de barraca: anonimiza na hora e
# registra o pedido, para ser reaplicado se o banco for restaurado de uma cópia antiga.
#
#   deploy/lgpd/excluir-cliente.sh --comanda UUID
#   deploy/lgpd/excluir-cliente.sh --link https://mesamar.0mega.fun/c/<token>
#   deploy/lgpd/excluir-cliente.sh --whatsapp +5513999998888     (pode haver mais de um cliente com o mesmo número)
#   (acrescente --sim para não pedir confirmação)
#
# Procedimento completo, com o que conferir depois: docs/lgpd-procedimento.md
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/comum.sh"

modo="" valor="" sim=""
while [ $# -gt 0 ]; do
  case "$1" in
    --comanda | --link | --whatsapp) modo="$1"; valor="${2:-}"; shift 2 ;;
    --sim) sim=1; shift ;;
    *) falha "opção desconhecida: $1 (veja o começo deste arquivo)" ;;
  esac
done
[ -n "$modo" ] && [ -n "$valor" ] || falha "uso: excluir-cliente.sh --comanda UUID | --link URL | --whatsapp +55DDDNUMERO"
conferir_banco

# Os valores são validados (só dígitos, letras, _ e -) antes de entrar no SQL
case "$modo" in
  --comanda)
    uuid_valido "$valor" || falha "UUID de comanda inválido."
    filtro="co.id = '$valor'" ;;
  --link)
    token="${valor##*/c/}"
    token="${token%%[?#/]*}"
    [[ "$token" =~ ^[A-Za-z0-9_-]{16,64}$ ]] || falha "não achei o código do link (o final de /c/<código>)."
    filtro="(co.token_acesso = '$token' OR co.token_anterior = '$token')" ;;
  --whatsapp)
    [[ "$valor" =~ ^\+[0-9]{10,15}$ ]] || falha "WhatsApp no formato +5513999998888."
    filtro="cl.whatsapp_e164 = '$valor'" ;;
esac

encontrados=$(psql_db "$DB_NAME" -F ' | ' -c "
  SELECT DISTINCT cl.id, cl.nome, 'barraca ' || b.slug, 'dia ' || co.dia
  FROM comanda co JOIN cliente cl ON cl.id = co.cliente_id JOIN barraca b ON b.id = co.barraca_id
  WHERE $filtro ORDER BY 1")
[ -n "$encontrados" ] || falha "nenhum cliente encontrado com esse dado."
echo "Clientes encontrados (id | nome | barraca | dia):"
echo "$encontrados"

if [ -z "$sim" ]; then
  read -r -p "Anonimizar TODOS os clientes acima? Isto não tem volta. Digite EXCLUIR: " confirmacao
  [ "$confirmacao" = "EXCLUIR" ] || falha "cancelado."
fi

mkdir -p "$(dirname "$LGPD_REGISTRO")"
touch "$LGPD_REGISTRO"
chmod 600 "$LGPD_REGISTRO"
while IFS= read -r linha; do
  cliente="${linha%% *}"
  uuid_valido "$cliente" || continue
  anonimizar_cliente "$cliente"
  printf '%s\t%s\n' "$cliente" "$(date '+%F')" >> "$LGPD_REGISTRO"
  log "cliente $cliente anonimizado e registrado em $LGPD_REGISTRO"
done <<< "$encontrados"

cat <<'FIM'

Feito no banco. Falta conferir o resto (docs/lgpd-procedimento.md):
  1. Responder a pessoa, dizendo o que foi feito e o que fica (valores dos pedidos, sem identificar).
  2. Cache no celular dela: o link antigo agora dá "não vale mais", mas uma cópia salva no navegador só some quando ela
     limpar os dados do site (ou em 7 dias sem uso). Avise.
  3. Logs: nenhum log da aplicação tem nome ou WhatsApp; o do Nginx mascara o link. Nada a apagar.
  4. Backups: as cópias antigas ainda têm os dados até vencerem (14 dias). O registro acima garante que, se uma delas
     for restaurada, a exclusão é refeita (restaurar.sh chama reaplicar-exclusoes.sh).
FIM
