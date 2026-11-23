# Reproduce the local demonstrations

## Recommended: real DBLP-derived pipeline data

**NON-PRODUCTION:** real bounded dev data; in-memory storage; template answer model;
hashed-token stand-in embeddings. No AWS access or paid model calls. This is not a
cloud deployment, full-corpus analysis or real embedding/LLM evaluation.

Use JDK 17, sbt 1.11.7, Python 3 and Bash. Initial dependency resolution may need
Maven access. From the repository root:

```sh
bash scripts/pipeline-demo.sh --stage
python3 scripts/pipeline-demo-smoke.py
```

Expect `DBLP PIPELINE DEMO PASS`. The replay starts on an ephemeral loopback port,
saves JSON under `target/pipeline-demo-evidence/`, and stops its child. `--output
/absolute/path` chooses another evidence directory. Preserve generated output
before cleaning `target/`.

The data are the existing human-copied Phase 6 dev artifacts in
`modules/storage/src/test/resources/fixtures/`. Their location as test resources
does not mean the source records were fabricated: the Hadoop/Spark lineage is
verified before loading. The curated input contains 41 DBLP, 43 OpenAlex and six
Crossref normalized work records. This is not evidence of the required corpus size
or three additional independently ingested datasets. The accepted analysis run is
`a65f3f38b597fc4709fe24e0ddbd12ae09a0c79ad54f9d1f5378c06cfe8a8c40`.

The launcher compiles the application and writes a separate local classpath with
storage's optional Parquet readers. It imports no test implementation or Spark/Hadoop
job code into services. The normal service dependency exclusions and default production entry point remain unchanged. No synthetic seed
is used if the requested real artifact is missing or invalid: startup refuses.

For an interactive demonstration, terminal A:

```sh
bash scripts/pipeline-demo.sh --port 8080
```

In terminal B:

```sh
curl -sS http://127.0.0.1:8080/v1/query \
  -H 'Authorization: Bearer scholarchain-local-demo-key-NOT-A-SECRET' \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: dblp-demo-1' \
  -d '{"question":"Who are the coauthors of Ailing Zeng?","mode":"sync"}' \
  | python3 -m json.tool
```

The response identifies Ailing Zeng by an ORCID-anchored canonical ID, returns
three collaborators for 1970–2026 in the bounded sample and cites edges supported
by `work:dblp:conf/aaai/ZengCZ023` (the 2023 paper “Are Transformers Effective for
Time Series Forecasting?”). This is a result over the dev sample, not an exhaustive
career-level coauthor claim. Identity/grouping evaluation limitations remain in
the source provenance. Every response carries
`X-ScholarChain-Profile: pipeline-demo; DBLP-DERIVED; NON-PRODUCTION`.

Use the automated replay for citation/explain/graph checks without transcribing
IDs. Stop terminal A with Ctrl-C. All local state disappears on restart. The demo
key is public, not a production credential. Record the final video after reviewing
the report and deciding which final implementation to demonstrate.

## Original synthetic profile (preserved)


Profile: **NON-PRODUCTION, synthetic data, no paid model, no AWS access**.
The five named researchers and three coauthor edges are fictional fixtures,
not assertions about those real-world names. The answer synthesizer is a
fixed template and embeddings are hashed-token stand-ins. There are no semantic
documents in this demo. It does not establish production RAG or cloud deployment.

## Prerequisites

JDK 17 (verified with Temurin 17.0.17), sbt 1.11.7, Python 3, and a POSIX shell.
Initial staging may require access to Maven repositories. Dependencies and tool
versions are pinned in `project/versions.properties`. Do not supply AWS credentials.

From the repository root:

```sh
bash scripts/local-demo.sh --stage
python3 scripts/demo-smoke.py
```

The replay starts a staged JVM on an ephemeral IPv4 loopback port, verifies the
actual HTTP routes, saves response JSON, and terminates its own JVM. Expect
`DEMO SMOKE PASS`. Evidence goes to `target/local-demo-evidence/`. Copy it outside
`target/` before `sbt clean`; target directories are generated and not submitted.
Use `--output /absolute/path` to choose a persistent evidence directory.

## Interactive demonstration

In terminal A:

```sh
bash scripts/local-demo.sh --port 8080
```

The startup banner lists researcher IDs and prints NON-PRODUCTION. In terminal B:

```sh
curl -i http://127.0.0.1:8080/healthz
curl -sS http://127.0.0.1:8080/v1/query \
  -H 'Authorization: Bearer scholarchain-local-demo-key-NOT-A-SECRET' \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-0001' \
  -d '{"question":"Who are the coauthors of Ada Lovelace?","mode":"sync"}'
```

Use the automated replay for evidence/explain/graph lookups without manually
transcribing IDs. It also checks a missing-key 401, same-key replay, and abstention
for the ambiguous name John Smith. The public bearer key is a demo placeholder,
not a production credential. Repeating a key with different request content is not
a new query. Demo jobs and evidence disappear on restart.

Stop terminal A with Ctrl-C. The wrapper execs the JVM launcher; the shutdown hook
closes the listener and runtime. Only 127.0.0.1 is bound. Do not expose this demo.

## What to show and disclose

Show the repository structure, agent ownership map (`docs/agents.md`), a role file,
phase reviews and human decisions, dataset manifest, and local pipeline evidence.
Then run the HTTP replay and inspect `query.json`, `evidence.json`, `explain.json`,
`graph.json`, `unauthorized.json`, `replay.json`, and `ambiguous.json`.
The current recorded local demo run is in `docs/verification.md`.
Show an actual final check log, not just a marker. A demonstration video must still
be recorded by the student. Do not present local fakes as Neptune/DynamoDB/S3 Vectors.
