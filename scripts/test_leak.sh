#!/bin/bash
#
# Systematic Leak Test for PiPup.
# Measures memory differences across multiple notification types using ADB and API endpoints.

set -euo pipefail

# --- Source Shared Common Library ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/common.sh"

readonly NOTIFY_SCRIPT="${SCRIPT_DIR}/send_notify.sh"
readonly TIMEOUT_SEC=120
readonly ADB_TIMEOUT_SEC=10

# --- Global State ---
TARGET_IP="${PIPUP_IP:-${DEFAULT_IP}}"
ORIGINAL_MODE=""
TMP_LOG=""

#######################################
# Executes a safe, timeout-bound ADB shell command.
# Arguments:
#   $@: The adb shell command arguments.
#######################################
adb_shell_safe() {
  timeout -k 1s "${ADB_TIMEOUT_SEC}s" adb shell "$@" >/dev/null 2>&1 || true
}

#######################################
# Executes an API request to the PiPup server.
# Arguments:
#   $1: HTTP Method (GET, POST)
#   $2: API Endpoint (e.g., /settings)
#   $3: (Optional) JSON payload
# Outputs:
#   Writes curl response to stdout.
#######################################
api_request() {
  local method="${1}"
  local endpoint="${2}"
  local payload="${3:-}"
  local url="http://${TARGET_IP}:${PORT}${endpoint}"

  if [[ -n "${payload}" ]]; then
    curl -s --max-time 3 --connect-timeout 2 -X "${method}" \
      -H "Content-Type: application/json" -d "${payload}" "${url}" 2>/dev/null || true
  else
    curl -s --max-time 3 --connect-timeout 2 -X "${method}" "${url}" 2>/dev/null || true
  fi
}

#######################################
# Sets the media module mode via API.
# Arguments:
#   $1: Mode value (0 = OFF, 1 = ON, 2 = ECO)
#######################################
set_media_mode() {
  local mode="${1}"
  api_request "POST" "/settings" "{\"moduleModes\":{\"media\":${mode}}}" >/dev/null
}

#######################################
# Unified cleanup trap for process termination and state restoration.
#######################################
cleanup() {
  local exit_code=$?
  trap - EXIT INT TERM

  printf "\nCleaning up and exiting...\n" >&2

  pkill -9 -P $$ >/dev/null 2>&1 || true
  pkill -9 -f "${NOTIFY_SCRIPT}" >/dev/null 2>&1 || true

  if [[ -n "${ORIGINAL_MODE:-}" && ${ORIGINAL_MODE} != 2 ]]; then
    printf "Restoring original media mode (%s)...\n" "${ORIGINAL_MODE}" >&2
    curl -s --max-time 1 --connect-timeout 1 -X POST \
      -H "Content-Type: application/json" \
      -d "{\"moduleModes\":{\"media\":${ORIGINAL_MODE}}}" \
      "http://${TARGET_IP}:${PORT}/settings" >/dev/null 2>&1 || true
  fi

  [[ -f "${TMP_LOG}" ]] && rm -f "${TMP_LOG}"

  [[ ${exit_code} -eq 130 || ${exit_code} -eq 143 ]] && exit 130
  exit "${exit_code}"
}

#######################################
# Prints script usage guidelines and exits.
#######################################
usage() {
  printf "Usage: %s [-d target_ip] [-h]\n" "$(basename "${BASH_SOURCE[0]}")"
  printf "  -d    Target IP address (default: %s or PIPUP_IP env)\n" "${DEFAULT_IP}"
  printf "  -h    Display this help message\n"
  exit 0
}

#######################################
# Formats a value in KB into a human-readable KB/MB string.
# Arguments:
#   $1: Value in KB (integer, e.g., 2048 or -1500)
# Outputs:
#   Formatted string (e.g., "+2.0 MB" or "+512 KB")
#######################################
format_bytes() {
  local kb="${1:-0}"
  local abs_kb="${kb#-}"

  if [[ "${abs_kb}" -ge 1024 ]]; then
    awk -v val="${kb}" 'BEGIN { printf "%+.2f MB", val / 1024 }'
  else
    printf "%+d KB" "${kb}"
  fi
}

#######################################
# Extracts a specific metric from raw ANSI-formatted script output.
# Arguments:
#   $1: Raw script output string
#   $2: Metric pattern to search for
# Outputs:
#   Extracted value or 'N/A'
#######################################
extract_metric() {
  local raw_output="${1}"
  local pattern="${2}"
  local value

  value=$(echo "${raw_output}" \
    | sed -e 's/\x1b\[[0-9;]*m//g' -e 's/\xc2\xa0/ /g' \
    | awk -F'|' -v pat="^[[:space:]]*${pattern}" '$0 ~ pat { gsub(/^[ \t]+|[ \t]+$/, "", $4); print $4 }' \
    | head -n 1)

  printf "%s\n" "${value:-N/A}"
}

