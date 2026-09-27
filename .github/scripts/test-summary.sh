#!/usr/bin/env bash
# Resume os resultados do JUnit numa anotação (visível na aba Checks do PR).
#   uso: test-summary.sh [<diretorio-test-results>]
set -uo pipefail
RESULTS_DIR="${1:-backend/build/test-results}"
[ -d "$RESULTS_DIR" ] || { echo "::notice title=Testes::Nenhum resultado encontrado em $RESULTS_DIR"; exit 0; }

python3 - "$RESULTS_DIR" <<'PY'
import sys, pathlib, xml.etree.ElementTree as ET
total = failures = errors = skipped = 0
lines = []
for xml in sorted(pathlib.Path(sys.argv[1]).rglob("TEST-*.xml")):
    root = ET.parse(xml).getroot()
    t, f, e, s = (int(root.get(k, 0)) for k in ("tests", "failures", "errors", "skipped"))
    total, failures, errors, skipped = total + t, failures + f, errors + e, skipped + s
    name = root.get("name", xml.stem).rsplit(".", 1)[-1]
    lines.append(f"{name}: {t - f - e - s}/{t} ok ({float(root.get('time', 0)):.1f}s)")
passed = total - failures - errors - skipped
header = f"{passed} passaram, {failures + errors} falharam, {skipped} ignorados (total {total})"
print(f"::notice title=Testes: {header}::" + "%0A".join(lines))
PY
