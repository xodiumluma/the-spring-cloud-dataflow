#!/bin/bash
if [ "$DATAFLOW_VERSION" = "" ]; then
  DATAFLOW_VERSION=2.11.2-SNAPSHOT
fi
docker pull "springcloud/spring-cloud-dataflow-grafana-prometheus:$DATAFLOW_VERSION"
