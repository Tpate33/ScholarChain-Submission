#!/usr/bin/env bash
# Local synthetic demo only; no AWS calls or model access.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
if [[ "${1:-}" == "--stage" ]]; then
  shift
  if [[ $# -ne 0 ]]; then
    echo 'Usage: scripts/local-demo.sh --stage OR scripts/local-demo.sh [--port PORT]' >&2
    exit 2
  fi
  exec sbt services/stage
fi
launcher="$repo_root/modules/services/target/universal/stage/bin/scholarchain-services"
if [[ ! -x "$launcher" ]]; then
  echo 'Demo is not staged. Run: bash scripts/local-demo.sh --stage' >&2
  exit 1
fi
# Refuse cloud credential inheritance in the synthetic demo process.
for variable_name in $(env | sed -n 's/^\(AWS_[A-Za-z0-9_]*\)=.*/\1/p'); do
  unset "$variable_name"
done
exec "$launcher" -main scholarchain.services.localdemo.LocalDemoMain "$@"
