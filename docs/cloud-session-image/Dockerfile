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
ARG TEAMCODE_VERSION=1.0.1
# Node.js runtime for the agent CLIs (an LTS line). Empty with both agent versions empty skips Node.js.
ARG NODE_VERSION=24.21.0
# The Node.js release keyring (gpg/pubring.kbx of github.com/nodejs/release-keys, at this commit) and its SHA-256:
# SHASUMS256.txt must be signed by one of its keys, so nodejs.org alone cannot change Node.js. A Node.js bump needs no
# change here; a release signed by a key the keyring lacks fails the build, and moving to a newer keyring commit
# changes both values (the keys are the releasers listed in the README of nodejs/node).
ARG NODE_RELEASE_KEYS_COMMIT=481637f813e912c4aa3622d7964ab426c97b8e8d
ARG NODE_RELEASE_KEYS_SHA256=610b8d249da3d5733f5a128def2dd0294dbbf5b5713e6ca2529db8db419dee00
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
       ca-certificates curl git gpgv libcrypt1 openssl xz-utils zlib1g \
       "$(apt-cache show libcurl4t64 >/dev/null 2>&1 && echo libcurl4t64 || echo libcurl4)" \
       "$(apt-cache show libssl3t64 >/dev/null 2>&1 && echo libssl3t64 || echo libssl3)" \
    && rm -rf /var/lib/apt/lists/*

# The TeamCode publisher's public key (release/trust/teamcode-release-2026.pem in the TeamCode repository). The
# release's manifest.sig is checked against it, so replacing the binary and SHA256SUMS of a release is not enough to
# put another binary in this image. A fork that signs its own releases puts its own key here.
COPY <<'EOF' /usr/local/share/teamcode/release-2026.pem
-----BEGIN PUBLIC KEY-----
MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAvt26hBXU8nyHnq0pVU3C
iMjMPUDKTVahWc4zCNGnxfbALmp2viekOOAMQXbhMLqR0//OQ/EpRoiwsLLqHNq5
Lt03Btv6ie30W2JXYCNwS6dG6bbusVVsJvf1WDTnmap2Pq19jSvTqnlmi+Cuk/Lg
GTHYVakpf98fCTzlI1cNulYgZfxckSTG3p9FQIOzHENdcfsCysfYylvLhOMjCccB
TC7A3Fof4NPS2HJkA47FdCKGwU8hvhe9YcR7mKIqki6QcCHwwpe8jtXX2X5e+Wte
7Rhj1pTqDKBJtNqOoh/cD97w9w7u0c2U+d1jTxHBItnKRwuiXzM0c0lZdhog/JSZ
G5poqSSil4R0kMuosQe1A887TIMoBvf5vB0SMyduxFdZuvAji11Emfgnz0E2qZFC
UfWQVUQEtVuJDzGLSqT2Z1rMc0vu6AQ8FHYM7lgvLyTbEU8LM/+PgAKI6K/K0EOf
shLnIvBbcl+AvatfS3UpbBbkU4CJsi4FV7/Z78FDmoUpAgMBAAE=
-----END PUBLIC KEY-----
EOF

# The teamcode binary: manifest.sig must name the publisher key above by its keyId (SHA-256 of the key's DER
# SubjectPublicKeyInfo), manifest.json must carry a valid RSA-PSS signature of that key, name this version, and list
# the binary's SHA-256. Another keyId stops the build with both ids: after a key rotation of the publisher, take the
# new public key from release/trust/ of the TeamCode repository (it ships there in a release signed by the old key,
# docs/distribution.md) and put it above in place of the old one.
RUN case "${TARGETARCH:-amd64}" in amd64) arch=x64 ;; arm64) arch=arm64 ;; *) echo "Unsupported architecture ${TARGETARCH:-amd64}" >&2; exit 1 ;; esac \
    && asset="teamcode-${TEAMCODE_VERSION}-linux-${arch}" \
    && base="https://github.com/nathanfallet/teamcode/releases/download/${TEAMCODE_VERSION}" \
    && cd /tmp \
    && curl -fsSLO --retry 3 "${base}/${asset}" \
    && curl -fsSLO --retry 3 "${base}/manifest.json" \
    && curl -fsSLO --retry 3 "${base}/manifest.sig" \
    && trusted="$(openssl pkey -pubin -in /usr/local/share/teamcode/release-2026.pem -outform DER | sha256sum | cut -d ' ' -f 1)" \
    && signer="$(sed -n 's/.*"keyId": "\([0-9a-f]*\)".*/\1/p' manifest.sig)" \
    && if [ "${signer}" != "${trusted}" ]; then \
         echo "manifest.sig of TeamCode ${TEAMCODE_VERSION} is signed by the key ${signer:-(none)}, this image trusts ${trusted} (release-2026.pem): a rotated publisher key goes in place of release-2026.pem, see the comment above" >&2; \
         exit 1; \
       fi \
    && sed -n 's/.*"signature": "\([A-Za-z0-9+\/=]*\)".*/\1/p' manifest.sig | base64 -d > manifest.sig.bin \
    && openssl dgst -sha256 -sigopt rsa_padding_mode:pss -sigopt rsa_pss_saltlen:32 -sigopt rsa_mgf1_md:sha256 \
       -verify /usr/local/share/teamcode/release-2026.pem -signature manifest.sig.bin manifest.json \
    && grep -q "^  \"tag\": \"${TEAMCODE_VERSION}\",\$" manifest.json \
    && grep -q "\"sha256\": \"$(sha256sum "${asset}" | cut -d ' ' -f 1)\"" manifest.json \
    && install -m 0755 "${asset}" /usr/local/bin/teamcode \
    && rm -f "${asset}" manifest.json manifest.sig manifest.sig.bin

