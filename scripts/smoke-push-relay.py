#!/usr/bin/env python3
"""Contract test of the TeamCode Push relay image (push/README.md), on the real native binary.

Starts the image read-only, as Cloud Run would (PORT set, unprivileged user), and checks the protocol of
docs/notifications.md: health, registration, a signed send, a forged signature, a copied request, an oversized body,
and a blocked instance. Outside Google Cloud the metadata server cannot be reached, so a valid send ends as `retry`
(`fcm_unreachable`): no provider is ever called. Ed25519 signing is the RFC 8032 reference algorithm, so the script
needs nothing but Python. It stops and removes only the container it started.

    ./gradlew :push:linkReleaseExecutableLinuxX64
    docker build --platform linux/amd64 -f push/Dockerfile -t teamcode-push:dev push
    python3 scripts/smoke-push-relay.py teamcode-push:dev
"""
import base64
import hashlib
import json
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request

# --- Ed25519 (RFC 8032, section 5.1), enough to sign ----------------------------------------------------------
P = 2 ** 255 - 19
L = 2 ** 252 + 27742317777372353535851937790883648493
D = -121665 * pow(121666, P - 2, P) % P
I = pow(2, (P - 1) // 4, P)


def recover_x(y, sign):
    x2 = (y * y - 1) * pow(D * y * y + 1, P - 2, P)
    x = pow(x2, (P + 3) // 8, P)
    if (x * x - x2) % P != 0:
        x = x * I % P
    if x % 2 != sign:
        x = P - x
    return x


G_Y = 4 * pow(5, P - 2, P) % P
G = (recover_x(G_Y, 0), G_Y, 1, recover_x(G_Y, 0) * G_Y % P)


def add(p, q):
    a = (p[1] - p[0]) * (q[1] - q[0]) % P
    b = (p[1] + p[0]) * (q[1] + q[0]) % P
    c = 2 * p[3] * q[3] * D % P
    d = 2 * p[2] * q[2] % P
    e, f, g, h = b - a, d - c, d + c, b + a
    return (e * f % P, g * h % P, f * g % P, e * h % P)


def mul(s, p):
    q = (0, 1, 1, 0)
    while s:
        if s & 1:
            q = add(q, p)
        p = add(p, p)
        s >>= 1
    return q


def encode(p):
    zi = pow(p[2], P - 2, P)
    x, y = p[0] * zi % P, p[1] * zi % P
    return int.to_bytes(y | ((x & 1) << 255), 32, "little")


def keypair():
    seed = secrets.token_bytes(32)
    h = hashlib.sha512(seed).digest()
    a = int.from_bytes(h[:32], "little")
    a &= (1 << 254) - 8
    a |= 1 << 254
    return (a, h[32:]), encode(mul(a, G))


def sign(private, public, message):
    a, prefix = private
    r = int.from_bytes(hashlib.sha512(prefix + message).digest(), "little") % L
    big_r = encode(mul(r, G))
    k = int.from_bytes(hashlib.sha512(big_r + public + message).digest(), "little") % L
    return big_r + int.to_bytes((r + k * a) % L, 32, "little")


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


# --- The relay ---------------------------------------------------------------------------------------------------
def docker(*args, check=True):
    return subprocess.run(["docker", *args], check=check, capture_output=True, text=True)


def post(origin, path, body, headers=None):
    data = body if isinstance(body, bytes) else json.dumps(body).encode()
    req = urllib.request.Request(origin + path, data=data, headers={"Content-Type": "application/json", **(headers or {})}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return response.status, json.loads(response.read() or b"null")
    except urllib.error.HTTPError as error:
        raw = error.read()
        return error.code, json.loads(raw) if raw.startswith(b"{") else None


def signed(private, public, instance, body, at=None):
    at = int(time.time()) if at is None else at
    digest = hashlib.sha256(body).hexdigest()
    signature = sign(private, public, f"POST\n/v1/send\n{at}\n{digest}".encode())
    return {"TeamCode-Instance": instance, "TeamCode-Timestamp": str(at), "TeamCode-Signature": b64url(signature)}


def main(image):
    private, public = keypair()
    blocked_private, blocked_public = keypair()
    instance, blocked = b64url(public), b64url(blocked_public)
    container = docker("run", "--rm", "--detach", "--platform", "linux/amd64", "--read-only", "--cap-drop", "ALL", "-p", "127.0.0.1::8080",
                       "-e", "PORT=8080", "-e", f"TEAMCODE_PUSH_BLOCKED_INSTANCES={blocked}", image).stdout.strip()
    try:
        port = docker("port", container, "8080/tcp").stdout.strip().rsplit(":", 1)[1]
        origin = f"http://127.0.0.1:{port}"
        deadline = time.monotonic() + 60
        while True:
            try:
                with urllib.request.urlopen(origin + "/healthz", timeout=5) as response:
                    assert response.read() == b"ok"
                    break
            except (urllib.error.URLError, ConnectionError):
                if time.monotonic() > deadline:
                    raise RuntimeError("The relay did not answer /healthz in 60 seconds:\n" + docker("logs", container, check=False).stdout)
                time.sleep(0.5)

        status, answer = post(origin, "/v1/instances", {"publicKey": instance})
        assert (status, answer) == (200, {"instanceId": instance}), (status, answer)
        assert post(origin, "/v1/instances", {"publicKey": "nope"})[0] == 400

        body = json.dumps({"token": "t" * 40, "platform": "ios", "payload": base64.b64encode(b"x" * 64).decode(),
                           "collapseKey": "0123456789abcdef", "ttlSeconds": 3600}).encode()
        headers = signed(private, public, instance, body)
        status, answer = post(origin, "/v1/send", body, headers)
        # Outside Google Cloud the metadata server cannot be reached: the push is worth retrying, never lost silently.
        assert (status, answer["status"], answer.get("error")) == (200, "retry", "fcm_unreachable"), (status, answer)
        # The same request again is answered without a second attempt.
        replay = post(origin, "/v1/send", body, headers)
        assert replay[0] == 200 and replay[1]["status"] == "sent", replay

        forged = dict(signed(private, public, instance, body), **{"TeamCode-Signature": b64url(sign(blocked_private, blocked_public, b"other"))})
        assert post(origin, "/v1/send", body, forged)[0] == 401
        stale = signed(private, public, instance, body, int(time.time()) - 600)
        status, answer = post(origin, "/v1/send", body, stale)
        assert (status, answer["status"], answer["error"]) == (401, "rejected", "stale_request"), (status, answer)
        assert post(origin, "/v1/send", body, signed(blocked_private, blocked_public, blocked, body))[0] == 403
        assert post(origin, "/v1/send", b"x" * 9000, signed(private, public, instance, b"x" * 9000))[0] == 413
        logs = docker("logs", container, check=False)
        assert "t" * 40 not in logs.stdout + logs.stderr, "the token is never logged"
        print("The relay image speaks push.v1.")
    finally:
        docker("stop", container, check=False)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
