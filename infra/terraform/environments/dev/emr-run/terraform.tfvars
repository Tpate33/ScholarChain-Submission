# Stack settings for emr-run (dev). Every value must equal the run config
# (../runs/spark-dev-run-01.conf); scripts/run-bounded-spark.sh checks the cluster's bootstrap action.
# REPLACE-ME values are filled from the first `sbt sparkSubmitLocal` inventory and `shasum -a 256`.
emr_release_label     = "emr-7.14.0"
instance_type         = "m5.xlarge"
root_volume_gib       = 15
data_volume_gib       = 32
idle_timeout_seconds  = 900
install_script_sha256 = "REPLACE-ME"
submit_script_sha256  = "REPLACE-ME"
# Pinned in project/versions.properties (BE-P6-09) and emr-docs-research-03.md (verified).
spark_dist_sha512    = "a462656d2a87afcf81f24e4dea9a99e6666ed5c8ff7ba0cc9c936188498c549cf852d0ebb475325761d6cf645d1184d527be9a609503d587bb47d5532b533aa1"
scala_library_sha256 = "REPLACE-ME"
hadoop_aws_sha256    = "REPLACE-ME"
hadoop_aws_sha1      = "a65839fbf1869f81a1632e09f415e586922e4f80"
sdk_bundle_sha256    = "REPLACE-ME"
