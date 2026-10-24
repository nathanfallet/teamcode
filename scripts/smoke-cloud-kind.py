#!/usr/bin/env python3
"""Opt-in cloud-session smoke on a local kind cluster. Not part of pull-request CI.

Requires docker, kind and kubectl on PATH. Creates a throwaway cluster, a least-privilege service account for
the TeamCode server, and a stand-in runner image, then runs KindSmokeTest (real reconciler, stores and
Kubernetes adapter against the cluster) and deletes the cluster.

    python3 scripts/smoke-cloud-kind.py [--keep]
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import textwrap
import time

CLUSTER = "teamcode-smoke"
NAMESPACE = "teamcode-smoke"
IMAGE = "teamcode-smoke-runner:1"

RBAC = f"""
apiVersion: v1
kind: Namespace
metadata: {{name: teamcode-system}}
---
apiVersion: v1
kind: Namespace
metadata: {{name: {NAMESPACE}}}
---
apiVersion: v1
kind: ServiceAccount
metadata: {{name: teamcode-server, namespace: teamcode-system}}
---
# Session objects only in the allowed namespace.
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: {{name: teamcode-sessions, namespace: {NAMESPACE}}}
rules:
- apiGroups: [""]
  resources: [pods, persistentvolumeclaims]
  verbs: [get, list, create, delete]
# The session network policy, applied by server-side apply before every pod (creating it needs `create`).
- apiGroups: [networking.k8s.io]
  resources: [networkpolicies]
  verbs: [create, patch]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: {{name: teamcode-sessions, namespace: {NAMESPACE}}}
roleRef: {{apiGroup: rbac.authorization.k8s.io, kind: Role, name: teamcode-sessions}}
subjects: [{{kind: ServiceAccount, name: teamcode-server, namespace: teamcode-system}}]
---
# Read-only cluster scope: deletion proof (PersistentVolumes) and the storage-class check.
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata: {{name: teamcode-volumes}}
rules:
- apiGroups: [""]
  resources: [persistentvolumes]
  verbs: [get, list]
- apiGroups: [storage.k8s.io]
  resources: [storageclasses]
  verbs: [get, list]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata: {{name: teamcode-volumes}}
roleRef: {{apiGroup: rbac.authorization.k8s.io, kind: ClusterRole, name: teamcode-volumes}}
subjects: [{{kind: ServiceAccount, name: teamcode-server, namespace: teamcode-system}}]
---
# Optional: the profile form marks allowed namespaces that do not exist. Limited to the allowed namespace.
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata: {{name: teamcode-namespaces}}
rules:
- apiGroups: [""]
  resources: [namespaces]
  resourceNames: [{NAMESPACE}]
  verbs: [get]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata: {{name: teamcode-namespaces}}
roleRef: {{apiGroup: rbac.authorization.k8s.io, kind: ClusterRole, name: teamcode-namespaces}}
subjects: [{{kind: ServiceAccount, name: teamcode-server, namespace: teamcode-system}}]
---
# Optional: the profile form offers the namespace's image pull secrets. Listing lets the account read secrets;
# TeamCode lists them as metadata only and keeps their names.
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: {{name: teamcode-pull-secrets, namespace: {NAMESPACE}}}
rules:
- apiGroups: [""]
  resources: [secrets]
  verbs: [list]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: {{name: teamcode-pull-secrets, namespace: {NAMESPACE}}}
