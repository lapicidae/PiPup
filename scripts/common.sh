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
# Cancels all active and queued popups via POST /cancel?all=true.
# Arguments:
#   $1: Endpoint base URL (e.g. http://127.0.0.1:7979)
# Outputs:
#   Writes status to stdout.
# Returns:
#   0 on HTTP 200, 1 on failure.
#######################################
cancel_all_popups() {
  local endpoint_base="${1}"
  local response body code

  printf "%b[*] Cancelling all active popups...%b\n" "${CLR_HEADER}" "${CLR_RESET}"
  response=$(curl -s --connect-timeout 3 --max-time 5 -w "|%{http_code}" -X POST "${endpoint_base}/cancel?all=true" || printf "Error|000")
  body="${response%|*}"
  code="${response##*|}"

  if [[ "${code}" == "200" ]]; then
    printf "%b  [OK] All popups cancelled (%s).%b\n" "${CLR_SUCCESS}" "${body}" "${CLR_RESET}"
    sleep 1
    return 0
  else
    printf "%b  [ERROR] Cancel all failed (HTTP %s): %s%b\n" "${CLR_ERROR}" "${code}" "${body}" "${CLR_RESET}"
    return 1
  fi
}

#######################################
# Cancels a specific popup by ID via POST /cancel?id=<id>.
# Arguments:
#   $1: Endpoint base URL (e.g. http://127.0.0.1:7979)
#   $2: Popup ID to cancel
# Outputs:
#   Writes status to stdout.
# Returns:
#   0 on HTTP 200, 1 on failure.
#######################################
cancel_popup_by_id() {
  local endpoint_base="${1}"
  local cancel_id="${2}"
  local response body code

  printf "%b[*] Cancelling popup with ID '%s'...%b\n" "${CLR_HEADER}" "${cancel_id}" "${CLR_RESET}"
  response=$(curl -s --connect-timeout 3 --max-time 5 -w "|%{http_code}" -X POST "${endpoint_base}/cancel?id=${cancel_id}" || printf "Error|000")
  body="${response%|*}"
  code="${response##*|}"

  if [[ "${code}" == "200" ]]; then
    printf "%b  [OK] Cancel request for ID '%s' succeeded (%s).%b\n" "${CLR_SUCCESS}" "${cancel_id}" "${body}" "${CLR_RESET}"
    return 0
  else
    printf "%b  [ERROR] Cancel by ID failed (HTTP %s): %s%b\n" "${CLR_ERROR}" "${code}" "${body}" "${CLR_RESET}"
    return 1
  fi
}

#######################################
# Reads the current maxPopups value from /settings.
# Arguments:
#   $1: Endpoint base URL (e.g. http://127.0.0.1:7979)
# Outputs:
#   Writes the integer maxPopups value (defaults to 1) to stdout.
#######################################
get_max_popups() {
  local endpoint_base="${1}"
  curl -s --connect-timeout 3 --max-time 5 "${endpoint_base}/settings" \
    | grep -o '"maxPopups":[0-9]*' | cut -d':' -f2 || printf "1"
}

#######################################
# Reads the current mediaLoadingPlaceholder value from /settings.
# Arguments:
#   $1: Endpoint base URL (e.g. http://127.0.0.1:7979)
# Outputs:
#   Writes the boolean string ("true" or "false", defaults to "false") to stdout.
#######################################
get_media_placeholder() {
  local endpoint_base="${1}" val
  val=$(curl -s --connect-timeout 3 --max-time 5 "${endpoint_base}/settings" \
    | grep -o '"mediaLoadingPlaceholder":[a-z]*' | cut -d':' -f2 || printf "false")
  printf "%s" "${val:-false}"
}

#######################################
# Updates /settings with a partial JSON payload.
# Arguments:
#   $1: Endpoint base URL (e.g. http://127.0.0.1:7979)
#   $2: JSON object string (e.g. '{"maxPopups": 5}')
#######################################
update_app_settings() {
  local endpoint_base="${1}"
  local json_payload="${2}"
  curl -s --connect-timeout 3 --max-time 5 -X POST -H "Content-Type: application/json" \
    -d "${json_payload}" "${endpoint_base}/settings" >/dev/null 2>&1 || true
}

#######################################
# Fetches current /settings and warns if maxPopups is less than required.
# Arguments:
#   $1: Endpoint base URL (e.g. http://127.0.0.1:7979)
#   $2: Required minimum maxPopups (default: 2)
# Outputs:
#   Writes warning to stdout if maxPopups is less than required.
#######################################
check_max_popups() {
  local endpoint_base="${1}"
  local required_max="${2:-2}"
  local current_max

  current_max=$(get_max_popups "${endpoint_base}")
  if (( current_max < required_max )); then
    printf "%b[WARNING] maxPopups is currently set to %s in app settings. Multiple popups will queue rather than display simultaneously.%b\n" \
      "${CLR_WARNING}" "${current_max}" "${CLR_RESET}"
  fi
}
