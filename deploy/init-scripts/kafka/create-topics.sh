#!/bin/bash
set -e

BOOTSTRAP_SERVER="kafka:29092"

echo "=========================================================="
echo "NexusEnterprise Hub - Kafka Topic Provisioning Service"
echo "Target Broker: ${BOOTSTRAP_SERVER}"
echo "=========================================================="

echo "Testing broker connectivity..."
RETRY_COUNT=0
MAX_RETRIES=20

until kafka-topics --bootstrap-server "${BOOTSTRAP_SERVER}" --list > /dev/null 2>&1; do
  RETRY_COUNT=$((RETRY_COUNT+1))
  if [ $RETRY_COUNT -ge $MAX_RETRIES ]; then
    echo "ERROR: Kafka broker did not become ready in time. Exiting."
    exit 1
  fi
  echo "Broker not fully responsive yet (Attempt $RETRY_COUNT/$MAX_RETRIES). Retrying in 2 seconds..."
  sleep 2
done

echo "Connection established! Provisioning topics..."

TOPICS=(
  "agent.task.requested"
  "agent.task.events"
  "agent.task.completed"
  "agent.task.dlq"
)

PARTITIONS=3
REPLICATION_FACTOR=1

for TOPIC in "${TOPICS[@]}"; do
  echo "Ensuring topic exists: '${TOPIC}' (Partitions: ${PARTITIONS}, Replication: ${REPLICATION_FACTOR})"
  kafka-topics --bootstrap-server "${BOOTSTRAP_SERVER}" \
    --create \
    --if-not-exists \
    --topic "${TOPIC}" \
    --partitions ${PARTITIONS} \
    --replication-factor ${REPLICATION_FACTOR} \
    --config min.insync.replicas=1 \
    --config retention.ms=604800000
done

echo "----------------------------------------------------------"
echo "All topics successfully reconciled. Current Cluster Topics:"
kafka-topics --bootstrap-server "${BOOTSTRAP_SERVER}" --list
echo "=========================================================="
