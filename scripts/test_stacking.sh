#!/bin/bash
#
# PiPup Popup Stacking & In-Place Update Tester.
# Sends multiple notifications with unique IDs to test popup stacking, positioning, and in-place updates.

set -euo pipefail

# --- Global Constants ---
readonly DEFAULT_IP='127.0.0.1'
readonly PORT='7979'
readonly APP_PACKAGE="nl.rogro82.pipup"
readonly APP_ACTIVITY="${APP_PACKAGE}/.MainActivity"

# --- UI Coloring ---
readonly CLR_RESET='\033[0m'
readonly CLR_HEADER='\033[1;35m'
readonly CLR_SUCCESS='\033[1;32m'
readonly CLR_ERROR='\033[1;31m'

#######################################
# Sets up ADB port forwarding and ensures the PiPup webserver is running.
# Arguments:
#   $1: Target IP address.
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
  if ! curl -s --max-time 1 "http://${target_ip}:${PORT}/state" >/dev/null 2>&1; then
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
# Returns:
#   0 on HTTP 200, 1 on failure.
#######################################
send_api_request() {
  local endpoint="${1}"
  local payload="${2}"
  local label="${3}"
  local response body code

  response=$(curl -s -w "|%{http_code}" -X POST -H "Content-Type: application/json" -d "${payload}" "${endpoint}" || printf "Error|000")
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
# Displays usage information and exits.
# Globals:
#   DEFAULT_IP
# Arguments:
#   None.
# Outputs:
#   Writes usage guide to stdout.
# Returns:
#   Exits with status 1.
#######################################
usage() {
  cat <<EOF
Usage: ${0##*/} [-d target_ip] [-p position] [-o] [-c]
Commands / Options:
  -d    Target IP (default: ${DEFAULT_IP})
  -p    Position index (0: TopRight, 1: TopLeft, 2: BottomRight, 3: BottomLeft, 4: Center, default: 4)
  -o    Enable overwrite flag ("overwrite": true) in requests
  -c    Cancel all active popups (/cancel?all=true)
  -h    Show this help

Description:
  Sends 3 simultaneous popups with distinct IDs to test popup vertical stacking,
  followed by an in-place content update with a random counter on Popup #1.
EOF
  exit 1
}

#######################################
# Main execution logic.
# Globals:
#   DEFAULT_IP
#   PORT
#   CLR_HEADER
#   CLR_SUCCESS
# Arguments:
#   @: Command-line arguments passed to the script.
# Outputs:
#   Writes progress and execution results to stdout.
# Returns:
#   0 on success, non-zero on error.
#######################################
main() {
  local target_ip="${DEFAULT_IP}"
  local position="4"
  local cancel_all="false"
  local overwrite_flag="false"
  local endpoint_base
  local payload
  local update_payload
  local rand_val
  local i

  while getopts "d:p:och" opt; do
    case "${opt}" in
      d) target_ip="${OPTARG}" ;;
      p) position="${OPTARG}" ;;
      o) overwrite_flag="true" ;;
      c) cancel_all="true" ;;
      h|?) usage ;;
    esac
  done
  shift $((OPTIND - 1))

  setup_adb_forwarding "${target_ip}"
  endpoint_base="http://${target_ip}:${PORT}"

  if [[ "${cancel_all}" == "true" ]]; then
    printf "%b[STACKING] Cancelling all active popups...%b\n" "${CLR_HEADER}" "${CLR_RESET}"
    curl -s -X POST "${endpoint_base}/cancel?all=true" || true
    printf "%b[SUCCESS] All popups cancelled.%b\n" "${CLR_SUCCESS}" "${CLR_RESET}"
    exit 0
  fi

  printf "%b[STACKING] Sending 3 stacked test popups to position %s (overwrite: %s)...%b\n" "${CLR_HEADER}" "${position}" "${overwrite_flag}" "${CLR_RESET}"
  for i in 1 2 3; do
    payload=$(cat <<EOF
{
  "id": "stack_test_${i}",
  "title": "Stacked Popup #${i}",
  "message": "Initial message at position ${position}.",
  "position": ${position},
  "duration": 10,
  "overwrite": ${overwrite_flag},
  "backgroundColor": "#DD1A237E"
}
EOF
    )
    send_api_request "${endpoint_base}/notify" "${payload}" "Popup #${i} enqueued successfully."
    sleep 0.7
  done

  # Wait 3 seconds, then send an in-place update for Popup #1 with a random counter / timestamp
  sleep 3
  rand_val=$((RANDOM % 9000 + 1000))
  printf "\n%b[UPDATE] Sending in-place update for Popup #1 (Random ID: %d)...%b\n" "${CLR_HEADER}" "${rand_val}" "${CLR_RESET}"

  update_payload=$(cat <<EOF
{
  "id": "stack_test_1",
  "title": "Stacked Popup #1 (Updated)",
  "message": "Updated in-place! Random token: ${rand_val} at $(date +%T)",
  "position": ${position},
  "duration": 10,
  "overwrite": ${overwrite_flag},
  "backgroundColor": "#DD1B5E20"
}
EOF
  )

  send_api_request "${endpoint_base}/notify" "${update_payload}" "In-place update for Popup #1 sent successfully."

  printf "\n%b[SUCCESS] Stacking & In-Place test dispatched. Check your TV screen!%b\n" "${CLR_SUCCESS}" "${CLR_RESET}"
}

main "$@"
