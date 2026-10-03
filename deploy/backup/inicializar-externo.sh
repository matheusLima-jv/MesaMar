#!/usr/bin/env bash
# Inicialização única, usando exatamente a configuração do backup.
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/comum.sh"
configurar_restic
timeout 120 restic init > /dev/null
log 'Repositório externo criptografado inicializado. Preserve a senha fora da VPS.'
