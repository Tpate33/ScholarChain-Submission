# Phase 06: EC2 vCPU quota increase request (human, 2026-11-18)

Human ran in a Terminal (screenshot shared in session):

```
aws service-quotas request-service-quota-increase --service-code ec2 --quota-code L-1216C47A --desired-value 8 --region us-east-1 --profile scholarchain-login
```

Response fields (transcribed from the screenshot): Id `6878646f637c437d9413cf7d19b0cdd7bm5zIMb4`; ServiceCode `ec2`; QuotaCode `L-1216C47A`; QuotaName "Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances"; DesiredValue `8.0`; Status `PENDING`; Created `2026-11-18T13:17:26.624000-06:00`; Requester callerArn `arn:aws:iam::663570374448:user/scholarchain-admin`; QuotaArn `arn:aws:servicequotas:us-east-1:663570374448:ec2/L-1216C47A`; QuotaRequestedAtLevel `ACCOUNT`.

A service-quota request; no resource created and no charge stated by AWS documentation (emr-docs-research-05.md). Blocker B-quota stays open until `get-service-quota` reports ≥ 8.0.
