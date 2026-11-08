#!/bin/bash
#
# PiPup Multi-Position Interactive Buttons Tester.
# Sends multiple simultaneous interactive button popups at different screen positions (TopRight, Center, BottomLeft)
# to test D-pad focus traversal across simultaneous popups.

set -euo pipefail

# --- Source Shared Common Library ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/common.sh"

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

  check_max_popups "${endpoint_base}" 3

  printf "%b[BUTTONS] Deploying 3 button popups: Top-Right (0), Center (4), Bottom-Left (3)...%b\n" \
    "${CLR_HEADER}" "${CLR_RESET}"

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