#######################################
# Gets current Total PSS from dumpsys.
# Outputs:
#   Total PSS in KB (integer)
#######################################
get_current_pss() {
  local pss
  pss=$(adb shell dumpsys meminfo "${APP_PACKAGE}" 2>/dev/null | awk '/TOTAL PSS:/ {print $3}')
  printf "%s\n" "${pss:-0}"
}

#######################################
# Runs a single test iteration for a specified notification type.
# Arguments:
#   $1: Notification type (e.g., image, web)
#######################################
run_test_type() {
  local test_type="${1}"
  local pss_diff="N/A"
  local after_unload="N/A"
  local java_diff="N/A"
  local ctx_diff="N/A"

  adb_shell_safe am force-stop "${APP_PACKAGE}"
  sleep 2
  adb_shell_safe am start -n "${APP_ACTIVITY}"
  sleep 6

  set_media_mode 2
  sleep 1

  local baseline_pss
  baseline_pss=$(get_current_pss)

  timeout -k 1s "${TIMEOUT_SEC}s" "${NOTIFY_SCRIPT}" -d "${TARGET_IP}" -t "${test_type}" -M u </dev/null >"${TMP_LOG}" 2>&1 &
  local notify_pid=$!

  local exit_code=0
  wait "${notify_pid}" || exit_code=$?

  if [[ ${exit_code} -eq 124 ]]; then
    java_diff="TIMEOUT"
    ctx_diff="TIMEOUT"
  elif [[ ${exit_code} -gt 128 ]]; then
    return 130
  else
    local raw_output
    raw_output=$(<"${TMP_LOG}")
    java_diff=$(extract_metric "${raw_output}" "Java Heap")
    ctx_diff=$(extract_metric "${raw_output}" "AppContexts")
  fi

  sleep 3
  local peak_pss
  peak_pss=$(get_current_pss)

  if [[ "${baseline_pss}" -gt 0 && "${peak_pss}" -gt 0 ]]; then
    pss_diff=$(format_bytes "$((peak_pss - baseline_pss))")
  fi

  if [[ "${test_type}" =~ ^(web|whep)$ ]]; then
    api_request "POST" "/debug/idle?ms=1000" >/dev/null
    sleep 2
    api_request "POST" "/debug/unload" >/dev/null
    sleep 2

    local recovered_pss
    recovered_pss=$(get_current_pss)

    if [[ "${baseline_pss}" -gt 0 && "${recovered_pss}" -gt 0 ]]; then
      local net_diff=$((recovered_pss - baseline_pss))
      local dropped=$((peak_pss - recovered_pss))
      local net_fmt
      local drop_fmt
      net_fmt=$(format_bytes "${net_diff}")
      drop_fmt=$(format_bytes "${dropped}")
      after_unload="${net_fmt} (${drop_fmt#+})"
    else
      after_unload="0 KB"
    fi
  fi

  printf "%-10s | %-12s | %-24s | %-10s | %-8s\n" "${test_type}" "${pss_diff}" "${after_unload}" "${java_diff}" "${ctx_diff}"
}

#######################################
# Main execution function.
#######################################
main() {
  trap cleanup EXIT INT TERM

  while getopts "d:h?" opt; do
    case "${opt}" in
      d) TARGET_IP="${OPTARG}" ;;
      h|?) usage ;;
    esac
  done

  local tmp_dir="${TMPDIR:-/tmp}"
  [[ -d "/dev/shm" && -w "/dev/shm" ]] && tmp_dir="/dev/shm"
  TMP_LOG=$(mktemp -p "${tmp_dir}" pipup_test_XXXXXX.log)

  setup_adb_forwarding "${TARGET_IP}"

  adb_shell_safe am start -n "${APP_ACTIVITY}"
  sleep 4

  ORIGINAL_MODE=$(api_request "GET" "/settings" | grep -o '"media":[0-9]*' | awk -F':' '{print $2}' || true)
  ORIGINAL_MODE="${ORIGINAL_MODE:-2}"

  local separator="------------------------------------------------------------------------------------------------"
  printf "Starting Systematic Leak & Recovery Test [Target IP: %s]...\n" "${TARGET_IP}"
  printf "%s\n" "${separator}"
  printf "%-10s | %-12s | %-24s | %-10s | %-8s\n" "TYPE" "PEAK DIFF" "AFTER UNLOAD (DROP)" "JAVA DIFF" "CTX DIFF"
  printf "%s\n" "${separator}"

  local test_types=("message" "png" "video" "web" "whep")
  for type in "${test_types[@]}"; do
    run_test_type "${type}"
  done

  printf "%s\n" "${separator}"
}

main "$@"
