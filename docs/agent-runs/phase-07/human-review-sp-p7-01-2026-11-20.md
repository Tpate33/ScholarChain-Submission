# Human marker review (SP-P7-01) — 2026-11-20 ~21:50 CST

## Human statement (verbatim)
> Marker reviewed for November 20, 2026, 9:43:19 PM: 156 suites, 1968 passed, Spark 263, storage 390, skipped 0, exit code 0. Follow documented audit recovery without manually editing the marker or weakening hooks.
>
> Proceed with the authorized dependency remediation and cross-module test setup, fix the CR-P7-16 findings, then complete sparkIT/localPipelineDev, WP8 adversarial tests, documentation updates, and WP9 final review and handoff. Run builds sequentially.
>
> Keep the Hadoop bundled-Netty assessment separate; no risk acceptance or Hadoop upgrade is authorized. No deletions, cloud operations, spending, deployment, commits, or hook changes.

## Applied
- Next builder: BE-P7-06 (build-engineer): DU-P7-04 change list (netty 4.1.141.Final pin on storage, services, sparkAnalytics, tests; aircompressor and zstd-jni excluded from sparkAnalytics/tests; snappy-java 1.1.10.11 override there; hadoop snappy exclusion H1 with fallback H2; L1 lz4-java exclusion attempt) plus the cross-module test setup (tests/Test parquet-hadoop with nativeCodecExclusions; storage % "test->test"); new FAIL checks.
- Then SE-P7-14 (storage-engineer: CR-P7-16 m1, m2, nits, LoadPermit Scaladoc), then sparkIT (spark-engineer) and localPipelineDev (hadoop-engineer), WP8 (test-engineer), documentation updates, WP9, handoff.
- DU-P7-05 (Hadoop-bundled Netty) is presented separately (du-p7-05-hadoop-shaded-netty.md); its finding corrects DU-P7-04 C3; option (d) (jar-content check) is a proposal awaiting the human's decision, not part of BE-P7-06; no risk acceptance, no Hadoop upgrade.
