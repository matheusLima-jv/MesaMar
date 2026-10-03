#!/usr/bin/env bash
# Repositório local descartável: prova o mecanismo cifrado, não o acesso ao S3 real.
set -euo pipefail
umask 077
base=$(mktemp -d /tmp/mesamar-restic.XXXXXX)
trap 'rm -rf "$base"' EXIT
export RESTIC_REPOSITORY="$base/repositorio"
export RESTIC_PASSWORD_FILE="$base/senha"
cat /proc/sys/kernel/random/uuid > "$RESTIC_PASSWORD_FILE"
mkdir "$base/origem"
printf '%s' 'financeiro-de-teste-sem-dados-pessoais' > "$base/origem/contas.txt"
restic init > /dev/null
# O host e as tags mudaram: o snapshot antigo ainda precisa vencer.
antigo=$(python3 -c 'import datetime as d; print((d.datetime.now(d.timezone.utc)-d.timedelta(days=31)).strftime("%Y-%m-%d %H:%M:%S"))')
restic backup --host vps-anterior --time "$antigo" --tag mesamar --tag rpo-86400 "$base/origem" > /dev/null
restic backup --host vps-atual --tag mesamar --tag rpo-43200 "$base/origem" > /dev/null
restic check --read-data > /dev/null
restic restore latest --tag mesamar --verify --target "$base/restaurado" > /dev/null
cmp "$base/origem/contas.txt" "$base/restaurado$base/origem/contas.txt"
if grep -r -F -q 'financeiro-de-teste-sem-dados-pessoais' "$RESTIC_REPOSITORY/data"; then echo 'Conteúdo sem proteção encontrado'; exit 1; fi
if RESTIC_PASSWORD_FILE= RESTIC_PASSWORD=chave-incorreta restic snapshots > /dev/null 2>&1; then exit 1; fi
restic forget --tag mesamar --group-by '' --keep-within 14d --prune > /dev/null
restic check --read-data > /dev/null
restic snapshots --json | python3 -c 'import json,sys; s=json.load(sys.stdin); assert len(s)==1 and s[0]["hostname"]=="vps-atual"'
printf '%s\n' 'RESTIC OK: cópia cifrada, restauração idêntica, senha incorreta recusada e retenção após trocar de host.'
