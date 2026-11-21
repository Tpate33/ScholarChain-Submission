# Phase 07: disk space (2026-11-20)

- Human read-only inspection (human Terminal, screenshot in session): 2.7 GiB available (99% used).
  - target 3.1G, of which target/spark-submit-local 2.7G and target/spark-dist 391M.
  - The three Terraform `.terraform` directories are 726M each.
  - datasets/downloads 1.0G; ~/ScholarChain-archive 1.6G; storage test resources 1.3M.
  - Coursier cache 742M; Homebrew cache 506M; Claude temporary outputs 240M.
  - Personal Downloads (not inspected) 12G; ~/Library/Caches 6.2G.
- Orchestrator read-only findings (git ls-files and Read only; nothing deleted):
  - Each of target/spark-submit-local/attempt-1..4 holds its own `dist/spark-3.5.5-bin-hadoop3-scala2.13/`. This is an extraction of the cached, sha512-pinned target/spark-dist tarball, and each `sbt sparkSubmitLocal` run extracts it afresh into a new attempt (project/SparkSubmitLocal.scala:48-57).
  - attempt-4 also holds the run outputs.
  - ~/ScholarChain-archive/phase-06/local-evidence/SHA256SUMS.txt covers `spark-submit-local-attempt-4/...`. Whether the archive covers attempts 1-3 was not established.
  - Each `.terraform` directory holds the aws provider 6.72.0 binary under providers/, plus modules.json (except emr-run) and a backend `.terraform/terraform.tfstate` (not read).
  - The three `.terraform.lock.hcl` files are committed.
- Human decision: "I will free disk space myself using personal files. Stop the cleanup investigation; no deletion is authorized. Wait for me to confirm available space, then resume the approved Phase 7 work."
- Result: no cleanup proposal was issued, and nothing in the repository, the archive or the caches was deleted.
- Human follow-up (screenshot at 10:58 local): `df -h /Users/taufeeqpatel` shows 171Gi used, 14Gi available, 93% used. Phase 7 work resumed.
