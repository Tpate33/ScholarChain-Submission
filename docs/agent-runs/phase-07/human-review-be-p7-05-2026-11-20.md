# Human marker review (BE-P7-05 codec exclusions) — 2026-11-20 ~19:55 CST

## Human statement (verbatim)
> Marker reviewed for the November 20, 2026, 7:46:52 PM run: 153 suites, 1948 passed, storage 374, exit code 0. Follow documented audit recovery without editing the marker or weakening hooks.
>
> Record the completed dependency-check evidence and continue the remaining local closeout and minor review fixes.
>
> For the reported dependencies in other modules, first verify affected versions and applicability against primary advisories, then present a bounded remediation proposal. I am not accepting unresolved vulnerabilities or authorizing broad dependency upgrades.
>
> Check current disk space read-only; the reported 2.7 GiB may be stale. Do not delete anything. Keep cloud operations, spending, deployment, commits, and hook changes outside scope.

## Dependency-check evidence (BE-P7-05, now complete)
- `sbt check` (task b91gnccqu) completed 7:46:52 PM CST, exit 0, 153 suites, 1948 passed, storage 374 (human-observed), so the storage Parquet suites (ParquetCodecIsolationSuite: 29 parts without codec jars; ParquetHostileSuite: differential SNAPPY vs snappy-java 1.1.10.11 at Test; ParquetRowsSuite; OpeningLoadSuite) pass on the patched classpath.
- verifyDependencyScopes OK, 26 checks: storage Compile/Runtime have no snappy-java, aircompressor or zstd-jni; storage Test has exactly snappy-java 1.1.10.11 (ledger key `snappy-java`, ledger 27 entries); rag Compile/Runtime/Test, services Runtime, Universal and Docker packaging have none (checked).
- Remaining outside storage (INFO `native-codec-revisions`, test scopes only): hadoop-test snappy-java 1.1.10.4; spark-test and tests-test snappy-java 1.1.10.5, aircompressor 0.27, zstd-jni 1.5.5-4. Not accepted; next: DU-P7-03 verifies affected versions and applicability against primary advisories, then a bounded remediation proposal for the human.
- commons-pool 1.6 remains on storage Runtime (no identified advisory).

## Disk space
The orchestrator has no allowlisted command to read disk space (`df` is not an orchestrator Bash row). The human is asked to run `df -h /Users/taufeeqpatel` (read-only). Nothing is deleted.

## Next
- CR-P7-14 (code-reviewer: build.sbt, project/versions.properties, project/DependencyScopes.scala).
- DU-P7-03 (dependency-updater, read-only, no Bash: other-module codec advisories).
- SE-P7-12 (storage-engineer, the only builder: CR-P7-13 m1, m2, n1, n2).
- Then WP8, WP10, WP9.
