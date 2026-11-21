# CR-P7-19 (re-check of BE-P7-08) and SP-P7-02 (`sbt sparkIT`) — orchestrator-condensed records

Full reports are in the session transcript.

## CR-P7-19 (code-reviewer): VERDICT APPROVE, no findings
- m1 closed: build.sbt:121-135 description and DependencyScopes.scala:59-71 header state exactly the scanned classpaths (build.sbt:811-819), the entry patterns (`nettyEntry` :1201-1207), the failure modes (:1213-1242) and the five limits; "writes nothing" replaced accurately (:79-84; self-test deletes and recreates, `finally` deletes, :1271-1272, :1336-1339).
- m2 closed: `nettyOrg` (:405) used by `nettyJar` (:411-413) feeding `nettyCp` (:449, :576-589) and `noNettyCp` (:505, :590-595); acceptance still requires org exactly io.netty, allowed module, ledger revision, file name; new self-test inputs (:1094-1108) fail their checks and would have passed the old code; nothing weakened.
- Wording note U3: "whose name contains netty" may read narrower than the code (safe direction).

## SP-P7-02 (spark-engineer): `sbt sparkIT` PASS on the remediated classpaths
- One foreground run: `[success] Total time: 35 s, completed Nov 20, 2026, 11:34:31 PM`, exit 0. Verdict: "sparkIT: PASS: 14 tests in 1 class(es), every expected class ran, no failure, ignored test or assumption failure (exit 0)". No NoClassDefFoundError, codec or Netty error. No test marker written.
- Attempt directory: modules/spark/target/spark-it/attempt-7/ (gitignored; removed by sbt clean). Analysis run run:dffad781b52d554739174502776797a62a4aa12ac7fdb5dc2cd9a7d32c04212c; edges 259, metrics 176; configDigest e37c32b1…; structure checks passed (1970-2026: 112 researchers, 163 edges, 31 components; 2020-2024: 64 researchers, 96 edges, 18 components); paths stored 9; load manifests neptune 1258, dynamodb 1302, s3 2809, s3-vectors 249; reproducibility logicalEqual=true for every dataset and CSV.
- Scope: local client mode only; not EMR or spark-submit evidence (sparkSubmitLocal deferred pending the human commit).

### Copied evidence (verbatim, read by the orchestrator from attempt-7 on 2026-11-20)
attempt-7/result.txt:
```
status=pass
tests=14
exit=0
expectedClasses=1
expectedJUnit=/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar (junit:junit:4.13.2)
evidence=junit-result.txt
junit.JUnitCore=/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar implementationVersion=4.13.2
junit.Result=/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar implementationVersion=4.13.2
class=scholarchain.spark.DevArchiveGraphFoundationSuite run=14 failures=0 ignored=0 assumptionFailures=0
```
attempt-7/junit-result.txt:
```
format=scholarchain.sparkit.junit-result.v2
nonce=94577744b4e938b111c88daaadd506750209e38a61087e2add21703e53667d04
junit	JUnitCore	/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar	4.13.2
junit	Result	/Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar	4.13.2
class	scholarchain.spark.DevArchiveGraphFoundationSuite	run=14	failures=0	ignored=0	assumptionFailures=0
classes=1
complete=94577744b4e938b111c88daaadd506750209e38a61087e2add21703e53667d04
```
