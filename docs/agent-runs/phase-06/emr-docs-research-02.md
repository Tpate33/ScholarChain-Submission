# Phase 06 EMR documentation research 02: prices and storage (web-fetch agent, public AWS pages, 2026-11-17)

| # | Question | Finding | Source |
|---|---|---|---|
| 1 | EMR uplift m5.xlarge / m6i.xlarge, us-east-1 | NOT ESTABLISHED (page has only a c4.2xlarge example, EMR 0.105 USD/h; offer file only partly readable, no price seen) | aws.amazon.com/emr/pricing/; pricing.us-east-1.amazonaws.com/offers/v1.0/aws/ElasticMapReduce/current/us-east-1/index.json |
| 2 | EC2 On-Demand Linux m5.xlarge / m6i.xlarge, us-east-1 | NOT STATED (table did not render; regional offer file too large for the tool) | aws.amazon.com/ec2/pricing/on-demand/; AmazonEC2 region_index.json |
| 3 | EBS gp3 per GB-month, us-east-1 | NOT STATED (only hypothetical "$0.08 per GB-month" example) | aws.amazon.com/ebs/pricing/ |
| 4 | S3 Standard storage and requests | storage and PUT NOT STATED; GET $0.0004 per 1,000 (S3 Standard, in an Object Lambda example) | aws.amazon.com/s3/pricing/ |
| 5 | EMR default storage | root volume gp3 15 GiB (EMR 6.15+); EBS-only `*.xlarge` default 2 x 32 GiB gp2 (applied to m5.xlarge by inference); core EBS >= 5 GB | docs.aws.amazon.com/emr/latest/ManagementGuide/emr-plan-storage.html |
| 6 | Auto-termination | idle timeout default 60 min, minimum 1 minute, maximum 7 days; us-east-1 supported | emr-auto-termination-policy.html |
| 7 | AWS Budgets | budgets with notifications free; first two action-enabled budgets free, then $0.10/day each; reports $0.01 each | aws.amazon.com/aws-cost-management/aws-budgets/pricing/ |
| - | Billing granularity (research 01) | EMR on EC2, EC2, EBS: per second, one-minute minimum | aws.amazon.com/emr/pricing/ |

Consequence: no instance-hour price is verified from an official page in this session. The cost estimate in emr-proposal.md is a formula with an explicitly unverified reference rate; the human supplies or confirms the price bound (the run script already requires a human-supplied `instance_hour_price_usd`).
