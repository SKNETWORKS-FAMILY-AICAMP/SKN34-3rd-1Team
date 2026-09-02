#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
INFRASTRUCTURE_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${INFRASTRUCTURE_DIR}/compose.yaml"
WAIT_TIMEOUT_SECONDS="${VERIFY_COMPOSE_TIMEOUT_SECONDS:-120}"
WAIT_INTERVAL_SECONDS="${VERIFY_COMPOSE_INTERVAL_SECONDS:-2}"
KEEP_RUNNING="${VERIFY_COMPOSE_KEEP_RUNNING:-false}"
PROJECT_NAME="${VERIFY_COMPOSE_PROJECT_NAME:-govbiz-verify}"

# Verification never uses a developer's real key or the live public API. Exported values take
# precedence over a root .env file for every Compose command executed by this script.
export BIZINFO_API_BASE_URL="http://bizinfo-stub:8001"
export DATA_GO_KR_SERVICE_KEY="compose%2Bverification%2Fkey%3D"
export OPENAI_API_KEY="compose-verification-key-never-sent"
export LLM_MODEL_TIMEOUT_SECONDS="8.0"
export LLM_RUN_TIMEOUT_SECONDS="10.0"
export AI_SERVICE_READ_TIMEOUT="12s"

COMPOSE=(
  docker compose
  --profile verification
  --project-name "${PROJECT_NAME}"
  --file "${COMPOSE_FILE}"
)
RESPONSE_DIR="$(mktemp -d)"
LAST_RESPONSE_FILE="${RESPONSE_DIR}/last-response"

cleanup() {
  local exit_code=$?
  trap - EXIT

  if ((exit_code != 0)); then
    echo "Compose verification failed. Current services and logs:" >&2
    "${COMPOSE[@]}" ps >&2 || true
    "${COMPOSE[@]}" logs --no-color >&2 || true
  fi

  if [[ "${KEEP_RUNNING}" != "true" ]]; then
    "${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi

  rm -rf -- "${RESPONSE_DIR}"
  exit "${exit_code}"
}

trap cleanup EXIT

wait_for_http() {
  local label=$1
  local url=$2
  local expected_status=$3
  local expected_body_patterns=("${@:4}")
  local deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))
  local actual_status="000"
  local body_matches
  local pattern

  while ((SECONDS < deadline)); do
    : >"${LAST_RESPONSE_FILE}"
    actual_status="$(
      curl \
        --silent \
        --output "${LAST_RESPONSE_FILE}" \
        --write-out '%{http_code}' \
        --max-time 5 \
        "${url}" || true
    )"

    if [[ "${actual_status}" == "${expected_status}" ]]; then
      body_matches=true
      for pattern in "${expected_body_patterns[@]}"; do
        if [[ -n "${pattern}" ]] && ! grep -Eq "${pattern}" "${LAST_RESPONSE_FILE}"; then
          body_matches=false
          break
        fi
      done

      if [[ "${body_matches}" == "true" ]]; then
        echo "Verified ${label}: HTTP ${actual_status}"
        return 0
      fi
    fi

    echo "Waiting for ${label}: expected HTTP ${expected_status}, received ${actual_status}"
    sleep "${WAIT_INTERVAL_SECONDS}"
  done

  echo "Timed out waiting for ${label}: expected HTTP ${expected_status}, received ${actual_status}" >&2
  echo "Last response body:" >&2
  sed -n '1,80p' "${LAST_RESPONSE_FILE}" >&2
  return 1
}

wait_for_json_post() {
  local label=$1
  local url=$2
  local request_body=$3
  local expected_status=$4
  local expected_body_pattern=$5
  local deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))
  local actual_status="000"

  while ((SECONDS < deadline)); do
    : >"${LAST_RESPONSE_FILE}"
    actual_status="$(
      curl \
        --silent \
        --output "${LAST_RESPONSE_FILE}" \
        --write-out '%{http_code}' \
        --max-time 5 \
        --request POST \
        --header 'Accept: application/json' \
        --header 'Content-Type: application/json' \
        --header 'Origin: http://127.0.0.1:5173' \
        --data "${request_body}" \
        "${url}" || true
    )"

    if [[ "${actual_status}" == "${expected_status}" ]] \
        && grep -Eq "${expected_body_pattern}" "${LAST_RESPONSE_FILE}"; then
      echo "Verified ${label}: HTTP ${actual_status}"
      return 0
    fi

    echo "Waiting for ${label}: expected HTTP ${expected_status}, received ${actual_status}"
    sleep "${WAIT_INTERVAL_SECONDS}"
  done

  echo "Timed out waiting for ${label}: expected HTTP ${expected_status}, received ${actual_status}" >&2
  echo "Last response body:" >&2
  sed -n '1,80p' "${LAST_RESPONSE_FILE}" >&2
  return 1
}

