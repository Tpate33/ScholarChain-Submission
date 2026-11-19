# Stack settings for emr-run (dev). Every value must equal the run config
# (../runs/spark-dev-run-01.conf), except install_script_sha256 and submit_script_sha256, which are
# the sha256 of the committed scripts; scripts/run-bounded-spark.sh checks the cluster's bootstrap action.
# Runtime-jar sha256 values: target/spark-submit-local/attempt-4/inventory.txt (sbt sparkSubmitLocal PASS
# at 148b4b9, BE-P6-10 attempt-4; IE-P6-04). install_script_sha256: `shasum -a 256` by the human of
# scripts/emr/install-spark-dist.sh in the IE-P6-09 working tree (to be committed on top of b50d8be),
# archived install-spark-dist-sha256.txt (IE-P6-09 repair 03); previous value
# 07d11283933c63afda1b03c6fe046bace19444ccc4a42e0df4dabf98fbbd8ff0 (attempts 1-3; attempt 3 failed
# BOOTSTRAP_FAILURE on the comma entries). submit_script_sha256: `shasum -a 256` by the human of
# scripts/emr/submit-collaboration-job.sh in the IE-P6-12 working tree (to be committed on top of
# 3892b0f), archived submit-wrapper-sha256.txt (IE-P6-12 repair 02); previous value
# ccf45f6cff9e202338dc377d559308abe028e5115d2bcee02e80d37c393db34f (run attempt 2 failed exit 4:
# the wrapper took Corretto 8 from the PATH java).
emr_release_label     = "emr-7.14.0"
instance_type         = "m5.xlarge"
root_volume_gib       = 15
data_volume_gib       = 32
idle_timeout_seconds  = 900
install_script_sha256 = "38835028c231444ec2fa6c6e7ec180bcb1bfbae09f6acaa29ecb4af4acb7c2a1"
submit_script_sha256  = "8ed99c72e9636eef7d1192a61a4c5255ae3142e5ff5e4a46fd733d069ad5fcf3"
# Pinned in project/versions.properties (BE-P6-09) and emr-docs-research-03.md (verified).
spark_dist_sha512    = "a462656d2a87afcf81f24e4dea9a99e6666ed5c8ff7ba0cc9c936188498c549cf852d0ebb475325761d6cf645d1184d527be9a609503d587bb47d5532b533aa1"
scala_library_sha256 = "1ebb2b6f9e4eb4022497c19b1e1e825019c08514f962aaac197145f88ed730f1"
hadoop_aws_sha256    = "53f9ae03c681a30a50aa17524bd9790ab596b28481858e54efd989a826ed3a4a"
hadoop_aws_sha1      = "a65839fbf1869f81a1632e09f415e586922e4f80"
sdk_bundle_sha256    = "873fe7cf495126619997bec21c44de5d992544aea7e632fdc77adb1a0915bae5"
