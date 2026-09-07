#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -n "${FIJI_JAVA_HOME:-}" ]]; then
  export JAVA_HOME="${FIJI_JAVA_HOME}"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi
MAVEN_BIN="${MAVEN_BIN:-mvn}"
if ! command -v java >/dev/null 2>&1; then
  echo "Java 17 or newer is required. Set JAVA_HOME or FIJI_JAVA_HOME." >&2
  exit 1
fi
if ! command -v "${MAVEN_BIN}" >/dev/null 2>&1; then
  echo "Maven is required. Install Maven or set MAVEN_BIN." >&2
  exit 1
fi
cd "${PROJECT_ROOT}"
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
MAVEN_ARGS=(--batch-mode --no-transfer-progress)
if [[ -n "${MAVEN_REPOSITORY:-}" ]]; then
  MAVEN_ARGS+=("-Dmaven.repo.local=${MAVEN_REPOSITORY}")
fi
"${MAVEN_BIN}" "${MAVEN_ARGS[@]}" verify