# Node.js and the agent CLIs, system-wide under /usr/local.
RUN if [ -z "${CLAUDE_CODE_VERSION}${CODEX_VERSION}" ]; then exit 0; fi \
    && case "${TARGETARCH:-amd64}" in amd64) arch=x64 ;; arm64) arch=arm64 ;; *) echo "Unsupported architecture ${TARGETARCH:-amd64}" >&2; exit 1 ;; esac \
    && archive="node-v${NODE_VERSION}-linux-${arch}.tar.xz" \
    && cd /tmp \
    && curl -fsSLO --retry 3 "https://nodejs.org/dist/v${NODE_VERSION}/${archive}" \
    && curl -fsSLO --retry 3 "https://nodejs.org/dist/v${NODE_VERSION}/SHASUMS256.txt" \
    && curl -fsSLO --retry 3 "https://nodejs.org/dist/v${NODE_VERSION}/SHASUMS256.txt.sig" \
    && curl -fsSL --retry 3 -o node-release-keys.kbx "https://raw.githubusercontent.com/nodejs/release-keys/${NODE_RELEASE_KEYS_COMMIT}/gpg/pubring.kbx" \
    && if ! printf '%s  node-release-keys.kbx\n' "${NODE_RELEASE_KEYS_SHA256}" | sha256sum -c - >/dev/null 2>&1; then \
         echo "The Node.js release keyring at ${NODE_RELEASE_KEYS_COMMIT} does not match NODE_RELEASE_KEYS_SHA256" >&2; exit 1; \
       fi \
    && if ! gpgv --keyring /tmp/node-release-keys.kbx SHASUMS256.txt.sig SHASUMS256.txt; then \
         echo "SHASUMS256.txt of Node.js ${NODE_VERSION} is not signed by a key of the Node.js release keyring at ${NODE_RELEASE_KEYS_COMMIT}" >&2; exit 1; \
       fi \
    && grep " ${archive}\$" SHASUMS256.txt | sha256sum -c - \
    && tar -xJf "${archive}" -C /usr/local --strip-components=1 --no-same-owner \
    && rm -f "${archive}" SHASUMS256.txt SHASUMS256.txt.sig node-release-keys.kbx \
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

# The tools block runs with the default shell (/bin/sh -c); set SHELL inside it if you need another.
SHELL ["/bin/sh", "-c"]

# >>> Insert your tools setup here <<<

# <<< End of your tools setup >>>

SHELL ["/bin/sh", "-eu", "-c"]

# --- TeamCode contract check (do not edit) -------------------------------------------------------

# Fails the build when the image no longer gives TeamCode what it needs, for example when a tools
# setup removed git or a library the binary loads (linked or opened at run time), and runs the binary
# as both pod UIDs.
RUN as_agent() { setpriv --reuid=1001 --regid=1000 --clear-groups env HOME=/tmp "$@" >/dev/null; }; \
    for command in sh mkdir rm mv rmdir dirname touch test git; do \
        command -v "${command}" >/dev/null || { echo "Missing ${command}" >&2; exit 1; }; \
    done \
    && git_version="$(git --version | sed 's/^git version //')" \
    && if ! printf '2.31.0\n%s\n' "${git_version}" | sort -C -V; then echo "git ${git_version} is older than 2.31" >&2; exit 1; fi \
    && [ "$(id -u runner):$(id -g runner):$(id -u agent):$(id -g agent)" = "1000:1000:1001:1000" ] \
    && if ldd /usr/local/bin/teamcode | grep 'not found'; then echo "teamcode is missing a library" >&2; exit 1; fi \
    && for library in libcrypto.so.3 libcurl.so.4 libcrypt.so.1 libz.so.1; do \
        ldconfig -p | grep -q "${library}" || { echo "Missing ${library}" >&2; exit 1; }; \
    done \
    && setpriv --reuid=1000 --regid=1000 --clear-groups env HOME=/tmp /usr/local/bin/teamcode version >/dev/null \
    && as_agent /usr/local/bin/teamcode version \
    && if [ -n "${CLAUDE_CODE_VERSION}" ]; then as_agent claude --version; fi \
    && if [ -n "${CODEX_VERSION}" ]; then as_agent codex --version; fi \
    && rm -rf /tmp/* /tmp/.[!.]*

WORKDIR /workspace
USER 1001:1000
