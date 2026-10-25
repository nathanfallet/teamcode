# syntax=docker/dockerfile:1
#
# The TeamCode MCP stdio host image, nathanfallet/teamcode-mcp (docs/mcp.md). The build context is this directory,
# after `./gradlew :mcp:buildFatJar` wrote build/libs/mcp-all.jar:
#
#   docker build -f mcp/Dockerfile -t nathanfallet/teamcode-mcp:dev mcp
#
# The image holds the host (JVM), Node.js (npx) and uv (uvx), tini as PID 1 to reap the grandchildren of npx and
# uvx, and prlimit. No Python: uvx installs its own per process, under /var/mcp. It runs as UID 10002 on a read-only
# root file system; /var/mcp and /tmp are the only writable places (emptyDir volumes in the chart).

ARG JRE_IMAGE=eclipse-temurin:21-jre-noble

# --- Downloads, verified, in a stage of their own: no curl or gpg in the final image ----------------------------
FROM ubuntu:24.04 AS tools
# The Node.js LTS of the cloud session image template (docs/cloud-session-image/Dockerfile), checked the same way:
# SHASUMS256.txt must be signed by a key of the Node.js release keyring at this commit, whose SHA-256 is pinned.
ARG NODE_VERSION=24.21.0
ARG NODE_RELEASE_KEYS_COMMIT=481637f813e912c4aa3622d7964ab426c97b8e8d
ARG NODE_RELEASE_KEYS_SHA256=610b8d249da3d5733f5a128def2dd0294dbbf5b5713e6ca2529db8db419dee00
# uv, pinned by version and by the SHA-256 of each architecture's archive (github.com/astral-sh/uv/releases).
ARG UV_VERSION=0.13.5
ARG UV_SHA256_AMD64=f68d291c75783122dad58ab77def0e13f3233f358731db5d6d7e8715603b928f
ARG UV_SHA256_ARM64=4bb69cf95ed13f23713bb7bc09f241896ea0c75b7c557e615a20604b82f9c403
ARG TARGETARCH
ARG DEBIAN_FRONTEND=noninteractive
SHELL ["/bin/sh", "-eu", "-c"]
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gpgv xz-utils \
    && rm -rf /var/lib/apt/lists/*
RUN case "${TARGETARCH:-amd64}" in amd64) arch=x64 ;; arm64) arch=arm64 ;; *) echo "Unsupported architecture ${TARGETARCH:-amd64}" >&2; exit 1 ;; esac \
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
    && mkdir -p /opt/node \
    && tar -xJf "${archive}" -C /opt/node --strip-components=1 --no-same-owner \
    && rm -rf /opt/node/include /opt/node/share/doc /opt/node/CHANGELOG.md /opt/node/README.md
RUN case "${TARGETARCH:-amd64}" in amd64) arch=x86_64; sum="${UV_SHA256_AMD64}" ;; arm64) arch=aarch64; sum="${UV_SHA256_ARM64}" ;; *) exit 1 ;; esac \
    && archive="uv-${arch}-unknown-linux-gnu.tar.gz" \
    && cd /tmp \
    && curl -fsSLO --retry 3 "https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/${archive}" \
    && if ! printf '%s  %s\n' "${sum}" "${archive}" | sha256sum -c - >/dev/null 2>&1; then \
         echo "${archive} of uv ${UV_VERSION} does not match its pinned SHA-256" >&2; exit 1; \
       fi \
    && mkdir -p /opt/uv \
    && tar -xzf "${archive}" -C /opt/uv --strip-components=1 --no-same-owner

# --- The host ----------------------------------------------------------------------------------------------
FROM ${JRE_IMAGE}
ARG DEBIAN_FRONTEND=noninteractive
SHELL ["/bin/sh", "-eu", "-c"]
# tini reaps the orphans npx and uvx leave behind; prlimit (util-linux) bounds each process's PIDs and open files.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tini util-linux ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=tools /opt/node/bin/ /usr/local/bin/
COPY --from=tools /opt/node/lib/ /usr/local/lib/
COPY --from=tools /opt/uv/uv /opt/uv/uvx /usr/local/bin/
RUN groupadd --gid 10002 mcp \
    && useradd --uid 10002 --gid 10002 --no-create-home --home-dir /var/mcp --shell /usr/sbin/nologin mcp \
    && install -d -o 10002 -g 10002 -m 0700 /var/mcp
COPY build/libs/mcp-all.jar /opt/teamcode-mcp/mcp.jar
# Contract: the commands the host starts, as its user, without a shell of its own.
RUN as_mcp() { setpriv --reuid=10002 --regid=10002 --clear-groups env HOME=/tmp "$@" >/dev/null; }; \
    as_mcp node --version && as_mcp npx --version && as_mcp uvx --version && as_mcp prlimit --version && as_mcp tini --version \
    && as_mcp java -version 2>/dev/null \
    && test -r /opt/teamcode-mcp/mcp.jar \
    && rm -rf /tmp/* /tmp/.[!.]*
# The JVM is small next to the processes it supervises; children never see these options (their environment is built from scratch).
ENV TEAMCODE_MCP_DATA_DIR=/var/mcp \
    JAVA_TOOL_OPTIONS="-Xmx384m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
LABEL org.opencontainers.image.source="https://github.com/nathanfallet/teamcode" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.description="TeamCode MCP stdio host"
USER 10002:10002
WORKDIR /var/mcp
EXPOSE 8090
ENTRYPOINT ["/usr/bin/tini", "--", "java", "-jar", "/opt/teamcode-mcp/mcp.jar"]
