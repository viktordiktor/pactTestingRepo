#!/usr/bin/env bash
#
# Small helpers shared by .github/workflows/ci.yml and .gitlab-ci.yml.
# Only curl + jq + (optionally) docker are required.
#
# Usage:
#   source scripts/ci/pact-broker.sh
#   wait_for_broker
#   publish_pact pacts/order-service-payment-service.json order-service payment-service "$GIT_SHA"
#   record_deployment payment-service "$GIT_SHA" staging
#   can_i_deploy order-service "$GIT_SHA" staging
#
# Environment:
#   PACT_BROKER_URL       broker URL as seen from the CI job (default http://localhost:9292)
#   PACT_BROKER_TOKEN     optional bearer token (PactFlow / secured broker)
#   PACT_CLI_IMAGE        default pactfoundation/pact-cli:latest
#   PACT_CLI_BROKER_URL   broker URL as seen from *inside* the pact-cli container
#   PACT_CLI_NETWORK      optional docker network for the pact-cli container
#                         (if empty, host.docker.internal + --add-host is used)

set -eo pipefail

PACT_BROKER_URL="${PACT_BROKER_URL:-http://localhost:9292}"
PACT_CLI_IMAGE="${PACT_CLI_IMAGE:-pactfoundation/pact-cli:latest}"
PACT_CLI_BROKER_URL="${PACT_CLI_BROKER_URL:-$PACT_BROKER_URL}"

broker_curl() {
  if [ -n "${PACT_BROKER_TOKEN:-}" ]; then
    curl -fsS -H "Authorization: Bearer $PACT_BROKER_TOKEN" "$@"
  else
    curl -fsS "$@"
  fi
}

wait_for_broker() {
  local attempts="${1:-60}"
  local i
  for i in $(seq 1 "$attempts"); do
    if broker_curl "$PACT_BROKER_URL/diagnostic/status/heartbeat" > /dev/null 2>&1; then
      echo "Pact Broker is up at $PACT_BROKER_URL"
      return 0
    fi
    sleep 2
  done
  echo "Pact Broker did not start at $PACT_BROKER_URL" >&2
  return 1
}

publish_pact() {
  local pact_file="$1" consumer="$2" provider="$3" version="$4"
  broker_curl -X PUT -H "Content-Type: application/json" \
    --data-binary "@$pact_file" \
    "$PACT_BROKER_URL/pacts/provider/$provider/consumer/$consumer/version/$version" > /dev/null
  echo "Published $pact_file as $consumer $version"
}

ensure_environment() {
  local name="$1" production="${2:-false}" body uuid
  body=$(printf '{"name":"%s","displayName":"%s","production":%s}' "$name" "$name" "$production")
  curl -sS -X POST -H "Content-Type: application/json" -d "$body" \
    "$PACT_BROKER_URL/environments" > /dev/null 2>&1 || true
  uuid=$(broker_curl -H "Accept: application/hal+json" "$PACT_BROKER_URL/environments" \
    | jq -r --arg name "$name" '._embedded.environments[] | select(.name == $name) | .uuid' \
    | head -n 1)
  if [ -z "$uuid" ] || [ "$uuid" = "null" ]; then
    echo "Cannot find or create environment '$name'" >&2
    return 1
  fi
  echo "$uuid"
}

record_deployment() {
  local pacticipant="$1" version="$2" environment="$3" uuid
  uuid=$(ensure_environment "$environment")
  broker_curl -X POST -H "Content-Type: application/json" -d '{}' \
    "$PACT_BROKER_URL/pacticipants/$pacticipant/versions/$version/deployed-versions/environment/$uuid" > /dev/null
  echo "Recorded deployment: $pacticipant $version -> $environment"
}

can_i_deploy() {
  local pacticipant="$1" version="$2" environment="$3"
  shift 3
  local docker_args=(--rm)
  if [ -n "${PACT_CLI_NETWORK:-}" ]; then
    docker_args+=(--network "$PACT_CLI_NETWORK")
  else
    docker_args+=(--add-host host.docker.internal:host-gateway)
  fi
  # Exit code is the gate: 0 = yes, 1 = no. Do not swallow it.
  docker run "${docker_args[@]}" "$PACT_CLI_IMAGE" \
    broker can-i-deploy -a "$pacticipant" -e "$version" \
    --to-environment "$environment" -b "$PACT_CLI_BROKER_URL" "$@"
}
