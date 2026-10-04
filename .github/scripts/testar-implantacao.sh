#!/usr/bin/env bash
# Adapter sem acesso ao Docker: exercita o script real e a persistência após saúde.
set -euo pipefail
[[ "${MESAMAR_OPERACAO_TESTE:-}" == 1 ]] || { echo 'Exige MESAMAR_OPERACAO_TESTE=1'; exit 1; }
raiz="$(cd "$(dirname "$0")/../.." && pwd)"
ensaio=$(mktemp -d /tmp/mesamar-implantacao.XXXXXX)
trap 'rm -rf "$ensaio"' EXIT
export MESAMAR_BACKUP_DIR="$ensaio/backups" MESAMAR_ENV_FILE="$ensaio/config.env" ENSAIO_DIR="$ensaio"
antiga="ghcr.io/teste/mesamar@sha256:$(printf '1%.0s' {1..64})"
nova="ghcr.io/teste/mesamar@sha256:$(printf '2%.0s' {1..64})"
docker() {
  case "$1" in
    pull|rm) return 0;;
    create) printf 'container-ficticio\n';;
    cp) cp "$ENSAIO_DIR/manifesto.json" "$3";;
    compose)
      shift
      while [[ "$1" == -f || "$1" == --env-file ]]; do shift 2; done
      case "$1" in
        exec)
          shift 3
          if [[ "$1" == printenv ]]; then
            case "$2" in POSTGRES_USER) echo admin;; POSTGRES_DB) echo mesamar;; DB_MIGRATION_USER) echo migrador;; esac
          elif [[ "$*" == *to_regclass* ]]; then echo t
          elif [[ -e "$ENSAIO_DIR/migrou" ]]; then echo "${ENSAIO_DESTINO:-21}"
          else echo 20; fi;;
        config) printf '{"x-operacao":{"diretorio":"%s","dias":"14"}}\n' "$MESAMAR_BACKUP_DIR";;
        stop) touch "$ENSAIO_DIR/parou";;
        run) [[ "${ENSAIO_FALHA:-}" != migracao ]] || return 1; touch "$ENSAIO_DIR/migrou";;
        up)
          [[ "${ENSAIO_FALHA:-}" != subida ]] || return 1
          if [[ -n "${MESAMAR_IMAGE:-}" ]]; then printf '%s\n' "$MESAMAR_IMAGE"
          else sed -n 's/^MESAMAR_IMAGE=//p' "$MESAMAR_ENV_FILE"; fi > "$ENSAIO_DIR/imagem-usada";;
        *) return 1;;
      esac;;
    *) return 1;;
  esac
}
export -f docker
cp "$raiz/deploy/compatibilidade.json" "$ensaio/manifesto.json"
for falha in migracao subida esquema nenhuma; do
  printf 'SEGREDO_FICTICIO=literal$sem_execucao\nMESAMAR_IMAGE=%s\n' "$antiga" > "$MESAMAR_ENV_FILE"
  rm -f "$ensaio/migrou" "$ensaio/parou" "$ensaio/imagem-usada"
  export ENSAIO_FALHA="$falha" ENSAIO_DESTINO=21
  [[ "$falha" != esquema ]] || export ENSAIO_DESTINO=22
  if bash "$raiz/deploy/implantar.sh" "$nova"; then
    [[ "$falha" == nenhuma ]] || { echo 'Falha injetada passou'; exit 1; }
    grep -qxF "MESAMAR_IMAGE=$nova" "$MESAMAR_ENV_FILE"
    grep -qxF 'SEGREDO_FICTICIO=literal$sem_execucao' "$MESAMAR_ENV_FILE"
    unset MESAMAR_IMAGE
    docker compose up -d app
    [[ "$(cat "$ensaio/imagem-usada")" == "$nova" ]] || { echo 'Compose independente perdeu a imagem'; exit 1; }
  else
    [[ "$falha" != nenhuma ]] || { echo 'Implantação saudável falhou'; exit 1; }
    grep -qxF "MESAMAR_IMAGE=$antiga" "$MESAMAR_ENV_FILE"
  fi
  [[ -z "$(find "$ensaio" -maxdepth 1 -name '.imagem-*' -print)" ]] || { echo 'Arquivo de segredo temporário sobrou'; exit 1; }
done
for argumentos in '--producao arquivo.dump' 'arquivo.dump --limpar' 'arquivo.dump --invalido' ''; do
  read -r -a args <<< "$argumentos"
  if bash "$raiz/deploy/backup/restaurar.sh" "${args[@]}" > "$ensaio/erro" 2>&1; then echo 'Argumentos inválidos aceitos'; exit 1; fi
  grep -q 'Uso: restaurar.sh ARQUIVO.dump' "$ensaio/erro"
done
echo 'IMPLANTAÇÃO OK: digest persistido, Compose independente, falhas sem persistência e contrato de restauração.'
