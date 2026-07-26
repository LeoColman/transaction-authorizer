#!/usr/bin/env bash
# Gera os relatórios de cobertura e mutação e confere os números do README.
#
# Os três modos do Kover instrumentam de forma diferente, então cada um precisa
# de uma execução própria e sobrescreve o mesmo report.xml — daí a cópia para
# arquivos distintos antes da comparação.
#
# Uso: scripts/check-numbers.sh [--skip-pitest]
set -euo pipefail

cd "$(dirname "$0")/.."
out="build/reports/numbers"
mkdir -p "$out"

echo "==> Cobertura total (unitários + integração)"
./gradlew koverXmlReport --console=plain -q
cp build/reports/kover/report.xml "$out/total.xml"

echo "==> Cobertura só dos unitários"
./gradlew koverXmlReport -PkoverMode=unit --console=plain -q
cp build/reports/kover/report.xml "$out/unit.xml"

echo "==> Cobertura só da integração"
./gradlew koverXmlReport -PkoverMode=integration --console=plain -q
cp build/reports/kover/report.xml "$out/integration.xml"

if [[ "${1:-}" != "--skip-pitest" ]]; then
  echo "==> Mutação"
  ./gradlew pitest --console=plain -q
fi

echo "==> Conferindo o README"
python3 scripts/check_readme_numbers.py \
  --kover-total "$out/total.xml" \
  --kover-unit "$out/unit.xml" \
  --kover-integration "$out/integration.xml" \
  --pitest build/reports/pitest/mutations.xml
