# Phase 06: human-computed sha256 values for the EMR run config (2026-11-18)

Requested by IE-P6-04 (ie-p6-04-report.md, section 2). Run by the human in a Terminal at the repository root, HEAD 148b4b9. Output verbatim:

```
07d11283933c63afda1b03c6fe046bace19444ccc4a42e0df4dabf98fbbd8ff0  scripts/emr/install-spark-dist.sh
ccf45f6cff9e202338dc377d559308abe028e5115d2bcee02e80d37c393db34f  scripts/emr/submit-collaboration-job.sh
3581db56cb57cc94d83186d468be8233adebc27a94136540731b35839b09888f  target/spark-dist/spark-3.5.5-bin-hadoop3-scala2.13.tgz
```

Destination:
- `install_script_sha256` and `submit_script_sha256` in infra/terraform/environments/dev/emr-run/terraform.tfvars
- `spark_dist_sha256` in infra/terraform/environments/dev/runs/spark-dev-run-01.conf

The values are written by infra-engineer (owner of both files), together with `partitions` once spark-engineer decides it (SP-P6-08). The dry run re-hashes every file and refuses any mismatch.
