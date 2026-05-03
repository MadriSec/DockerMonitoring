# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-11 AS agent-build
WORKDIR /src
COPY DockerMonitoring/agent/pom.xml DockerMonitoring/agent/
COPY DockerMonitoring/agent/src DockerMonitoring/agent/src
RUN cd DockerMonitoring/agent && mvn -B -q -DskipTests package

FROM tomcat:9.0-jdk11-temurin

USER root
RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=agent-build /src/DockerMonitoring/agent/target/docker-monitoring-agent.jar /opt/dockermonitoring/docker-monitoring-agent.jar
COPY DockerMonitoring/tomcat-twin/native-methods.cfg /opt/dockermonitoring/native-methods.cfg

# Some `tomcat:*` tags omit an OS `tomcat` user; run as root so the agent paths
# and CATALINA startup match default official image behavior across variants.

ENV CATALINA_OPTS="-javaagent:/opt/dockermonitoring/docker-monitoring-agent.jar \
 -Ddockermonitoring.native.methods.file=/opt/dockermonitoring/native-methods.cfg \
 -Ddockermonitoring.metrics.port=9464 \
 -Ddockermonitoring.metrics.host=0.0.0.0 \
 -Ddockermonitoring.output.file=/tmp/runtime_native_methods.txt"

EXPOSE 8080 9464
