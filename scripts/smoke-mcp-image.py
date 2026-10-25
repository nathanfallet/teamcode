#!/usr/bin/env python3
"""Contract test of the MCP stdio host image (docs/mcp.md), run by the image job of the build workflow.

Starts the image as the chart will (UID 10002, read-only root file system, no capability, writable /tmp and /var/mcp
only), checks the probes and the token, then lists and calls real pinned servers through npx and uvx: the npm and
PyPI launchers, the dependency cut-off and the per-process caches all run in the actual image. It stops and removes
only the container it started.

    python3 scripts/smoke-mcp-image.py nathanfallet/teamcode-mcp:sha-<commit>
"""
import hashlib
import json
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request

NPM = ("npm", "@modelcontextprotocol/server-everything@2.0.0", "echo", {"message": "hello"})
PYPI = ("pypi", "mcp-server-fetch==2026.10.24", None, None)


def docker(*args, check=True):
    return subprocess.run(["docker", *args], check=check, capture_output=True, text=True)


def request(origin, path, token, body=None, protocol="1"):
    headers = {"Content-Type": "application/json"}
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    if protocol is not None:
        headers["TeamCode-Host-Protocol"] = protocol
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(origin + path, data=data, headers=headers, method="GET" if body is None else "POST")
    try:
        with urllib.request.urlopen(req, timeout=180) as response:
            return response.status, json.loads(response.read() or b"null")
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"null")


def wait_ready(origin, deadline):
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(origin + "/ready", timeout=5) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, ConnectionError, OSError):
            pass
        time.sleep(1)
    raise SystemExit("the host never became ready")


def main(image):
    token = secrets.token_urlsafe(36)
    container = docker(
        "run", "--detach", "--read-only", "--user", "10002:10002", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
        "--tmpfs", "/tmp:rw,nosuid,size=512m",
        # emptyDir in the chart: executable, since uv installs Python there and npm links its binaries there.
        "--tmpfs", "/var/mcp:rw,exec,nosuid,size=3g,uid=10002,gid=10002,mode=0700",
        "--env", f"TEAMCODE_MCP_TOKEN={token}", "--publish", "127.0.0.1::8090", image,
    ).stdout.strip()
    try:
        port = docker("port", container, "8090/tcp").stdout.strip().splitlines()[0].rsplit(":", 1)[1]
        origin = f"http://127.0.0.1:{port}"
        wait_ready(origin, time.time() + 90)
        assert docker("exec", container, "id", "-u").stdout.strip() == "10002", "the host must run as UID 10002"
        assert docker("exec", container, "cat", "/proc/1/comm").stdout.strip() == "tini", "tini must be PID 1"
        assert docker("exec", container, "sh", "-c", "touch /opt/x", check=False).returncode != 0, "the root file system must be read-only"
        status, body = request(origin, "/v1/status", "wrong-" + token)
        assert status == 401 and body["error"]["code"] == "unauthorized", (status, body)
        status, body = request(origin, "/v1/status", token, protocol="2")
        assert status == 400 and body["error"]["code"] == "unsupported_protocol", (status, body)
        now = int(time.time() * 1000)
        for launcher, package, tool, arguments in (NPM, PYPI):
            key = hashlib.sha256(package.encode()).hexdigest()
            spec = {"launcher": launcher, "package": package, "args": [], "env": {}, "secrets": {}, "declaredAt": now}
            started = time.time()
            status, body = request(origin, "/v1/ops", token, {"key": key, "spec": spec, "op": "list", "deadlineMs": 150_000, "maxResponseBytes": 4 << 20})
            assert status == 200 and body.get("error") is None, (status, body)
            names = [entry["name"] for entry in body["result"]["tools"]]
            print(f"{package}: {len(names)} tools, first list {time.time() - started:.1f} s (cold={body['cold']})")
            if tool:
                status, body = request(origin, "/v1/ops", token, {"key": key, "spec": spec, "op": "call", "tool": tool, "arguments": arguments, "deadlineMs": 60_000, "maxResponseBytes": 4 << 20})
                assert status == 200 and body.get("error") is None and not body["cold"], (status, body)
                print(f"{package}: {tool} answered {json.dumps(body['result'])[:120]}")
        # A declaration dated before the pinned version was published: npm's `before` refuses it.
        spec = {"launcher": "npm", "package": NPM[1], "args": [], "env": {}, "secrets": {}, "declaredAt": 1_790_812_800_000}  # 2026-10-01
        status, body = request(origin, "/v1/ops", token, {"key": "0" * 64, "spec": spec, "op": "list", "deadlineMs": 120_000, "maxResponseBytes": 1 << 20})
        assert status == 200 and body["error"]["code"] == "start_failed", (status, body)
        status, body = request(origin, "/v1/status", token)
        stderr = next(process["stderr"] for process in body["processes"] if process["key"] == "0" * 64)
        assert "with a date before" in stderr, stderr
        print("A version newer than the declaration date is refused: " + next(line for line in stderr.splitlines() if "with a date before" in line).strip())
        time.sleep(6)  # one memory reading
        status, body = request(origin, "/v1/status", token)
        # Absent fields are null: the refused key never started, so it has neither a cold start nor a memory reading.
        for process in body["processes"]:
            rss, cold = process.get("rssBytes"), process.get("coldStartMillis")
            print(f"{process['package']}: state {process['state']}, cold start {cold} ms, RSS {rss and rss >> 20} MiB")
            if process["state"] == "ready":
                assert rss, f"no memory reading for {process['package']}: the /proc probe does not work in the image"
        status, body = request(origin, "/v1/retain", token, {"keys": []})
        assert status == 200 and body["stopped"] == 2, (status, body)  # the refused key has no process
        events = [line for line in docker("logs", container).stdout.splitlines() if line.startswith("{")]
        assert any('"event":"started"' in line for line in events), "the host logs one JSON event per line on stdout"
        print("MCP host image contract verified.")
    finally:
        docker("rm", "--force", container, check=False)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    main(sys.argv[1])
