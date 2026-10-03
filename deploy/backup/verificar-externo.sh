#!/usr/bin/env bash
# Verificação completa semanal, separada da amostragem diária.
set -euo pipefail
source "$(dirname "$0")/comum.sh"
configurar_restic
timeout "$RTO_SEGUNDOS" restic check --read-data
log 'Todos os blocos da cópia externa foram verificados.'
