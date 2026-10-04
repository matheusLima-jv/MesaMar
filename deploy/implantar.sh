#!/usr/bin/env bash
# Implantação/rollback manual; a imagem saudável passa a ser a configuração persistente.
set -euo pipefail
source "$(dirname "$0")/backup/comum.sh"
imagem="${1:-}"
[[ "$imagem" =~ ^ghcr.io/[a-z0-9_/.-]+@sha256:[a-f0-9]{64}$ ]] || falha 'Use a imagem por digest do artefato versao-validada.json.'
docker pull "$imagem"
container=$(docker create "$imagem")
dir=$(mktemp -d "$BACKUP_DIR/compatibilidade.XXXXXX")
env_temporario=''
trap 'docker rm "$container" > /dev/null; rm -f "$dir/compatibilidade.json"; rmdir "$dir"; [[ -z "$env_temporario" ]] || rm -f "$env_temporario"' EXIT
docker cp "$container:/app/compatibilidade.json" "$dir/compatibilidade.json" || falha 'Imagem não declara compatibilidade; rollback bloqueado.'
if [[ "$(psql_db "$DB_NAME" -c "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")" == t ]]; then
  atual=$(psql_db "$DB_NAME" -c 'SELECT coalesce(max(version::int),0) FROM flyway_schema_history WHERE success')
else
  [[ "$(psql_db "$DB_NAME" -c "SELECT count(*) FROM pg_tables WHERE schemaname='public'")" == 0 ]] || falha 'Banco existente sem histórico de migração; ensaie a atualização antes de implantar.'
  atual=0
fi
python3 - "$dir/compatibilidade.json" "$atual" <<'PY'
import json,sys
c=json.load(open(sys.argv[1])); atual=int(sys.argv[2])
if c.get('versao') != 1 or not ((atual == 0 and c.get('permiteBancoVazio') is True) or c['schemaOrigemMinimo'] <= atual <= c['schemaMaximo']):
    raise SystemExit('Esquema incompatível. Use recuperação com backup anterior validado; não altere Flyway manualmente.')
PY
# Só a migração usa a credencial administrativa de DDL. O app usa a conta de execução.
env_arquivo=$(realpath "${MESAMAR_ENV_FILE:-.env}")
env_temporario=$(python3 "$(dirname "$0")/atualizar-imagem.py" "$env_arquivo" "$imagem")
export MESAMAR_IMAGE="$imagem"
"${COMPOSE[@]}" stop app
"${COMPOSE[@]}" run --rm migrate
posterior=$(psql_db "$DB_NAME" -c 'SELECT max(version::int) FROM flyway_schema_history WHERE success')
python3 - "$dir/compatibilidade.json" "$posterior" <<'PY'
import json,sys
c=json.load(open(sys.argv[1])); v=int(sys.argv[2])
if not c['schemaMinimo'] <= v <= c['schemaMaximo']: raise SystemExit('Esquema resultante incompatível; mantenha o app parado e execute o plano de recuperação.')
PY
"${COMPOSE[@]}" up -d --no-deps --wait --wait-timeout 180 app
mv -f "$env_temporario" "$env_arquivo"
env_temporario=''
printf '%s\n' "$imagem" > "$BACKUP_DIR/imagem-implantada.txt"
log 'Imagem validada implantada e persistida na .env; confira os fluxos e /actuator/info.'
