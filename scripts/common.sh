#!/bin/bash
#
# Shared Common Helper Library for PiPup Test Scripts.
# Provides centralized constants, ADB forwarding, HTTP API request helpers, and UI coloring.
# Strictly adheres to the Google Shell Style Guide.

set -euo pipefail

# --- Global Constants ---
# shellcheck disable=SC2034
readonly DEFAULT_IP='127.0.0.1'
readonly PORT='7979'
readonly APP_PACKAGE="nl.rogro82.pipup"
readonly APP_ACTIVITY="${APP_PACKAGE}/.MainActivity"

# --- UI Coloring ---
readonly CLR_RESET='\033[0m'
# shellcheck disable=SC2034
readonly CLR_HEADER='\033[1;35m'
readonly CLR_SUCCESS='\033[1;32m'
readonly CLR_WARNING='\033[1;33m'
readonly CLR_ERROR='\033[1;31m'

#######################################
# Sets up ADB port forwarding and ensures the PiPup webserver is running.
# Arguments:
#   $1: Target IP address.
# Outputs:
#   Connects ADB or sets up port forwarding if needed.
#######################################
setup_adb_forwarding() {
  local target_ip="${1}"
  local adb_cmd="adb"
  [[ -n "${ANDROID_SERIAL:-}" ]] && adb_cmd="adb -s ${ANDROID_SERIAL}"

  if [[ "${target_ip}" != "127.0.0.1" && "${target_ip}" != "localhost" ]]; then
    $adb_cmd connect "${target_ip}:5555" >/dev/null 2>&1 || true
  else
    $adb_cmd forward "tcp:${PORT}" "tcp:${PORT}" >/dev/null 2>&1 || true
  fi

  # Only start activity if webserver isn't already responding to avoid stealing focus
  if ! curl -s --connect-timeout 2 --max-time 3 "http://${target_ip}:${PORT}/state" >/dev/null 2>&1; then
    $adb_cmd shell am start -n "${APP_ACTIVITY}" >/dev/null 2>&1 || true
    sleep 2
  fi
}

#######################################
# Sends an API request using curl and handles response parsing and output.
# Arguments:
#   $1: Full HTTP endpoint URL
#   $2: JSON Payload string
#   $3: A description label for logging.
# Outputs:
#   Writes status formatted output to stdout.
# Returns:
#   0 on HTTP 200, 1 on failure.
#######################################
send_api_request() {
  local endpoint="${1}"
  local payload="${2}"
  local label="${3}"
  local response body code

  response=$(curl -s --connect-timeout 3 --max-time 5 -w "|%{http_code}" -X POST -H "Content-Type: application/json" -d "${payload}" "${endpoint}" || printf "Error|000")
  body="${response%|*}"
  code="${response##*|}"

  if [[ "${code}" == "200" ]]; then
    printf "%b  [OK] %s%b\n" "${CLR_SUCCESS}" "${label}" "${CLR_RESET}"
    return 0
  else
    printf "%b  [ERROR] %s failed (HTTP %s): %s%b\n" "${CLR_ERROR}" "${label}" "${code}" "${body}" "${CLR_RESET}"
    return 1
  fi
}

#######################################
# Fetches current /settings and warns if maxPopups is set to 1 for multi-popup tests.
# Arguments:
#   $1: Endpoint base URL (e.g. http://127.0.0.1:7979)
# Outputs:
#   Writes warning to stdout if maxPopups is less than required.
#######################################
check_max_popups() {
  local endpoint_base="${1}"
  local required_max="${2:-2}"
  local current_max

  current_max=$(curl -s --connect-timeout 3 --max-time 5 "${endpoint_base}/settings" | grep -o '"maxPopups":[0-9]*' | cut -d':' -f2 || printf "1")
  if (( current_max < required_max )); then
    printf "%b[WARNING] maxPopups is currently set to %s in app settings. Multiple popups will queue rather than display simultaneously.%b\n" \
      "${CLR_WARNING}" "${current_max}" "${CLR_RESET}"
  fi
}
