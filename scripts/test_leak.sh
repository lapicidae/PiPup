#!/bin/bash
#
# Systematic Leak Test for PiPup.
# Measures memory differences across multiple notification types using ADB.

set -euo pipefail

# Determine the absolute directory where this script resides
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR

# Global Constants
readonly TIMEOUT_SEC=120
readonly ADB_TIMEOUT_SEC=10
readonly APP_PACKAGE="nl.rogro82.pipup"
readonly APP_ACTIVITY="${APP_PACKAGE}/.MainActivity"
readonly NOTIFY_SCRIPT="${SCRIPT_DIR}/send_notify.sh"
readonly RAM_DISK_DIR="/dev/shm"

# Signal trap for instant process termination on interrupt (Ctrl+C).
cleanup() {
  trap - SIGINT SIGTERM
  echo -e "\nExecution forcibly aborted by user. Terminating process tree..." >&2
  # Forcefully kill all processes in current process group immediately
  kill -9 0 2>/dev/null || true
  exit 130
}

trap cleanup SIGINT SIGTERM

# Parses the output from the notify script for specific metric diffs.
# Arguments:
#   $1: Raw script output string
#   $2: Metric pattern to search for (e.g., "Total PSS")
extract_metric() {
  local raw_output="${1}"
  local pattern="${2}"
  local value

  value=$(echo "${raw_output}" \
    | sed 's/\x1b\[[0-9;]*m//g' \
    | sed 's/\xc2\xa0/ /g' \
    | grep -E "^[[:space:]]*${pattern}" \
    | awk -F'|' '{print $4}' \
    | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' || true)

  echo "${value:-N/A}"
}

# Runs a single test iteration for a specified notification type.
# Arguments:
#   $1: Notification type
run_test_type() {
  local test_type="${1}"
  local tmp_log=""
  local exit_code=0
  local raw_output=""
  local pss_diff="N/A"
  local java_diff="N/A"
  local ctx_diff="N/A"

  # Ensure temporary log file is deleted safely upon function return
  trap '[[ -n "${tmp_log:-}" ]] && rm -f "${tmp_log}"' RETURN

  # Use RAM disk (/dev/shm) if available, fall back to default TMPDIR
  local tmp_dir="${TMPDIR:-/tmp}"
  [[ -d "${RAM_DISK_DIR}" && -w "${RAM_DISK_DIR}" ]] && tmp_dir="${RAM_DISK_DIR}"

  tmp_log=$(mktemp -p "${tmp_dir}" pipup_test_XXXXXX.log)

  # Force-stop and restart app with hard SIGKILL backup if hanging (-k 1s)
  timeout -k 1s "${ADB_TIMEOUT_SEC}s" adb shell am force-stop "${APP_PACKAGE}" >/dev/null 2>&1 || true
  sleep 2
  timeout -k 1s "${ADB_TIMEOUT_SEC}s" adb shell am start -n "${APP_ACTIVITY}" >/dev/null 2>&1 || true
  sleep 6

  # Execute script redirected to temp file in RAM to immediately return upon script exit
  if timeout -k 1s "${TIMEOUT_SEC}s" "${NOTIFY_SCRIPT}" -t "${test_type}" -M u </dev/null >"${tmp_log}" 2>&1; then
    exit_code=0
  else
    exit_code=$?
  fi

  raw_output=$(cat "${tmp_log}" 2>/dev/null || true)

  if [[ ${exit_code} -eq 124 ]]; then
    pss_diff="TIMEOUT"
    java_diff="TIMEOUT"
    ctx_diff="TIMEOUT"
  else
    pss_diff=$(extract_metric "${raw_output}" "Total PSS")
    java_diff=$(extract_metric "${raw_output}" "Java Heap")
    ctx_diff=$(extract_metric "${raw_output}" "AppContexts")
  fi

  printf "%-10s | %-12s | %-12s | %-8s\n" "${test_type}" "${pss_diff}" "${java_diff}" "${ctx_diff}"
}

main() {
  local -a test_types=("message" "png" "video" "web" "whep")
  local line='------------------------------------------------------'

  echo "Starting Systematic Leak Test..."
  printf '%s\n' "${line}"
  printf "%-10s | %-12s | %-12s | %-8s\n" "TYPE" "PSS DIFF" "JAVA DIFF" "CTX DIFF"
  printf '%s\n' "${line}"

  for type in "${test_types[@]}"; do
    run_test_type "${type}"
  done

  printf '%s\n' "${line}"
}

main "$@"
