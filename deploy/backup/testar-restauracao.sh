#!/usr/bin/env bash
# TESTE DE RESTAURAÇÃO: prova que o backup serve. Gera um dump novo (ou usa o arquivo que você passar), restaura num banco
# descartável e compara, tabela por tabela, a quantidade de linhas com o banco de verdade. No fim, apaga o banco descartável.
# Não mexe no banco de produção (só lê).
#
#   deploy/backup/testar-restauracao.sh                      # dump novo
#   deploy/backup/testar-restauracao.sh ARQUIVO.dump         # testa um backup já guardado
#
# O CI roda isto contra o banco do E2E, depois dos testes, a cada push.
#
# Na VPS, rode fora do horário de movimento: se alguém fizer um pedido entre o dump e a conferência, as tabelas movimentadas
# (pedido, item_pedido, pagamento) podem diferir em algumas linhas, e o teste acusa. Se acusar só isso, rode de novo.
set -euo pipefail
# shellcheck source=comum.sh
source "$(cd "$(dirname "$0")" && pwd)/comum.sh"

TESTE="${MESAMAR_BANCO_TESTE:-mesamar_teste_restauracao}"
conferir_banco

temporario=""
limpar() {
  psql_db postgres -c "DROP DATABASE IF EXISTS $TESTE" > /dev/null 2>&1 || true
  [ -z "$temporario" ] || rm -rf "$temporario"
}
trap limpar EXIT

arquivo="${1:-}"
if [ -z "$arquivo" ]; then
  temporario="$(mktemp -d)"
  arquivo="$temporario/teste.dump"
  gerar_dump "$arquivo"
  log "dump novo gerado ($(du -h "$arquivo" | cut -f1))"
fi
[ -s "$arquivo" ] || falha "arquivo não encontrado ou vazio: $arquivo"

psql_db postgres -c "DROP DATABASE IF EXISTS $TESTE" -c "CREATE DATABASE $TESTE" > /dev/null
$COMPOSE exec -T db pg_restore -U "$DB_USER" -d "$TESTE" --no-owner --no-privileges --exit-on-error < "$arquivo" \
  || falha "o pg_restore falhou: ESTE BACKUP NÃO SERVE."
log "restaurado no banco descartável '$TESTE'"

lista_tabelas="SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY 1"
tabelas=$(psql_db "$DB_NAME" -c "$lista_tabelas")
restauradas=$(psql_db "$TESTE" -c "$lista_tabelas")
[ "$tabelas" = "$restauradas" ] || falha "as tabelas do banco restaurado não são as mesmas. Original: $(echo "$tabelas" | tr '\n' ' ') | Restaurado: $(echo "$restauradas" | tr '\n' ' ')"
[ -n "$tabelas" ] || falha "o banco não tem nenhuma tabela: nada para conferir."

versao_origem=$(psql_db "$DB_NAME" -c "SELECT max(version::int) FROM flyway_schema_history WHERE success")
versao_restaurada=$(psql_db "$TESTE" -c "SELECT max(version::int) FROM flyway_schema_history WHERE success")
[ "$versao_origem" = "$versao_restaurada" ] || falha "a versão do esquema difere: original V$versao_origem, restaurado V$versao_restaurada."

erros=0
total_linhas=0
total_tabelas=0
while read -r tabela; do
  [ -n "$tabela" ] || continue
  origem=$(psql_db "$DB_NAME" -c "SELECT count(*) FROM \"$tabela\"")
  destino=$(psql_db "$TESTE" -c "SELECT count(*) FROM \"$tabela\"")
  total_tabelas=$((total_tabelas + 1))
  total_linhas=$((total_linhas + destino))
  if [ "$origem" != "$destino" ]; then
    log "DIFERENÇA em $tabela: original $origem linha(s), restaurado $destino"
    erros=$((erros + 1))
  fi
done <<< "$tabelas"

[ "$erros" -eq 0 ] || falha "$erros tabela(s) com contagem diferente. Se foi só movimento do app entre o dump e a conferência, rode de novo fora do horário."
log "RESTAURAÇÃO OK: $total_tabelas tabelas, $total_linhas linhas, esquema V$versao_restaurada, idêntico ao banco original."
