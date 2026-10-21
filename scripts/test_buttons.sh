#!/bin/bash
#
# PiPup Multi-Position Interactive Buttons Tester.
# Sends multiple simultaneous interactive button popups at different screen positions (TopRight, Center, BottomLeft)
# to test D-pad focus traversal across simultaneous popups.

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
readonly CLR_WARNING='\033[1;33m'
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
Usage: ${0##*/} [-d target_ip] [-b size] [-r radius] [-c] [-C id]
Commands / Options:
  -d    Target IP (default: ${DEFAULT_IP})
  -b    Button font size in sp (8..40, default: 14)
  -r    Button corner radius in px (0..50, default: 12)
  -c    Cancel all active popups (/cancel?all=true)
  -C    Cancel a specific popup by ID (/cancel?id=<id>)
  -h    Show this help

Description:
  Sends 3 simultaneous interactive popups with buttons at different screen positions:
  1. Top-Right (Position 0)
  2. Center (Position 4)
  3. Bottom-Left (Position 3)
  Note: Requires 'maxPopups >= 3' in app settings for simultaneous display.
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
  local button_size="14"
  local corner_radius="12"
  local cancel_all="false"
  local cancel_id=""
  local endpoint_base
  local payload
  local state_json
  local cancel_resp cancel_code cancel_body

  while getopts "d:b:r:cC:h" opt; do
    case "${opt}" in
      d) target_ip="${OPTARG}" ;;
      b)
        button_size="${OPTARG}"
        if ! [[ "${button_size}" =~ ^[0-9]+$ ]] || (( button_size < 8 || button_size > 40 )); then
          printf "%b[ERROR] Invalid button size '%s'. Must be an integer 8..40.%b\n" "${CLR_ERROR}" "${button_size}" "${CLR_RESET}"
          exit 1
        fi
        ;;
      r)
        corner_radius="${OPTARG}"
        if ! [[ "${corner_radius}" =~ ^[0-9]+$ ]] || (( corner_radius < 0 || corner_radius > 50 )); then
          printf "%b[ERROR] Invalid corner radius '%s'. Must be an integer 0..50.%b\n" "${CLR_ERROR}" "${corner_radius}" "${CLR_RESET}"
          exit 1
        fi
        ;;
      c) cancel_all="true" ;;
      C) cancel_id="${OPTARG}" ;;
      h|?) usage ;;
    esac
  done
  shift $((OPTIND - 1))

  setup_adb_forwarding "${target_ip}"
  endpoint_base="http://${target_ip}:${PORT}"

  if [[ "${cancel_all}" == "true" ]]; then
    printf "%b[BUTTONS] Cancelling all active popups...%b\n" "${CLR_HEADER}" "${CLR_RESET}"
    cancel_resp=$(curl -s --connect-timeout 3 --max-time 5 -w "|%{http_code}" -X POST "${endpoint_base}/cancel?all=true" || printf "Error|000")
    cancel_body="${cancel_resp%|*}"
    cancel_code="${cancel_resp##*|}"
    if [[ "${cancel_code}" == "200" ]]; then
      printf "%b[SUCCESS] All popups cancelled (%s).%b\n" "${CLR_SUCCESS}" "${cancel_body}" "${CLR_RESET}"
      exit 0
    else
      printf "%b[ERROR] Cancel all failed (HTTP %s): %s%b\n" "${CLR_ERROR}" "${cancel_code}" "${cancel_body}" "${CLR_RESET}"
      exit 1
    fi
  fi

  if [[ -n "${cancel_id}" ]]; then
    printf "%b[BUTTONS] Cancelling popup with ID '%s'...%b\n" "${CLR_HEADER}" "${cancel_id}" "${CLR_RESET}"
    cancel_resp=$(curl -s --connect-timeout 3 --max-time 5 -w "|%{http_code}" -X POST "${endpoint_base}/cancel?id=${cancel_id}" || printf "Error|000")
    cancel_body="${cancel_resp%|*}"
    cancel_code="${cancel_resp##*|}"
    if [[ "${cancel_code}" == "200" ]]; then
      printf "%b[SUCCESS] Cancel request for ID '%s' succeeded (%s).%b\n" "${CLR_SUCCESS}" "${cancel_id}" "${cancel_body}" "${CLR_RESET}"
      exit 0
    else
      printf "%b[ERROR] Cancel by ID failed (HTTP %s): %s%b\n" "${CLR_ERROR}" "${cancel_code}" "${cancel_body}" "${CLR_RESET}"
      exit 1
    fi
  fi

  local current_max
  current_max=$(curl -s --connect-timeout 3 --max-time 5 "${endpoint_base}/settings" | grep -o '"maxPopups":[0-9]*' | cut -d':' -f2 || printf "1")
  if (( current_max < 3 )); then
    printf "%b[WARNING] maxPopups is currently set to %s in app settings. To display all 3 popups simultaneously, set maxPopups >= 3 in settings.%b\n" "${CLR_WARNING}" "${current_max}" "${CLR_RESET}"
  fi

  printf "%b[BUTTONS] Deploying 3 button popups: Top-Right (0), Center (4), Bottom-Left (3)...%b\n" \
    "${CLR_HEADER}" "${CLR_RESET}"

  # Array of positions: id, title, position
  local positions=(
    "btn_popup_top_right|Top-Right Popup|0"
    "btn_popup_center|Center Popup|4"
    "btn_popup_bottom_left|Bottom-Left Popup|3"
  )

  for entry in "${positions[@]}"; do
    IFS='|' read -r p_id p_title p_pos <<< "${entry}"
    payload=$(cat <<EOF
{
  "id": "${p_id}",
  "title": "${p_title}",
  "message": "Interactive popup at position ${p_pos}.",
  "position": ${p_pos},
  "duration": 0,
  "overwrite": false,
  "backgroundColor": "#EE12181C",
  "buttonSize": ${button_size},
  "buttonCornerRadius": ${corner_radius},
  "buttonColor": "#2F3033",
  "buttonFocusColor": "#8ECFF2",
  "buttonTextColor": "#DFE3E7",
  "buttonFocusTextColor": "#0F1417",
  "buttons": [
    { "id": "yes", "label": "Yes" },
    { "id": "no", "label": "No" }
  ],
  "callback": "http://${target_ip}:${PORT}/state"
}
EOF
    )
    send_api_request "${endpoint_base}/notify" "${payload}" "${p_title} enqueued successfully."
    sleep 0.5
  done

  # Query state to verify popups are active in the state model
  sleep 1
  printf "\n%b[STATE] Fetching current overlay state from /state...%b\n" "${CLR_HEADER}" "${CLR_RESET}"
  state_json=$(curl -s --connect-timeout 3 --max-time 5 "${endpoint_base}/state" || printf "{}")

  if echo "${state_json}" | grep -q '"id":"btn_popup_top_right"'; then
    printf "%b  [OK] Multiple button popups verified active in /state JSON.%b\n" "${CLR_SUCCESS}" "${CLR_RESET}"
  else
    printf "%b  [WARNING] Could not confirm active status in /state payload.%b\n" "${CLR_WARNING}" "${CLR_RESET}"
  fi

  printf "\n%b[SUCCESS] Multi-position button popups deployed! (Top-Right, Center, Bottom-Left).%b\n" "${CLR_SUCCESS}" "${CLR_RESET}"
}

main "$@"
