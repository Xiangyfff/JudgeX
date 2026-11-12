# JudgeX runner image: Java / Python / Node / C++ toolchains, no package manager needed.
# Built from official images because the build machine has no direct internet access
# (only the registry mirror is reachable).
FROM docker.m.daocloud.io/library/eclipse-temurin:17-jdk AS jdk
FROM docker.m.daocloud.io/library/node:20-slim AS node
FROM docker.m.daocloud.io/library/python:3.11-slim

COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules

ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/usr/local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

WORKDIR /work
