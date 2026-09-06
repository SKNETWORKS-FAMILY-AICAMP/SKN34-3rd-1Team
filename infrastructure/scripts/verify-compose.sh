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
export BIZINFO_SYNC_ENABLED="true"
export BIZINFO_SYNC_INITIAL_DELAY="PT0S"
export BIZINFO_SYNC_FIXED_DELAY="PT2S"
export OPENAI_API_KEY="compose-verification-key-never-sent"
export OPENAI_BASE_URL="http://openai-stub:8002/v1"
export OPENAI_EMBEDDING_MODEL="text-embedding-3-small"
export OPENAI_EMBEDDING_DIMENSIONS="1536"
export EMBEDDING_TIMEOUT_SECONDS="5"
export QDRANT_TIMEOUT_SECONDS="2"
export QDRANT_HOST_PORT="${VERIFY_COMPOSE_QDRANT_HOST_PORT:-16333}"
export SUPPORT_PROGRAM_INDEX_ENABLED="true"
export SUPPORT_PROGRAM_INDEX_INITIAL_DELAY="PT0S"
export SUPPORT_PROGRAM_INDEX_FIXED_DELAY="PT2S"
export AI_SEMANTIC_SEARCH_READ_TIMEOUT="30s"
export LLM_MODEL_TIMEOUT_SECONDS="25.0"
export LLM_RUN_TIMEOUT_SECONDS="30.0"
export AI_SERVICE_READ_TIMEOUT="35s"
# 이 스모크 테스트는 장애 상태를 반복 폴링하므로 공개 요청 한도를 별도로 높인다.
# 낮은 한도·동시 거절·우회 방지는 Core/Frontend 회귀 테스트에서 검증한다.
export SUPPORT_PROGRAM_REQUEST_PER_CLIENT_PER_MINUTE="1000"
export SUPPORT_PROGRAM_REQUEST_GLOBAL_PER_MINUTE="1000"
export SUPPORT_PROGRAM_REQUEST_MAX_CONCURRENT="4"
# The verification stack connects to MySQL through the Compose network. Give its
# host-only port a separate default so a developer's local MySQL on 3306 does
# not prevent the smoke test from starting.
export MYSQL_HOST_PORT="${VERIFY_COMPOSE_MYSQL_HOST_PORT:-13306}"

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
  shift 3
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
        --max-time 70 \
        "${url}" || true
    )"

    if [[ "${actual_status}" == "${expected_status}" ]]; then
      body_matches=true
      for pattern in "$@"; do
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

wait_for_ai_failure() {
  local label=$1
  local url=$2
  local deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))
  local actual_status="000"

  while ((SECONDS < deadline)); do
    : >"${LAST_RESPONSE_FILE}"
    actual_status="$(
      curl \
        --silent \
        --output "${LAST_RESPONSE_FILE}" \
        --write-out '%{http_code}' \
        --max-time 70 \
        "${url}" || true
    )"

    if [[ "${actual_status}" == "503" ]] \
        && grep -Eq '"code"[[:space:]]*:[[:space:]]*"AI_SERVICE_UNAVAILABLE"' "${LAST_RESPONSE_FILE}"; then
      echo "Verified ${label}: HTTP 503 unavailable"
      return 0
    fi
    if [[ "${actual_status}" == "504" ]] \
        && grep -Eq '"code"[[:space:]]*:[[:space:]]*"AI_SERVICE_TIMEOUT"' "${LAST_RESPONSE_FILE}"; then
      echo "Verified ${label}: HTTP 504 timeout"
      return 0
    fi

    echo "Waiting for ${label}: received ${actual_status}"
    sleep "${WAIT_INTERVAL_SECONDS}"
  done

  echo "Timed out waiting for ${label}: expected unavailable/timeout contract" >&2
  echo "Last response body:" >&2
  sed -n '1,80p' "${LAST_RESPONSE_FILE}" >&2
  return 1
}

wait_for_synchronized_catalog_program() {
  local deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))
  local actual_count="0"

  while ((SECONDS < deadline)); do
    actual_count="$(
      "${COMPOSE[@]}" exec -T mysql sh -c \
        'mysql --batch --skip-column-names --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "SELECT COUNT(*) FROM support_program WHERE source_code = '\''BIZINFO'\'' AND source_program_id = '\''PBLN_COMPOSE_EXPORT'\'' AND is_source_present = TRUE" 2>/dev/null || true'
    )"

    if [[ "${actual_count}" == "1" ]]; then
      echo "Verified BizInfo synchronization stored PBLN_COMPOSE_EXPORT in MySQL"
      return 0
    fi

    echo "Waiting for synchronized MySQL catalog program: found ${actual_count:-no result} rows"
    sleep "${WAIT_INTERVAL_SECONDS}"
  done

  echo "Timed out waiting for the synchronized MySQL catalog program" >&2
  return 1
}

echo "Validating Compose configuration"
"${COMPOSE[@]}" config --quiet

echo "Building and starting the GovBiz verification stack (${PROJECT_NAME})"
"${COMPOSE[@]}" up --build --detach --remove-orphans

