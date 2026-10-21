# syntax=docker/dockerfile:1
#
# Template for a TeamCode cloud session image. See docs/cloud-session-image.md.
#
# Copy this file, put your tools in the block marked "Insert your tools setup here" and leave the rest
# as it is. The rest is what TeamCode needs from the image:
#
#   - the libraries and commands the copied `teamcode` binary and the runner use (glibc, libcrypt,
#     libcrypto, libcurl, zlib, /bin/sh, coreutils, git 2.31 or later);
#   - the agent CLIs the runner starts by name (`claude`, `codex`), installed system-wide, since
#     HOME is on the workspace volume in the pod;
#   - named accounts for the two fixed UIDs of the pod (runner 1000, agent 1001, group 1000);
#   - the `teamcode` Linux binary at /usr/local/bin/teamcode, so this image can also be the runner
#     image of Dashboard -> Cloud sessions.
#
# What the image must NOT contain: an entrypoint the pod relies on (the pod spec sets the command of
# every container), a TeamCode token, a provider or GitHub credential, or a kubeconfig. The server
# authenticates the pod with a projected Kubernetes service-account token and hands every credential to
# the runner at run time.
#
# Build: docker build --build-arg TEAMCODE_VERSION=<server version> -t <registry>/<image>:<tag> .

ARG BASE_IMAGE=ubuntu:24.04

FROM ${BASE_IMAGE}

# The TeamCode release whose binary goes into the image. Use your server's version.
ARG TEAMCODE_VERSION=0.3.2
# Node.js runtime for the agent CLIs (an LTS line). Empty with both agent versions empty skips Node.js.
ARG NODE_VERSION=24.21.0
# Agent CLIs. Empty skips one; pin exact versions so a rebuild gives the same agent.
ARG CLAUDE_CODE_VERSION=2.1.306
ARG CODEX_VERSION=0.163.0
# Set by BuildKit from --platform; the legacy builder leaves it empty, which means amd64.
ARG TARGETARCH

ARG DEBIAN_FRONTEND=noninteractive
# DISABLE_AUTOUPDATER: Claude Code must not update itself in a pod, the image pins its version.
ENV LANG=C.UTF-8 \
    DISABLE_AUTOUPDATER=1

SHELL ["/bin/sh", "-eu", "-c"]

# --- TeamCode requirements (do not edit) ---------------------------------------------------------

