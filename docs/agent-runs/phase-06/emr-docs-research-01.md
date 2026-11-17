# Phase 06 EMR documentation research 01 (web-fetch agent, public AWS pages, 2026-11-17)

Route: the `web-fetch` agent read public docs.aws.amazon.com and aws.amazon.com pages only (no account data, no AWS API call), because the `aws-docs` MCP server is not connected in this session. Findings as reported; "not stated" means the page did not say it.

| # | Question | Finding | Source |
|---|---|---|---|
| 1 | Latest EMR 7.x on EC2 | emr-7.14.0 (initial release September 8, 2026): Spark 3.5.8-amzn-0, Scala 2.12.18 (also 2.12.18 for 7.11-7.13), Hadoop 3.4.2-amzn-2, Corretto 17 default ("Spark 17, 11, 8") | ReleaseGuide/emr-release-components.html, emr-7140-release.html |
| 2 | Any EMR release with Spark for Scala 2.13 | EMR on EC2 7.x: no (2.12.18). EMR Serverless lists emr-spark-8.2.0 (newest; Spark 4.1.3-amzn-0), 8.1.0, 8.0.0; Scala and Java not stated on that page. EMR on EKS not checked | EMR-Serverless-UserGuide/release-versions.html, release-version-emr-spark-8.2.0.html |
| 3 | Serverless custom image replacing Spark | not stated (examples only add libraries/jars); licensing page limits modification of AWS content | using-custom-images.html, concepts-licensing-images.html |
| 4 | User-installed Spark on EMR on EC2 via bootstrap + command-runner | not stated either way; bootstrap actions run before applications install, may use sudo; command-runner runs spark-submit of installed applications | ManagementGuide/emr-plan-bootstrap.html, ReleaseGuide/emr-commandrunner.html |
| 5 | Prices m5.xlarge / m6i.xlarge us-east-1 | not stated in fetched text (rate tables did not render); only worked examples (c4.2xlarge EMR 0.105 USD/h + EC2 0.398 USD/h, us-east-1; Serverless example 0.052624 USD per vCPU-hour, 0.0057785 USD per GB-hour, region not named) | aws.amazon.com/emr/pricing/ |
| 6 | Minimum billing | EMR on EC2 per second, one-minute minimum (EC2 and EBS too); Serverless rounded up to the second, 1-minute minimum | aws.amazon.com/emr/pricing/ |
| 7 | S3 consistency, conditional writes | strong read-after-write and list consistency in all regions; PutObject/CompleteMultipartUpload/CopyObject accept If-None-Match: 200 if absent, 412 if present, 409 possible under concurrency | aws.amazon.com/s3/consistency/, AmazonS3/latest/userguide/conditional-writes.html |
| 8 | EMRFS committer | EMRFS S3-optimized committer enabled by default since 5.20.0 for Spark SQL/DataFrame/Dataset writes; not used with S3A, HDFS, or RDD APIs; default file system for s3:// on 7.x not stated in these pages | emr-spark-s3-optimized-committer.html, emr-spark-committer-reqs.html |

## Orchestrator reading (to be verified by infra-engineer and build-engineer)

- Runtime compatibility: the job is Scala 3 compiled against `spark-sql_2.13` 3.5.5. EMR on EC2 7.14's Spark is Scala 2.12, so the job cannot run on EMR's own Spark. Spark 4.x is published for Scala 2.13 only (Apache Spark project fact, not from these pages), which suggests EMR Serverless emr-spark-8.x is Scala 2.13, but the pages do not state it, and adopting it means moving the build from Spark 3.5 to Spark 4.1, a major dependency upgrade that needs human approval.
- Options, then: (A) EMR on EC2 7.14 with a pinned Apache Spark 3.5.x Scala 2.13 distribution installed by a bootstrap action and run with its own spark-submit (not documented either way by AWS; our code then reads S3 through Hadoop S3A from that distribution, not EMRFS); (B) EMR Serverless emr-spark-8.x after a Spark 4 upgrade of the build (Scala 2.13 to be confirmed from the release page or a test job); (C) EMR on EKS (not researched).
- The EMRFS committer does not apply to the job's RDD writes; the S3/HDFS I/O design (CR-P6-25 f2) must not rely on it.
- Cost: no verified m5.xlarge rate was obtained; a second documentation read (EC2 on-demand pricing page, EMR pricing table) is needed before a cost number is stated.
