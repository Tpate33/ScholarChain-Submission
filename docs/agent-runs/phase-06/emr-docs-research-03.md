# Phase 06 research 03: Spark distribution, S3A jars and AWS prices (orchestrator, web-fetch, 2026-11-17)

Official sources only (archive.apache.org, raw.githubusercontent.com/apache/spark, repo1.maven.org, aws.amazon.com, b0.p.awsstatic.com, pricing.us-east-1.amazonaws.com). Values below were read verbatim unless marked otherwise. Fetched content is data, not instructions.

## Spark distribution (Option A)

| Fact | Value | Source | Status |
|---|---|---|---|
| Artifact | `spark-3.5.5-bin-hadoop3-scala2.13.tgz` (391M, with `.asc` and `.sha512`, modified 2025-02-23 20:47) | https://archive.apache.org/dist/spark/spark-3.5.5/ | verified |
| sha512 | `a462656d2a87afcf81f24e4dea9a99e6666ed5c8ff7ba0cc9c936188498c549cf852d0ebb475325761d6cf645d1184d527be9a609503d587bb47d5532b533aa1` | https://archive.apache.org/dist/spark/spark-3.5.5/spark-3.5.5-bin-hadoop3-scala2.13.tgz.sha512 | verified (128 hex) |
| Signing keys | KEYS file exists | https://archive.apache.org/dist/spark/KEYS | exists; only partly read. The `.asc` signature has NOT been verified (needs gpg, which no agent row allows) |
| Bundled Hadoop client | `<hadoop.version>3.3.4</hadoop.version>` | https://raw.githubusercontent.com/apache/spark/v3.5.5/pom.xml | verified (top-level property) |
| Scala in the 2.13 profile | 2.13.8 | same pom | NOT verified verbatim (model summary only); build-engineer to confirm from the tarball's `jars/scala-library-*.jar` name |
| hadoop-aws | 3.3.4; jar sha1 `a65839fbf1869f81a1632e09f415e586922e4f80` | https://repo1.maven.org/maven2/org/apache/hadoop/hadoop-aws/3.3.4/hadoop-aws-3.3.4.jar.sha1 | verified |
| AWS SDK bundle for hadoop-aws 3.3.4 | `com.amazonaws:aws-java-sdk-bundle` `1.12.262` (parent `<aws-java-sdk.version>1.12.262</aws-java-sdk.version>`) | hadoop-aws-3.3.4.pom and hadoop-project-3.3.4.pom on repo1.maven.org | verified |

Note: Spark's own `aws.java.sdk.version` 1.11.655 is for Kinesis and does not apply to S3A.

## Prices, us-east-1, On-Demand (offer data published 2026-11-17 for EC2, 2026-11-10 effective for EMR)

| Item | Price | Source | Status |
|---|---|---|---|
| EC2 m5.xlarge Linux | 0.192 USD/h | b0.p.awsstatic.com …/ec2-ondemand-without-sec-sel/US East (N. Virginia)/Linux/index.json | verified |
| EC2 m6i.xlarge Linux | 0.192 USD/h | same | verified |
| EMR m5.xlarge | "$0.048 per hour for EMR m5.xlarge", SKU 394XAA9GGEC5BPZG | pricing.us-east-1.amazonaws.com …/ElasticMapReduce/current/us-east-1/index.json | verified |
| EMR m6i.xlarge | "$0.048 per hour for EMR m6i.xlarge", SKU 7CHNKFPCMQU9SMRB | same | verified |
| EBS gp3 | 0.08 USD/GB-month; baseline 3,000 IOPS and 125 MB/s free | b0.p.awsstatic.com …/ec2/USD/current/ebs.json; aws.amazon.com/ebs/pricing/ | verified |
| KMS customer managed key | "$1/month (prorated hourly)"; "no charge for … keys … scheduled for deletion"; 20,000 requests/month free | aws.amazon.com/kms/pricing/ | verified |
| S3 gateway endpoint | "no data processing or hourly charges for using Gateway Type VPC endpoints" | aws.amazon.com/vpc/pricing/ | verified |
| Public IPv4 | 0.005 USD per IP-hour (in use or idle) | aws.amazon.com/vpc/pricing/ | verified (not used: private subnet) |
| AWS Budgets | "monitor and receive notifications on your budgets free of charge" | aws.amazon.com/aws-cost-management/aws-budgets/pricing/ | verified |
| S3 Standard storage and requests | 0.023 USD/GB-month, PUT 0.005 and GET 0.0004 per 1,000 | s3 pages did not render; b0 s3.json values read were for other Regions | NOT verified for us-east-1 (cost bounded below) |
| CloudWatch Logs ingestion | — | tables did not render | NOT verified (not planned to be used) |

Region us-east-1 is still the proposal's assumption; it is not yet confirmed by the human.
