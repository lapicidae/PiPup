#!/bin/bash
#
# PiPup Dual-Axis L-Shape Stacking & In-Place Update Tester.
# Sends dual-axis (horizontal and vertical) stacked notifications to test L-shape positioning and in-place updates.

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
Usage: ${0##*/} [-d target_ip] [-p position] [-o] [-c] [-C id]
Commands / Options:
  -d    Target IP (default: ${DEFAULT_IP})
  -p    Position index (0: TopRight, 1: TopLeft, 2: BottomRight, 3: BottomLeft, 4: Center, default: 0)
  -o    Enable overwrite flag ("overwrite": true) in requests
  -c    Cancel all active popups (/cancel?all=true)
  -C    Cancel a specific popup by ID (/cancel?id=<id>)
  -h    Show this help

Description:
  Sends 2 horizontal and 2 vertical stacked popups to test dual-axis L-shape stacking,
  followed by an in-place content update on Popup #1, and a selective cancel-by-ID on Popup #3.
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
  local position="0"
  local cancel_all="false"
  local cancel_id=""
  local overwrite_flag="false"
  local endpoint_base
  local payload
  local update_payload
  local rand_val

  while getopts "d:p:ocC:h" opt; do
    case "${opt}" in
      d) target_ip="${OPTARG}" ;;
      p) position="${OPTARG}" ;;
      o) overwrite_flag="true" ;;
      c) cancel_all="true" ;;
      C) cancel_id="${OPTARG}" ;;
      h|?) usage ;;
    esac
  done
  shift $((OPTIND - 1))

  setup_adb_forwarding "${target_ip}"
  endpoint_base="http://${target_ip}:${PORT}"

  if [[ "${cancel_all}" == "true" ]]; then
    cancel_all_popups "${endpoint_base}"
    exit $?
  fi

  if [[ -n "${cancel_id}" ]]; then
    cancel_popup_by_id "${endpoint_base}" "${cancel_id}"
    exit $?
  fi

  ensure_min_max_popups "${endpoint_base}" 4

  printf "%b[STACKING] Deploying Dual-Axis L-Shape Stack to position %s (2 Horizontal, 2 Vertical)...%b\n" \
    "${CLR_HEADER}" "${position}" "${CLR_RESET}"

  # 1. Send 2 horizontal popups
  for i in 1 2; do
    payload=$(cat <<EOF
{
  "id": "stack_test_${i}",
  "title": "Horiz Popup #${i}",
  "message": "Horizontal stack item #${i} at pos ${position}.",
  "position": ${position},
  "duration": 10,
  "stack": "horizontal",
  "overwrite": ${overwrite_flag},
  "backgroundColor": "#DD1A237E"
}
EOF
    )
    send_api_request "${endpoint_base}/notify" "${payload}" "Horizontal Popup #${i} enqueued successfully."
    sleep 0.5
  done

  # 2. Send 2 vertical popups
  for i in 3 4; do
    payload=$(cat <<EOF
{
  "id": "stack_test_${i}",
  "title": "Vert Popup #${i}",
  "message": "Vertical stack item #${i} at pos ${position}.",
  "position": ${position},
  "duration": 10,
  "stack": "vertical",
  "overwrite": ${overwrite_flag},
  "backgroundColor": "#DD004D40"
}
EOF
    )
    send_api_request "${endpoint_base}/notify" "${payload}" "Vertical Popup #${i} enqueued successfully."
    sleep 0.5
  done

  # Wait 3 seconds, then send an in-place update for Popup #1
  sleep 3
  rand_val=$((RANDOM % 9000 + 1000))
  printf "\n%b[UPDATE] Sending in-place update for Popup #1 (Random ID: %d)...%b\n" "${CLR_HEADER}" "${rand_val}" "${CLR_RESET}"

  update_payload=$(cat <<EOF
{
  "id": "stack_test_1",
  "title": "Horiz Popup #1 (Updated)",
  "message": "Updated in-place! Token: ${rand_val} at $(date +%T)",
  "position": ${position},
  "duration": 10,
  "stack": "horizontal",
  "overwrite": ${overwrite_flag},
  "backgroundColor": "#DD1B5E20"
}
EOF
  )

  send_api_request "${endpoint_base}/notify" "${update_payload}" "In-place update for Popup #1 sent successfully."

  # Wait 3 seconds, then test cancel by ID for Popup #3
  sleep 3
  printf "\n"
  cancel_popup_by_id "${endpoint_base}" "stack_test_3"

  printf "\n%b[SUCCESS] Dual-Axis L-Shape Stacking, In-Place & Cancel-by-ID test completed!%b\n" "${CLR_SUCCESS}" "${CLR_RESET}"
}

main "$@"
