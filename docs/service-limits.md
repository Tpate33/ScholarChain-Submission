# Service bounds and verification limits

## Local demo (source-defined)

The values below come from `services/localdemo/LocalDemo.scala`, not measured AWS
service quotas: IPv4 loopback only; default port 8080; request body 16,384 bytes;
question 2,048 bytes; sync/async workflow timeouts 10 seconds; read timeout 5 seconds;
read attempts 3; workflow workers 4 with queue 8; jobs 64; idempotency entries 256
with one-hour TTL; ownership entries 1,000; graph depth 1, maximum page size 20,
maximum results 50; synthetic graph window 2018–2022; evidence content bound 4,096 bytes.
These bounds do not establish throughput, latency percentiles, elasticity, or safe
multi-replica operation. Health success is not proof of every downstream dependency.

The DBLP-derived profile uses the same HTTP/runtime limits, with the widest window
from the accepted analysis settings (1970–2026) instead of the synthetic default.
Its loader uses explicit bounded file/Parquet/upstream reads and a pinned manifest;
only the accepted Phase 6 dev run is supported. Entity-link cache TTL is one day
from loading; restarting reloads the local stores. No production TTL claim is made.

## Cloud

Historical configuration targets us-east-1 and EMR 7.14.0 with two m5.xlarge nodes.
See Phase 6 records for the planned run bounds, failed executions and successful
Phase A cleanup. This completion performed no live quota, price, availability,
resource inventory, or regional feature check. Previously recorded limits must be
rechecked before deployment. AWS Budgets alerts and configuration cost estimates
are not hard billing caps. Retained base resources and storage may incur costs.

Production read-policy findings, S3 streaming-body bounds, cancellation limitations,
and endpoint validation residuals remain tracked in integration-01/outstanding.md.