# Runtime libraries of the teamcode binary, git, and what the steps below download with.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       ca-certificates curl git libcrypt1 openssl xz-utils zlib1g \
       "$(apt-cache show libcurl4t64 >/dev/null 2>&1 && echo libcurl4t64 || echo libcurl4)" \
       "$(apt-cache show libssl3t64 >/dev/null 2>&1 && echo libssl3t64 || echo libssl3)" \
    && rm -rf /var/lib/apt/lists/*

# The teamcode binary, checked against the release's SHA256SUMS.
RUN case "${TARGETARCH:-amd64}" in amd64) arch=x64 ;; arm64) arch=arm64 ;; *) echo "Unsupported architecture ${TARGETARCH:-amd64}" >&2; exit 1 ;; esac \
    && asset="teamcode-${TEAMCODE_VERSION}-linux-${arch}" \
    && base="https://github.com/nathanfallet/teamcode/releases/download/${TEAMCODE_VERSION}" \
    && cd /tmp \
    && curl -fsSLO --retry 3 "${base}/${asset}" \
    && curl -fsSLO --retry 3 "${base}/SHA256SUMS" \
    && grep " ${asset}\$" SHA256SUMS | sha256sum -c - \
    && install -m 0755 "${asset}" /usr/local/bin/teamcode \
    && rm -f "${asset}" SHA256SUMS

# Node.js and the agent CLIs, system-wide under /usr/local.
RUN if [ -z "${CLAUDE_CODE_VERSION}${CODEX_VERSION}" ]; then exit 0; fi \
    && case "${TARGETARCH:-amd64}" in amd64) arch=x64 ;; arm64) arch=arm64 ;; *) echo "Unsupported architecture ${TARGETARCH:-amd64}" >&2; exit 1 ;; esac \
    && archive="node-v${NODE_VERSION}-linux-${arch}.tar.xz" \
    && cd /tmp \
    && curl -fsSLO --retry 3 "https://nodejs.org/dist/v${NODE_VERSION}/${archive}" \
    && curl -fsSLO --retry 3 "https://nodejs.org/dist/v${NODE_VERSION}/SHASUMS256.txt" \
    && grep " ${archive}\$" SHASUMS256.txt | sha256sum -c - \
    && tar -xJf "${archive}" -C /usr/local --strip-components=1 --no-same-owner \
    && rm -f "${archive}" SHASUMS256.txt \
    && packages="" \
    && if [ -n "${CLAUDE_CODE_VERSION}" ]; then packages="${packages} @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}"; fi \
    && if [ -n "${CODEX_VERSION}" ]; then packages="${packages} @openai/codex@${CODEX_VERSION}"; fi \
    && npm install --global --no-fund --no-audit ${packages} \
    && npm cache clean --force \
    && rm -rf /root/.npm /tmp/*

# Accounts for the pod's fixed UIDs: `runner` (1000) and `agent` (1001), both in group `teamcode`
# (1000). The pod sets the UIDs itself; the names keep tools that look up the current user working.
# A base image's own UID or GID 1000 (the `ubuntu` user of ubuntu:24.04) is removed first.
RUN for uid in 1000 1001; do \
        user="$(getent passwd "${uid}" | cut -d: -f1)"; \
        if [ -n "${user}" ]; then userdel "${user}"; fi; \
    done \
    && group="$(getent group 1000 | cut -d: -f1)" \
    && if [ -n "${group}" ]; then groupdel "${group}"; fi \
    && groupadd --gid 1000 teamcode \
    && useradd --uid 1000 --gid 1000 --no-create-home --home-dir /workspace/.home --shell /bin/bash runner \
    && useradd --uid 1001 --gid 1000 --no-create-home --home-dir /workspace/.agent-home --shell /bin/bash agent

# --- Your tools ----------------------------------------------------------------------------------
# Runs as root. Install system-wide (/usr, /usr/local, /opt) and keep everything readable and
# executable by UIDs 1000 and 1001: in the pod, HOME is on the workspace volume, so anything installed
# under a home directory here is not visible there. Do not add credentials.

# >>> Insert your tools setup here <<<

# <<< End of your tools setup >>>

# --- TeamCode contract check (do not edit) -------------------------------------------------------

# Fails the build when the image no longer gives TeamCode what it needs, for example when a tools
# setup removed git or replaced a library the binary loads.
RUN as_agent() { setpriv --reuid=1001 --regid=1000 --clear-groups env HOME=/tmp "$@" >/dev/null; }; \
    for command in sh mkdir rm mv rmdir dirname touch test git; do \
        command -v "${command}" >/dev/null || { echo "Missing ${command}" >&2; exit 1; }; \
    done \
    && git_version="$(git --version | sed 's/^git version //')" \
    && if ! printf '2.31.0\n%s\n' "${git_version}" | sort -C -V; then echo "git ${git_version} is older than 2.31" >&2; exit 1; fi \
    && [ "$(id -u runner):$(id -g runner):$(id -u agent):$(id -g agent)" = "1000:1000:1001:1000" ] \
    && as_agent /usr/local/bin/teamcode version \
    && if [ -n "${CLAUDE_CODE_VERSION}" ]; then as_agent claude --version; fi \
    && if [ -n "${CODEX_VERSION}" ]; then as_agent codex --version; fi \
    && rm -rf /tmp/* /tmp/.[!.]*

WORKDIR /workspace
USER 1001:1000
