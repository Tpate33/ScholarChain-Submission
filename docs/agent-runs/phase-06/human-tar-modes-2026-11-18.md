# Phase 06: tar entry modes of the pinned Spark distribution (human Terminal, 2026-11-18)

Purpose: confirm or refute BE-P6-10's inferred diagnosis (be-p6-10-report.md) before routing a repair.

Command (human Terminal, repository root):

```sh
tar -tvzf target/spark-dist/spark-3.5.5-bin-hadoop3-scala2.13.tgz | grep -E 'bin/(load-spark-env|spark-submit|spark-class)'
```

Output (transcribed by the orchestrator from the human's screenshot, 2026-11-18 8:27 PM):

```
-rwxr-xr-x  0 spark-rm spark-rm     3561 Feb 23  2025 spark-3.5.5-bin-hadoop3-scala2.13/bin/spark-class
-rw-r--r--  0 spark-rm spark-rm     1180 Feb 23  2025 spark-3.5.5-bin-hadoop3-scala2.13/bin/spark-class.cmd
-rwxr-xr-x  0 spark-rm spark-rm     1040 Feb 23  2025 spark-3.5.5-bin-hadoop3-scala2.13/bin/spark-submit
-rw-r--r--  0 spark-rm spark-rm     1180 Feb 23  2025 spark-3.5.5-bin-hadoop3-scala2.13/bin/spark-submit.cmd
-rw-r--r--  0 spark-rm spark-rm     1155 Feb 23  2025 spark-3.5.5-bin-hadoop3-scala2.13/bin/spark-submit2.cmd
-rw-r--r--  0 spark-rm spark-rm     2337 Feb 23  2025 spark-3.5.5-bin-hadoop3-scala2.13/bin/load-spark-env.cmd
-rw-r--r--  0 spark-rm spark-rm     2678 Feb 23  2025 spark-3.5.5-bin-hadoop3-scala2.13/bin/load-spark-env.sh
-rwxr-xr-x  0 spark-rm spark-rm     2891 Feb 23  2025 spark-3.5.5-bin-hadoop3-scala2.13/bin/spark-class2.cmd
```

Conclusion: the upstream tar entry for `bin/load-spark-env.sh` has mode 0644 (no execute bit); `bin/spark-class` and `bin/spark-submit` are 0755. The build's tar reader reproduced the upstream mode correctly; the layout check at project/SparkSubmitLocal.scala:734-737 is too strict for load-spark-env.sh. Routed to build-engineer as BE-P6-09 repair 02.
