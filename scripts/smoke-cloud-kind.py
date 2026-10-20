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
    RUN printf '#!/bin/sh\\nexec sleep 3600\\n' > /usr/local/bin/teamcode && chmod 755 /usr/local/bin/teamcode
""")


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
            print("Kind smoke passed: claim, pod, TokenReview, pause and proven deletion on a real cluster.")
        finally:
            if not args.keep:
                run("kind", "delete", "cluster", "--name", CLUSTER, "--kubeconfig", kubeconfig)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.exit(f"Kind smoke failed at: {' '.join(error.cmd[:3])}")