roleRef: {{apiGroup: rbac.authorization.k8s.io, kind: Role, name: teamcode-pull-secrets}}
subjects: [{{kind: ServiceAccount, name: teamcode-server, namespace: teamcode-system}}]
---
# What the profile form must offer (the registry secret) and must not (an opaque one).
apiVersion: v1
kind: Secret
metadata: {{name: smoke-registry, namespace: {NAMESPACE}}}
type: kubernetes.io/dockerconfigjson
stringData: {{.dockerconfigjson: '{{"auths":{{}}}}'}}
---
apiVersion: v1
kind: Secret
metadata: {{name: smoke-opaque, namespace: {NAMESPACE}}}
type: Opaque
stringData: {{value: not-a-registry}}
---
# TokenReview for runner authentication.
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata: {{name: teamcode-token-review}}
roleRef: {{apiGroup: rbac.authorization.k8s.io, kind: ClusterRole, name: system:auth-delegator}}
subjects: [{{kind: ServiceAccount, name: teamcode-server, namespace: teamcode-system}}]
"""

# The stand-in runner: the init container copies it like the real binary; the session container runs it.
DOCKERFILE = textwrap.dedent("""\
    FROM busybox:1.36.1
    RUN mkdir -p /usr/local/bin && printf '#!/bin/sh\\nexec sleep 3600\\n' > /usr/local/bin/teamcode && chmod 755 /usr/local/bin/teamcode
""")


# A session-like pod (TeamCode's label, so the policy selects it) and a control pod without it.
PROBES = f"""
apiVersion: v1
kind: Pod
metadata: {{name: probe-session, namespace: {NAMESPACE}, labels: {{app.kubernetes.io/managed-by: teamcode}}}}
spec: {{containers: [{{name: probe, image: {IMAGE}, imagePullPolicy: Never, command: [sleep, "600"]}}]}}
---
apiVersion: v1
kind: Pod
metadata: {{name: probe-control, namespace: {NAMESPACE}}}
spec: {{containers: [{{name: probe, image: {IMAGE}, imagePullPolicy: Never, command: [sleep, "600"]}}]}}
"""


def check_network_policy(kubectl):
    """The policy the server applied exists and is enforced: a session pod resolves names but reaches neither the API
    server's service, a node, nor the metadata address; the control pod reaches the service, so the CNI enforces."""
    policy = json.loads(run(*kubectl, "get", "networkpolicy", "teamcode-sessions", "-n", NAMESPACE, "-o", "json", capture=True))
    assert policy["spec"]["podSelector"] == {"matchLabels": {"app.kubernetes.io/managed-by": "teamcode"}}, policy["spec"]
    run(*kubectl, "apply", "-f", "-", stdin=PROBES)
    run(*kubectl, "wait", "--for=condition=Ready", "pod/probe-session", "pod/probe-control", "-n", NAMESPACE, "--timeout=120s")
    service = run(*kubectl, "get", "service", "kubernetes", "-n", "default", "-o", "jsonpath={.spec.clusterIP}", capture=True)
    node = run(*kubectl, "get", "nodes", "-o", "jsonpath={.items[0].status.addresses[?(@.type=='InternalIP')].address}", capture=True)

    def reaches(pod, address, port):
        probe = subprocess.run([*kubectl, "exec", "-n", NAMESPACE, pod, "--", "nc", "-w", "3", address, str(port)],
                               stdin=subprocess.DEVNULL, capture_output=True, text=True)
        return probe.returncode == 0

    def resolves(pod):
        return subprocess.run([*kubectl, "exec", "-n", NAMESPACE, pod, "--", "nslookup", "kubernetes.default.svc.cluster.local"],
                              capture_output=True, text=True).returncode == 0

    if not reaches("probe-control", service, 443):
        sys.exit("Kind smoke failed: the control pod cannot reach the API server, so enforcement cannot be told apart.")
    def blocked(address, port):
        # The CNI programs a new pod's address into the policy a few seconds after the pod is ready (kindnet: up to
        # about 5 s): wait for it to take effect, then the address must stay unreachable.
        deadline = time.monotonic() + 60
        while reaches("probe-session", address, port):
            if time.monotonic() > deadline:
                return False
            time.sleep(2)
        return not reaches("probe-session", address, port)

    failures = [name for name, ok in [
        ("a session pod cannot reach the API server's service", blocked(service, 443)),
        ("a session pod cannot reach the node", blocked(node, 6443)),
        ("a session pod cannot reach the metadata address", blocked("169.254.169.254", 80)),
        ("DNS resolves from a session pod", resolves("probe-session")),
    ] if not ok]
    if failures:
        sys.exit("Kind smoke failed: " + "; ".join(failures))
    run(*kubectl, "delete", "pod", "probe-session", "probe-control", "-n", NAMESPACE, "--wait=false")