wait_for_http "Vite web" "http://127.0.0.1:5173/" "200"
wait_for_http "Vite-proxied Core API health" "http://127.0.0.1:5173/api/v1/health" "200" '"status"[[:space:]]*:[[:space:]]*"up".*"service"[[:space:]]*:[[:space:]]*"govbiz-core-api"'
wait_for_http "Vite-proxied Core to AI Service health" "http://127.0.0.1:5173/api/v1/health/ai-service" "200" '"status"[[:space:]]*:[[:space:]]*"up".*"service"[[:space:]]*:[[:space:]]*"govbiz-ai-service"'
wait_for_synchronized_catalog_program

echo "Stopping BizInfo stub to prove that search reads MySQL instead of the upstream API"
"${COMPOSE[@]}" stop bizinfo-stub

wait_for_http \
  "Vite-proxied blank catalog search after BizInfo stub is stopped" \
  "http://127.0.0.1:5173/api/v1/support-programs/search?query=&acceptingOnly=true" \
  "200" \
  '"query"[[:space:]]*:[[:space:]]*""' \
  '"id"[[:space:]]*:[[:space:]]*"PBLN_COMPOSE_EXPORT"' \
  '"applicationPeriod"[[:space:]]*:[[:space:]]*"2026-08-20 ~ 2099-09-11"' \
  '"status"[[:space:]]*:[[:space:]]*"OPEN"' \
  '"sourceUrl"[[:space:]]*:[[:space:]]*"https://www\.bizinfo\.go\.kr/web/lay1/bbs/S1T122C128/AS/74/view\.do\?pblancId=PBLN_COMPOSE_EXPORT"'

# This target is older than 25 irrelevant fixture programs. A latest-20 candidate
# selector cannot pass this check. OpenAI is an HTTP fixture; Qdrant is real.
wait_for_http \
  "Whole-catalog semantic search finds the old relevant AI program" \
  "http://127.0.0.1:5173/api/v1/support-programs/search?query=%EC%84%9C%EC%9A%B8%20AI&acceptingOnly=true" \
  "200" \
  '"id"[[:space:]]*:[[:space:]]*"PBLN_COMPOSE_OLD_AI"' \
  '"recommendationScore"[[:space:]]*:[[:space:]]*100'

echo "Stopping Qdrant to verify that a vector outage is not hidden as a successful search"
"${COMPOSE[@]}" stop qdrant
wait_for_http \
  "Explicit vector search failure while Qdrant is stopped" \
  "http://127.0.0.1:5173/api/v1/support-programs/search?query=AI&acceptingOnly=true" \
  "503" \
  '"code"[[:space:]]*:[[:space:]]*"AI_SERVICE_UNAVAILABLE"'
wait_for_http \
  "Blank latest listing still reads MySQL during vector outage" \
  "http://127.0.0.1:5173/api/v1/support-programs/search?query=&acceptingOnly=true" \
  "200" \
  '"id"[[:space:]]*:[[:space:]]*"PBLN_COMPOSE_EXPORT"'
"${COMPOSE[@]}" start qdrant
wait_for_http \
  "Vector search recovers from persistent Qdrant data" \
  "http://127.0.0.1:5173/api/v1/support-programs/search?query=%EC%84%9C%EC%9A%B8%20AI&acceptingOnly=true" \
  "200" \
  '"id"[[:space:]]*:[[:space:]]*"PBLN_COMPOSE_OLD_AI"'
wait_for_json_post \
  "Vite-proxied sample item preparation" \
  "http://127.0.0.1:5173/api/v1/sample-items/prepare" \
  '{"item":{"name":"Compose verification item","category":"BASIC","note":"Verifies the reusable sample feature."}}' \
  "200" \
  '"phase"[[:space:]]*:[[:space:]]*"READY_FOR_PROCESSING".*"status"[[:space:]]*:[[:space:]]*"NOT_STARTED"'

echo "Stopping only AI Service to verify failure isolation"
"${COMPOSE[@]}" stop ai-service

wait_for_http "Core API health while AI Service is stopped" "http://127.0.0.1:5173/api/v1/health" "200" '"status"[[:space:]]*:[[:space:]]*"up".*"service"[[:space:]]*:[[:space:]]*"govbiz-core-api"'
wait_for_ai_failure "Core to AI Service health failure contract" "http://127.0.0.1:5173/api/v1/health/ai-service"
wait_for_ai_failure \
  "Required AI search failure while AI Service is stopped" \
  "http://127.0.0.1:5173/api/v1/support-programs/search?query=%EC%88%98%EC%B6%9C&acceptingOnly=true"

echo "Restarting AI Service to verify recovery without restarting Core API"
"${COMPOSE[@]}" start ai-service

wait_for_http "Core to AI Service recovery" "http://127.0.0.1:5173/api/v1/health/ai-service" "200" '"status"[[:space:]]*:[[:space:]]*"up".*"service"[[:space:]]*:[[:space:]]*"govbiz-ai-service"'
wait_for_http \
  "Semantic search recovers after AI Service restart" \
  "http://127.0.0.1:5173/api/v1/support-programs/search?query=%EC%84%9C%EC%9A%B8%20AI&acceptingOnly=true" \
  "200" \
  '"id"[[:space:]]*:[[:space:]]*"PBLN_COMPOSE_OLD_AI"'

echo "Compose verification passed: old relevant semantic result, MySQL listing, Qdrant/AI failure isolation and recovery."