wait_for_ai_health_failure() {
  local url=$1
  local deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))
  local actual_status="000"

  while ((SECONDS < deadline)); do
    : >"${LAST_RESPONSE_FILE}"
    actual_status="$(
      curl \
        --silent \
        --output "${LAST_RESPONSE_FILE}" \
        --write-out '%{http_code}' \
        --max-time 5 \
        "${url}" || true
    )"

    if [[ "${actual_status}" == "503" ]] \
        && grep -Eq '"code"[[:space:]]*:[[:space:]]*"AI_SERVICE_UNAVAILABLE"' "${LAST_RESPONSE_FILE}"; then
      echo "Verified Core to AI Service failure contract: HTTP 503 unavailable"
      return 0
    fi
    if [[ "${actual_status}" == "504" ]] \
        && grep -Eq '"code"[[:space:]]*:[[:space:]]*"AI_SERVICE_TIMEOUT"' "${LAST_RESPONSE_FILE}"; then
      echo "Verified Core to AI Service failure contract: HTTP 504 timeout"
      return 0
    fi

    echo "Waiting for Core to AI Service failure contract: received ${actual_status}"
    sleep "${WAIT_INTERVAL_SECONDS}"
  done

  echo "Timed out waiting for Core to AI Service unavailable/timeout contract" >&2
  echo "Last response body:" >&2
  sed -n '1,80p' "${LAST_RESPONSE_FILE}" >&2
  return 1
}

echo "Validating Compose configuration"
"${COMPOSE[@]}" config --quiet

echo "Building and starting the GovBiz verification stack (${PROJECT_NAME})"
"${COMPOSE[@]}" up --build --detach --remove-orphans

wait_for_http "Vite web" "http://127.0.0.1:5173/" "200"
wait_for_http "Vite-proxied Core API health" "http://127.0.0.1:5173/api/v1/health" "200" '"status"[[:space:]]*:[[:space:]]*"up".*"service"[[:space:]]*:[[:space:]]*"govbiz-core-api"'
wait_for_http "Vite-proxied Core to AI Service health" "http://127.0.0.1:5173/api/v1/health/ai-service" "200" '"status"[[:space:]]*:[[:space:]]*"up".*"service"[[:space:]]*:[[:space:]]*"govbiz-ai-service"'
wait_for_http \
  "Vite-proxied blank catalog search through the Bizinfo adapter" \
  "http://127.0.0.1:5173/api/v1/support-programs/search?query=&acceptingOnly=true" \
  "200" \
  '"query"[[:space:]]*:[[:space:]]*""' \
  '"id"[[:space:]]*:[[:space:]]*"PBLN_COMPOSE_EXPORT"' \
  '"applicationPeriod"[[:space:]]*:[[:space:]]*"2026-08-20 ~ 2099-09-11"' \
  '"status"[[:space:]]*:[[:space:]]*"OPEN"' \
  '"sourceUrl"[[:space:]]*:[[:space:]]*"https://www.bizinfo.go.kr/compose-verification"'
wait_for_json_post \
  "Vite-proxied sample item preparation" \
  "http://127.0.0.1:5173/api/v1/sample-items/prepare" \
  '{"item":{"name":"Compose verification item","category":"BASIC","note":"Verifies the reusable sample feature."}}' \
  "200" \
  '"phase"[[:space:]]*:[[:space:]]*"READY_FOR_PROCESSING".*"status"[[:space:]]*:[[:space:]]*"NOT_STARTED"'

echo "Stopping only AI Service to verify failure isolation"
"${COMPOSE[@]}" stop ai-service

wait_for_http "Core API health while AI Service is stopped" "http://127.0.0.1:5173/api/v1/health" "200" '"status"[[:space:]]*:[[:space:]]*"up".*"service"[[:space:]]*:[[:space:]]*"govbiz-core-api"'
wait_for_ai_health_failure "http://127.0.0.1:5173/api/v1/health/ai-service"
wait_for_http \
  "Required AI search failure while AI Service is stopped" \
  "http://127.0.0.1:5173/api/v1/support-programs/search?query=%EC%88%98%EC%B6%9C&acceptingOnly=true" \
  "503" \
  '"code"[[:space:]]*:[[:space:]]*"AI_SERVICE_UNAVAILABLE"'

echo "Restarting AI Service to verify recovery without restarting Core API"
"${COMPOSE[@]}" start ai-service

wait_for_http "Core to AI Service recovery" "http://127.0.0.1:5173/api/v1/health/ai-service" "200" '"status"[[:space:]]*:[[:space:]]*"up".*"service"[[:space:]]*:[[:space:]]*"govbiz-ai-service"'

echo "Compose verification passed: required AI failure, catalog search, startup, isolation, and recovery are valid."