def run(*command, capture=False, stdin=None, env=None):
    result = subprocess.run(command, check=True, text=True, input=stdin, env=env,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else None


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--keep", action="store_true", help="Keep the kind cluster afterwards.")
    args = parser.parse_args()
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    with tempfile.TemporaryDirectory(prefix="teamcode-kind-") as work:
        kubeconfig = os.path.join(work, "kubeconfig")
        run("kind", "create", "cluster", "--name", CLUSTER, "--kubeconfig", kubeconfig, "--wait", "120s")
        try:
            kubectl = ("kubectl", "--kubeconfig", kubeconfig)
            build = os.path.join(work, "runner-image")
            os.makedirs(build)
            with open(os.path.join(build, "Dockerfile"), "w") as handle:
                handle.write(DOCKERFILE)
            run("docker", "build", "-q", "-t", IMAGE, build)
            run("kind", "load", "docker-image", IMAGE, "--name", CLUSTER)
            # A resumed session pins the image digest recorded by its first pod. A registry serves digests, but kind
            # imports the image under its tag only, so containerd would try to pull `name@sha256:...` from Docker Hub.
            # Give the imported image its digest name on the node, as a registry pull would have.
            node = f"{CLUSTER}-control-plane"
            inspected = json.loads(run("docker", "exec", node, "crictl", "inspecti", f"docker.io/library/{IMAGE}", capture=True))
            # Name the image by every digest the runtime knows for it (its ID and its repository digests, among them
            # the one the kubelet reports as the pod's image ID), so any of them resolves locally.
            if not inspected["status"].get("repoDigests"):
                sys.exit("Kind smoke setup failed: the node reports no repository digest for the imported image.")
            digests = {inspected["status"]["id"]} | {d.split("@")[1] for d in inspected["status"]["repoDigests"] if "@" in d}
            for digest in sorted(digests):
                # With and without the tag: the server keeps the tag of the image reference when it pins the digest.
                for name in (f"docker.io/library/{IMAGE.split(':')[0]}@{digest}", f"docker.io/library/{IMAGE}@{digest}"):
                    run("docker", "exec", node, "ctr", "-n", "k8s.io", "images", "tag", "--force", f"docker.io/library/{IMAGE}", name)
            run(*kubectl, "apply", "-f", "-", stdin=RBAC)
            token = run(*kubectl, "create", "token", "teamcode-server", "-n", "teamcode-system", "--duration", "2h", capture=True)
            config = json.loads(run(*kubectl, "config", "view", "--raw", "--minify", "-o", "json", capture=True))
            server = config["clusters"][0]["cluster"]
            files = {
                "api-url": server["server"],
                "ca.pem": base64.b64decode(server["certificate-authority-data"]).decode(),
                "token": token,
                "namespace": NAMESPACE,
                "image": IMAGE,
            }
            for name, value in files.items():
                path = os.path.join(work, name)
                with open(path, "w") as handle:
                    handle.write(value)
                os.chmod(path, 0o600)
            env = dict(os.environ, TEAMCODE_KIND_SMOKE_DIR=work)
            run(os.path.join(root, "gradlew"), "--no-daemon", "-p", root, ":infrastructure:test", "--tests", "*KindSmokeTest*", "--rerun", env=env)
            check_network_policy(kubectl)
            print("Kind smoke passed: profile choices, claim, pod, TokenReview, pause, resume into a new pod with two replicas, proven deletion, "
                  "and the session network policy (applied with create and patch only, DNS allowed, API server, node and metadata address unreachable) on a real cluster.")
        finally:
            if not args.keep:
                run("kind", "delete", "cluster", "--name", CLUSTER, "--kubeconfig", kubeconfig)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.exit(f"Kind smoke failed at: {' '.join(error.cmd[:3])}")
