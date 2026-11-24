"""Reviewed external stop controller. No infrastructure deletion; no retries.
Lambda is outside the private VPC so termination does not depend on paid endpoints.
"""
import datetime as dt
import json
import os

UTC = dt.timezone.utc
LIMITS = {"query": 7200, "evidence": 7200, "graph": 7200,
          "er": 1200, "loader": 2700, "collector": 1200}


def epoch(value):
    if isinstance(value, dt.datetime):
        return value.timestamp()
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


def expired(task, now, deadline, families):
    family = task["taskDefinitionArn"].rsplit("/", 1)[1].split(":", 1)[0]
    kind = families.get(family)
    if kind not in LIMITS:
        raise ValueError("unapproved task family")
    # createdAt covers PENDING time as well as RUNNING time.
    return now >= deadline or now >= epoch(task["createdAt"]) + LIMITS[kind]


def enforce(ecs, emr, sns, cfg, now):
    events = []
    deadline = epoch(cfg["deadline"])
    if cfg.get("graph_deadline") and epoch(cfg["graph_deadline"]) <= now < deadline:
        cfg["scaling"].register_scalable_target(
            ServiceNamespace="ecs", ResourceId=cfg["graph_resource"],
            ScalableDimension="ecs:service:DesiredCount", MinCapacity=0, MaxCapacity=1,
            SuspendedState={"DynamicScalingInSuspended": True,
                            "DynamicScalingOutSuspended": True,
                            "ScheduledScalingSuspended": True})
        # Clamp active capacity to one; zero remains zero after an explicit stop.
    if now >= deadline:
        # Disable scale-out before lowering services; suspended min remains a target bound only.
        cfg["scaling"].register_scalable_target(
            ServiceNamespace="ecs", ResourceId=cfg["graph_resource"],
            ScalableDimension="ecs:service:DesiredCount", MinCapacity=0, MaxCapacity=2,
            SuspendedState={"DynamicScalingInSuspended": True,
                            "DynamicScalingOutSuspended": True,
                            "ScheduledScalingSuspended": True})
        for service in cfg["services"]:
            ecs.update_service(cluster=cfg["cluster"], service=service, desiredCount=0)
        events.append("runtime-deadline-services-zero")
    # One bounded listing; fail and alarm if deployment is outside the approved small shape.
    listing = ecs.list_tasks(cluster=cfg["cluster"], maxResults=100)
    if listing.get("nextToken"):
        raise ValueError("task count exceeded bounded inventory")
    arns = listing.get("taskArns", [])
    if arns:
        result = ecs.describe_tasks(cluster=cfg["cluster"], tasks=arns)
        if result.get("failures"):
            raise ValueError("task inspection failed")
        tasks = result["tasks"]
        counts = {}
        for task in tasks:
            family = task["taskDefinitionArn"].rsplit("/", 1)[1].split(":", 1)[0]
            kind = cfg["families"].get(family)
            if kind is None:
                raise ValueError("unapproved task family")
            counts[kind] = counts.get(kind, 0) + 1
        if counts.get("er", 0) + counts.get("loader", 0) > 1:
            # Stop both workers; never pick a winner or replace automatically.
            events.append("worker-serialization-violation")
        for task in tasks:
            family = task["taskDefinitionArn"].rsplit("/", 1)[1].split(":", 1)[0]
            kind = cfg["families"][family]
            overcount = counts[kind] > (2 if kind == "graph" else 1)
            concurrent = kind in ("er", "loader") and counts.get("er", 0) + counts.get("loader", 0) > 1
            if overcount or concurrent or expired(task, now, deadline, cfg["families"]):
                ecs.stop_task(cluster=cfg["cluster"], task=task["taskArn"], reason="ScholarChain bounded deadline/shape guard")
                events.append("task-stopped:" + kind)
    cluster_id = cfg.get("emr_cluster_id")
    if cluster_id:
        cluster = emr.describe_cluster(ClusterId=cluster_id)["Cluster"]
        status = cluster["Status"]
        failed = status.get("State") in ("TERMINATED_WITH_ERRORS",)
        if status.get("State") not in ("TERMINATED", "TERMINATED_WITH_ERRORS"):
            steps = emr.list_steps(ClusterId=cluster_id)
            if steps.get("Marker"):
                raise ValueError("unexpected EMR step pagination")
            failed = any(s["Status"]["State"] in ("FAILED", "CANCELLED", "INTERRUPTED") for s in steps["Steps"])
            created = epoch(status["Timeline"]["CreationDateTime"])
            if failed or now >= min(epoch(cfg["emr_deadline"]), created + 7200):
                emr.terminate_job_flows(JobFlowIds=[cluster_id])
                events.append("emr-terminated-failure-or-deadline")
    if events:
        sns.publish(TopicArn=cfg["topic"], Subject="ScholarChain bounded stop guard", Message=json.dumps(events))
    return {"events": events}


def handler(event, context):
    import boto3
    from botocore.config import Config
    sdk = Config(retries={"total_max_attempts": 1}, connect_timeout=5, read_timeout=10)
    cfg = json.loads(os.environ["CONTROL"])
    cfg["scaling"] = boto3.client("application-autoscaling", config=sdk)
    try:
        return enforce(boto3.client("ecs", config=sdk), boto3.client("emr", config=sdk),
                       boto3.client("sns", config=sdk), cfg, dt.datetime.now(UTC).timestamp())
    except Exception:
        # No secret payload or raw API response in logs/notifications.
        boto3.client("sns", config=sdk).publish(TopicArn=cfg["topic"], Subject="ScholarChain deadline guard FAILED", Message="Human intervention required; guard failed. Check Lambda errors and approved cleanup.")
        raise
