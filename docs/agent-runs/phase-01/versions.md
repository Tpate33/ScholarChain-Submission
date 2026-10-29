# Phase 1 tool versions

Captured 2026-10-28 by the orchestrator in the post-restart probe session, using the orchestrator's
allowlisted version commands only. Output is verbatim.

Repository HEAD at capture: `d23d307c0557a3979419ab0b63cf283c96664f02` (Phase 1 constitution not yet committed).

| Tool | Command | Observed output |
|---|---|---|
| Claude Code | `claude --version` | `2.1.312 (Claude Code)` |
| Model configuration | (session metadata) | Orchestrator: Claude Opus 5.5 (`claude-opus-5-5`). Subagents inherit the session model unless their role file says otherwise. |
| JVM | `java -version` | `openjdk version "17.0.17" 2025-10-21` / `OpenJDK Runtime Environment Temurin-17.0.17+10 (build 17.0.17+10)` / `OpenJDK 64-Bit Server VM Temurin-17.0.17+10 (build 17.0.17+10, mixed mode, sharing)` |
| sbt | `sbt --version` | **Not installed**: `(eval):1: command not found: sbt` (exit 127) |
| Scala | n/a | No build exists yet (pre-Phase 2). Recorded by build-engineer in Phase 2. |
| Terraform | `terraform version` | **Not installed**: `(eval):1: command not found: terraform` (exit 127) |
| AWS CLI | `aws --version` | `aws-cli/2.34.61 Python/3.14.5 Darwin/25.6.0 exe/arm64` |
| Python | `python3 --version` | `Python 3.12.5` (required by the guard decision core) |
| scalafmt | n/a | Not checked: no allowlisted orchestrator command. Needed by `format-scala.sh` once Scala source exists. |
| shellcheck | (from `validate-agent-files.sh`) | `NOTE  shellcheck not installed; skipped (bash -n still enforced)` |

## MCP servers at session start (reported by Claude Code)

| Server | Status |
|---|---|
| `aws-api` | failed to connect: `uvx` not found on PATH |
| `aws-docs` | failed to connect: `uvx` not found on PATH |
| `github` | failed to connect: connection closed (Docker server) |
| `terraform` | failed to connect: connection closed (Docker server) |

None of the Phase 1 probes depends on an MCP server.

## Prerequisites missing before later phases

- `sbt`: required before Phase 2 (`sbt check` is the definition of done).
- `scalafmt`: required once Scala source exists (Phase 2 onward).
- `terraform`: required before Phase 12.
- `uvx` (uv) and a running Docker daemon: required for the MCP servers.
- `shellcheck`: optional; the validator skips it when absent.
