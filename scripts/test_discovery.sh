#!/bin/bash
#
# PiPup Network Service Discovery (NSD) Tester.
# Requires 'avahi-utils' to scan or publish mock instances.

set -euo pipefail

# --- Source Shared Common Library ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/common.sh"

readonly SERVICE_TYPE="_pipup._tcp"
readonly DEFAULT_VERSION="0.7.0"

#######################################
# Checks required avahi utilities.
# Outputs:
#   Errors to stderr and exits if missing.
#######################################
check_dependencies() {
  if ! command -v avahi-browse >/dev/null 2>&1; then
    printf "%b[ERROR] avahi-browse not found. Please install 'avahi-utils'.%b\n" "${CLR_ERROR}" "${CLR_RESET}" >&2
    exit 1
  fi
  if ! command -v avahi-publish-service >/dev/null 2>&1; then
    printf "%b[ERROR] avahi-publish-service not found. Please install 'avahi-utils'.%b\n" "${CLR_ERROR}" "${CLR_RESET}" >&2
    exit 1
  fi
}

#######################################
# Scans the network for active PiPup services.
#######################################
scan_services() {
  printf "%b[INFO] Scanning for PiPup services (%s)... Press Ctrl+C to stop.%b\n" "${CLR_SUCCESS}" "${SERVICE_TYPE}" "${CLR_RESET}"
  printf "\n%-20s | %-15s | %-6s | %s\n" "SERVICE NAME" "ADDRESS" "PORT" "TXT RECORDS"
  printf -- "--------------------------------------------------------------------------------\n"

  avahi-browse -rt "${SERVICE_TYPE}" --ignore-local
}

#######################################
# Publishes a mock PiPup service to the network.
# Arguments:
#   $1: Instance name
#   $2: Device ID
#   $3: Version
#######################################
publish_mock() {
  local name="${1}"
  local dev_id="${2}"
  local version="${3:-$DEFAULT_VERSION}"

  printf "%b[MOCK] Publishing: %s (ID: %s, Ver: %s)%b\n" \
    "${CLR_SUCCESS}" "${name}" "${dev_id}" "${version}" "${CLR_RESET}"

  avahi-publish-service "${name}" "${SERVICE_TYPE}" "${PORT}" \
    "id=${dev_id}" "name=${name}" "version=${version}" >/dev/null 2>&1 &
}

#######################################
# Simulates multiple devices for stress testing.
# Arguments:
#   $1: Number of devices to simulate
#######################################
stress_publish() {
  local count="${1}"
  printf "%b[INFO] Starting stress test: Publishing %s mock devices...%b\n" "${CLR_SUCCESS}" "${count}" "${CLR_RESET}"

  for ((i = 1; i <= count; i++)); do
    publish_mock "Mock TEST #${i}" "mock-id-${i}" "${DEFAULT_VERSION}"
    sleep 0.5
  done

  printf "%b[SUCCESS] %s devices published. Press Ctrl+C to kill all mocks.%b\n" "${CLR_SUCCESS}" "${count}" "${CLR_RESET}"
  wait
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
Usage: ${0##*/} [-d target_ip] [-s] [-m name] [-S count] [-h]
Options:
  -d    Target IP (default: ${DEFAULT_IP})
  -s    Scan for active PiPup instances on the network
  -m    Simulate a single mock PiPup device with given name
  -S    Simulate multiple mock devices (stress test count)
  -h    Show this help

Examples:
  ${0##*/} -s
  ${0##*/} -m "Bedroom TV"
  ${0##*/} -S 10
EOF
  exit 1
}

#######################################
# Main execution logic.
# Globals:
#   DEFAULT_IP
#   PORT
# Arguments:
#   @: Command-line arguments passed to the script.
# Outputs:
#   Writes progress and execution results to stdout.
# Returns:
#   0 on success, non-zero on error.
#######################################
main() {
  local target_ip="${DEFAULT_IP}"
  local action="help"
  local mock_name="Mock PiPup Test"
  local stress_count="5"

  while getopts "d:sm:S:h" opt; do
    case "${opt}" in
      d) target_ip="${OPTARG}" ;;
      s) action="scan" ;;
      m)
        action="mock"
        mock_name="${OPTARG}"
        ;;
      S)
        action="stress"
        stress_count="${OPTARG}"
        if ! [[ "${stress_count}" =~ ^[0-9]+$ ]]; then
          printf "%b[ERROR] Invalid stress count '%s'. Must be an integer.%b\n" "${CLR_ERROR}" "${stress_count}" "${CLR_RESET}"
          exit 1
        fi
        ;;
      h|?) usage ;;
    esac
  done
  shift $((OPTIND - 1))

  setup_adb_forwarding "${target_ip}"
  check_dependencies

  case "${action}" in
    scan)
      scan_services
      ;;
    mock)
      local dev_id
      dev_id="$(uuidgen 2>/dev/null || printf "mock-uuid-%s" "${RANDOM}")"
      publish_mock "${mock_name}" "${dev_id}"
      wait
      ;;
    stress)
      trap 'printf "\n"; printf "%b[SUCCESS] Cleaning up...%b\n" "${CLR_SUCCESS}" "${CLR_RESET}"; kill $(jobs -p) 2>/dev/null || true; exit 0' SIGINT SIGTERM
      stress_publish "${stress_count}"
      ;;
    help|*)
      usage
      ;;
  esac
}

main "$@"
