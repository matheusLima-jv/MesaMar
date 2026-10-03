-- Execute no banco de treino após restaurar histórico representativo, nunca injete dados na produção.
-- psql -v barraca_id=UUID -v inicio='2026-10-03T03:00:00Z' -v fim='2026-10-04T03:00:00Z' -f medir-consultas.sql
\timing on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '15s';
SET LOCAL lock_timeout = '3s';
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT metodo, SUM(valor_centavos), COUNT(*) FROM pagamento
WHERE barraca_id = :'barraca_id'::uuid AND confirmado_em >= :'inicio'::timestamptz AND confirmado_em < :'fim'::timestamptz
GROUP BY metodo;
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT id, saldo_perdoado_centavos FROM comanda
WHERE barraca_id = :'barraca_id'::uuid AND fechada_em >= :'inicio'::timestamptz AND fechada_em < :'fim'::timestamptz
    AND saldo_perdoado_centavos IS NOT NULL
ORDER BY fechada_em, id LIMIT 100;
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT id FROM pedido WHERE barraca_id = :'barraca_id'::uuid AND dia = (:'inicio'::timestamptz AT TIME ZONE 'America/Sao_Paulo')::date;
ROLLBACK;
