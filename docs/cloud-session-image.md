# Cloud session image

A [cloud session](cloud-sessions.md) runs in a pod built from two images:

| Setting | Where | What it is |
| --- | --- | --- |
| Session image | The profile's `image` (`/api/v1/cloud/profiles`), or a session override allowed by `allowedImageOverrides` | The image of the pod's two containers: the runner (`session`) and the agent (`agent`). Your toolchain lives here. |
| Runner image and runner binary | **Dashboard → Cloud sessions** | An image holding the `teamcode` Linux binary. An init container copies the binary out of it into the pod before the other containers start. |

[`cloud-session-image/Dockerfile`](cloud-session-image/Dockerfile) is a template for the session image. It installs what TeamCode needs, and leaves one block for your tools. It also puts the `teamcode` binary at `/usr/local/bin/teamcode`, so the same image can serve as the runner image: there is then one image to build, pin and pull.

## What the pod does, and what the image does not have to

The server builds the pod spec itself (see [the runner and the pod](cloud-sessions.md#the-runner-and-the-pod)). Nothing below comes from the image:

- **Command.** The spec sets the command of every container: `cp <runner binary> /opt/teamcode/teamcode` in the init container, `/opt/teamcode/teamcode cloud runner` in `session` and `/opt/teamcode/teamcode cloud agent-supervisor` in `agent`. An `ENTRYPOINT` or `CMD` in the image is never used.
- **Authentication.** The pod authenticates to the server with a projected Kubernetes service-account token (audience `teamcode`, 600 seconds, rotated by the kubelet), mounted in the `session` container only. The server checks it with a `TokenReview` and, from it, hands the runner the inference credential, the owner's variables and the owner's GitHub token at run time. The agent reaches models through the runner's loopback proxy. No TeamCode token, provider key, GitHub token or kubeconfig belongs in the image.
- **Users.** The spec runs the runner as UID 1000 and the agent as UID 1001, both with group 1000 (`fsGroup` 1000), non-root, without privilege escalation. `HOME` is `/workspace/.home` for the runner and `/workspace/.agent-home` for the agent, on the workspace volume mounted at `/workspace`.
- **The binary.** The pod runs the copy of `teamcode` made by the init container, from the runner image. The session image needs none unless it is also the runner image.

## What the image must provide

The template installs all of this before your block and checks it after:

- Linux with glibc, `/bin/sh` and the POSIX commands the runner uses (`mkdir`, `rm`, `mv`, `rmdir`, `dirname`, `touch`, `test`).
- The libraries the `teamcode` binary loads, since both containers run it: `libcrypt.so.1`, OpenSSL `libcrypto` 3, libcurl and zlib.
- `git` 2.31 or later. Git and GitHub credentials are set by the pod (`GIT_CONFIG_*` and a credential helper backed by the runner), not by the image.
- The agent CLIs, found by name on `PATH`: `claude` (Claude Code) and `codex` (Codex), as the profile's agent kind requires. They are installed system-wide with Node.js; Claude Code's self-update is turned off, so the image's version is the one that runs.
- Named accounts for the pod's UIDs: `runner` (1000) and `agent` (1001) in group `teamcode` (1000). The pod sets the UIDs itself; the names keep tools that look up the current user working. A base image's own user or group 1000 (the `ubuntu` user of `ubuntu:24.04`) is removed first.

## Adding your tools

Put your setup between the two markers and leave the rest of the file as it is:

```dockerfile
# >>> Insert your tools setup here <<<
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-21-jdk-headless python3 \
    && rm -rf /var/lib/apt/lists/*
# <<< End of your tools setup >>>
```

- The block runs as root, with the default `/bin/sh -c` (set `SHELL` inside the block for another shell). Install system-wide (`/usr`, `/usr/local`, `/opt`) and keep files readable and executable by UIDs 1000 and 1001. In the pod, `HOME` is on the workspace volume, so anything installed under a home directory at build time (`~/.local`, `~/.sdkman`, `~/.nvm`) is not there.
- Do not add credentials, registry logins or tokens: the image is pulled by the cluster and readable by anyone who can pull it. Per-member values reach the runner at run time, as the owner's variables.
- Repository-specific setup (dependency installs, generated files) belongs in the profile's environment recipe (`setup.create`, `setup.start`), which runs in the workspace for each session, not in the image.
- The contract check after the block fails the build if your setup broke something TeamCode needs, for example by removing `git` or replacing a library.

## Building

```sh
docker build \
  --build-arg TEAMCODE_VERSION=0.3.2 \
  -t registry.example.org/teamcode-session:2026-10-21 \
  docs/cloud-session-image
```

| Build argument | Default | Purpose |
| --- | --- | --- |
| `TEAMCODE_VERSION` | the release the template was last updated for | The release whose `teamcode` binary goes into the image, checked against that release's `SHA256SUMS`. Use your server's version. |
| `NODE_VERSION` | an LTS release | Node.js for the agent CLIs. |
| `CLAUDE_CODE_VERSION` | a recent release | `@anthropic-ai/claude-code`. Empty leaves Claude Code out. |
| `CODEX_VERSION` | a recent release | `@openai/codex`. Empty leaves Codex out. With both empty, Node.js is not installed. |
| `BASE_IMAGE` | `ubuntu:24.04` | A Debian or Ubuntu base. Others need the package step adapted. |

The template builds for `linux/amd64` and `linux/arm64` (`docker buildx build --platform linux/amd64,linux/arm64`); the `teamcode` release has a binary for each.

## Using it

1. Push the image to a registry the cluster can pull from. A private registry needs a pull secret in the session namespace, named in the profile's `imagePullSecrets`.
2. Set the profile's `image` to it. Pin it by digest (`image@sha256:...`), or let TeamCode pin it: a session records the digest of its first pod and every later pod of the session uses that digest.
3. To use the same image as the runner image, set **Dashboard → Cloud sessions → Runner image** to the same reference, pinned by digest, and leave the runner binary at `/usr/local/bin/teamcode`. The init container is pulled with the profile's `imagePullSecrets` too. When you upgrade the server, rebuild with the new `TEAMCODE_VERSION` and update the runner image.
