#!/usr/bin/env bash
# Transforma falhas do build em anotações do GitHub Actions (aparecem no PR e na aba Checks),
# para diagnosticar sem precisar abrir o log inteiro.
#   uso: annotate-failures.sh <arquivo-de-log> [<diretorio-test-results>]
set -uo pipefail

LOG_FILE="${1:-}"
RESULTS_DIR="${2:-backend/build/test-results}"
MAX=9

emit() {
  local title="$1" body="$2"
  # Quebras de linha precisam ser codificadas para caberem numa anotação.
  body="${body//'%'/'%25'}"
  body="${body//$'\r'/}"
  body="${body//$'\n'/'%0A'}"
  echo "::error title=${title}::${body}"
}

count=0

# 1) Testes que falharam (XML do JUnit)
if [ -d "$RESULTS_DIR" ]; then
  while IFS= read -r xml; do
    [ "$count" -ge "$MAX" ] && break
    if grep -q "<failure\|<error" "$xml"; then
      detail=$(python3 - "$xml" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
out = []
for tc in root.iter("testcase"):
    for tag in ("failure", "error"):
        for f in tc.findall(tag):
            text = (f.get("message") or "") + "\n" + (f.text or "")
            out.append(f"{tc.get('classname')} > {tc.get('name')}\n{text[:2500]}")
print("\n----\n".join(out)[:6000])
PY
)
      emit "Teste falhou: $(basename "$xml" .xml)" "$detail"
      count=$((count + 1))
    fi
  done < <(find "$RESULTS_DIR" -name 'TEST-*.xml' 2>/dev/null)
fi

# 2) Erros de compilação, ktlint e "What went wrong" do Gradle
if [ -n "$LOG_FILE" ] && [ -f "$LOG_FILE" ]; then
  errors=$(grep -nE '^e: |\.kts?:[0-9]+:[0-9]+|What went wrong|^> |error:|ERROR|FAILURE|Could not|Caused by' "$LOG_FILE" | head -80)
  if [ -n "$errors" ]; then
    chunk=""
    while IFS= read -r line; do
      if [ $(( ${#chunk} + ${#line} )) -gt 3500 ]; then
        [ "$count" -ge "$MAX" ] && break
        emit "Log do build" "$chunk"
        count=$((count + 1))
        chunk=""
      fi
      chunk+="$line"$'\n'
    done <<< "$errors"
    if [ -n "$chunk" ] && [ "$count" -lt "$MAX" ]; then
      emit "Log do build" "$chunk"
    fi
  fi
fi

exit 0
