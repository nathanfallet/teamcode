# syntax=docker/dockerfile:1
#
# The TeamCode Push relay image (docs/notifications.md, "Relay protocol"; push/README.md). The build context is this
# directory, after `./gradlew :push:linkReleaseExecutableLinuxX64` wrote the native binary:
#
#   docker build --platform linux/amd64 -f push/Dockerfile -t teamcode-push:dev push
#
# A native binary, no JVM: libcurl (Ktor's client engine on Linux, for FCM and the metadata server) and the CA bundle
# are all it needs. It runs as an unprivileged user on a read-only root file system.
FROM debian:bookworm-slim
ARG DEBIAN_FRONTEND=noninteractive
SHELL ["/bin/sh", "-eu", "-c"]
RUN apt-get update \
    && apt-get install -y --no-install-recommends libcurl4 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --gid 10003 push && useradd --uid 10003 --gid 10003 --no-create-home --shell /usr/sbin/nologin push
COPY build/bin/linuxX64/releaseExecutable/teamcode-push.kexe /usr/local/bin/teamcode-push
# Contract: every shared library the binary needs is in the image.
RUN ! ldd /usr/local/bin/teamcode-push | grep -q "not found"
LABEL org.opencontainers.image.source="https://github.com/nathanfallet/teamcode" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.description="TeamCode Push relay"
USER 10003:10003
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/teamcode-push"]
