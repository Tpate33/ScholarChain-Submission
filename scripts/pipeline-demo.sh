#!/usr/bin/env bash
# Local-only real-data profile. Normal service packaging stays free of Parquet/Hadoop.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
for variable_name in $(env | sed -n 's/^\(AWS_[A-Za-z0-9_]*\)=.*/\1/p'); do
  unset "$variable_name"
done
if [[ "${1:-}" == --stage ]]; then
  [[ $# == 1 ]] || { echo 'Usage: scripts/pipeline-demo.sh --stage OR scripts/pipeline-demo.sh [--port PORT]' >&2; exit 2; }
  exec sbt pipelineDemoClasspath
fi
classpath_file="$repo_root/target/pipeline-demo/classpath.txt"
[[ -f "$classpath_file" ]] || { echo 'Run bash scripts/pipeline-demo.sh --stage first.' >&2; exit 1; }
java_bin=java
if [[ -n "${JAVA_HOME:-}" ]]; then java_bin="$JAVA_HOME/bin/java"; fi
exec "$java_bin" -cp "$(cat "$classpath_file")" scholarchain.services.localdemo.PipelineDemoMain \
  --data-root "$repo_root/modules/storage/src/test/resources/fixtures" "$@"
