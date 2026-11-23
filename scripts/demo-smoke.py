#!/usr/bin/env python3
"""Replay the synthetic loopback demo; no AWS, models, or third-party Python packages."""
import argparse
import json
import pathlib
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
KEY = "scholarchain-local-demo-key-NOT-A-SECRET"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "target/local-demo-evidence")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryFile(mode="w+") as startup:
        process = subprocess.Popen(["bash", str(ROOT / "scripts/local-demo.sh"), "--port", "0"],
                                   cwd=ROOT, stdout=startup, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 45
            while True:
                startup.seek(0)
                banner = startup.read()
                match = re.search(r"listening=(http://127\.0\.0\.1:\d+)", banner)
                ada = re.search(r"ada Ada Lovelace: (\S+)", banner)
                if match and ada:
                    break
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Demo did not start: " + banner)
                time.sleep(0.1)
            base = match.group(1)
            # Bypass ambient proxy settings: all traffic must remain on loopback.
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            results = []
            def call(name, path, body=None, authenticated=True, idem=None, expected=200):
                headers = {"Authorization": "Bearer " + KEY} if authenticated else {}
                if body is not None:
                    headers["Content-Type"] = "application/json"
                if idem:
                    headers["Idempotency-Key"] = idem
                data = json.dumps(body).encode() if body is not None else None
                request = urllib.request.Request(base + path, data=data, headers=headers)
                try:
                    response = opener.open(request, timeout=30)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    status, response_headers, raw = response.code, dict(response.headers), response.read()
                assert status == expected, (name, status, raw.decode())
                assert response_headers.get("X-scholarchain-profile", response_headers.get("X-ScholarChain-Profile", "")).endswith("NON-PRODUCTION"), response_headers
                value = json.loads(raw)
                (args.output / (name + ".json")).write_text(json.dumps(value, indent=2) + "\n")
                results.append({"check": name, "status": status})
                return value
            call("health", "/healthz", authenticated=False)
            body = {"question": "Who are the coauthors of Ada Lovelace?", "mode": "sync"}
            answer = call("query", "/v1/query", body, idem="smoke-query-1")
            assert answer["verification"]["overall"] == "VERIFIED", answer
            assert answer["nonProductionModel"] is True
            claims = answer["answer"]["claims"]
            assert claims and all(claim["evidenceIds"] for claim in claims)
            evidence_id = claims[0]["evidenceIds"][0]
            evidence = call("evidence", "/v1/evidence/" + evidence_id)
            assert evidence["evidenceId"] == evidence_id
            call("explain", "/v1/explain/" + answer["queryId"])
            graph = call("graph", "/v1/graph/nodes/" + ada.group(1) + "/neighbors")
            assert len(graph["items"]) == 1, graph
            call("unauthorized", "/v1/query", body, authenticated=False, expected=401)
            replay = call("replay", "/v1/query", body, idem="smoke-query-1")
            assert replay["queryId"] == answer["queryId"] and replay["answer"] == answer["answer"]
            ambiguous = call("ambiguous", "/v1/query", {"question": "Who are the coauthors of John Smith?", "mode": "sync"})
            assert ambiguous["status"] == "abstained", ambiguous
            summary = {"profile": "NON-PRODUCTION synthetic fixture", "checks": results,
                       "result": "PASS", "queryId": answer["queryId"]}
            (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
            print("DEMO SMOKE PASS: query, citation, explain, graph, authentication, replay, abstention")
            print("Evidence: " + str(args.output.resolve()))
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            startup.seek(0)
            (args.output / "startup.log").write_text(startup.read())

if __name__ == "__main__":
    main()
