#!/bin/bash
#
# PiPup Network Service Discovery (NSD) Tester.
#
# This script requires 'avahi-utils' to be installed on Linux.
# It allows scanning for PiPup services and publishing mock instances.
#
# NOTE ON EMULATORS:
# Android emulators often have restricted multicast support. When testing
# with 'stress', the emulator's networking stack may drop packets if they
# arrive too fast, or it might not see host-published services at all.
# If services aren't appearing test on a physical device.

set -euo pipefail

#######################################
# GLOBALS
#######################################
readonly SERVICE_TYPE="_pipup._tcp"
readonly DEFAULT_PORT=7979
readonly DEFAULT_VERSION="0.7.0"

# UI Coloring
readonly CLR_RESET='\033[0m'
readonly CLR_INFO='\033[1;34m'    # Bold Blue
readonly CLR_SUCCESS='\033[1;32m' # Bold Green
readonly CLR_ERROR='\033[1;31m'   # Bold Red
readonly CLR_MOCK='\033[1;35m'    # Bold Magenta

#######################################
# HELPERS
#######################################

err() {
  printf "${CLR_ERROR}[ERROR] %s${CLR_RESET}\n" "$*" >&2
}

info() {
  printf "${CLR_INFO}[INFO] %s${CLR_RESET}\n" "$*"
}

success() {
  printf "${CLR_SUCCESS}[SUCCESS] %s${CLR_RESET}\n" "$*"
}

check_dependencies() {
  if ! command -v avahi-browse >/dev/null 2>&1; then
    err "avahi-browse not found. Please install 'avahi-utils'."
    exit 1
  fi
  if ! command -v avahi-publish-service >/dev/null 2>&1; then
    err "avahi-publish-service not found. Please install 'avahi-utils'."
    exit 1
  fi
}

#######################################
# FUNCTIONS
#######################################

#######################################
# Scans the network for active PiPup services.
#######################################
scan_services() {
  info "Scanning for PiPup services (${SERVICE_TYPE})..."
  info "Press Ctrl+C to stop scanning."
  printf "\n%-20s | %-15s | %-6s | %s\n" "SERVICE NAME" "ADDRESS" "PORT" "TXT RECORDS"
  printf -- "--------------------------------------------------------------------------------\n"

  # -r: Resolve, -t: Terminate after initial dump (we use live mode instead)
  avahi-browse -rt "${SERVICE_TYPE}" --ignore-local
}

#######################################
# Publishes a mock PiPup service to the network.
# Arguments:
#   Instance name
#   Device ID
#######################################
publish_mock() {
  local name="${1}"
  local dev_id="${2}"
  local version="${3:-$DEFAULT_VERSION}"

  printf "${CLR_MOCK}[MOCK] Publishing: %s (ID: %s, Ver: %s)${CLR_RESET}\n" \
    "${name}" "${dev_id}" "${version}"

  # Run in background and redirect output to keep UI clean
  avahi-publish-service "${name}" "${SERVICE_TYPE}" "${DEFAULT_PORT}" \
    "id=${dev_id}" "name=${name}" "version=${version}" >/dev/null 2>&1 &
}

#######################################
# Simulates multiple devices for stress testing.
# Arguments:
#   Number of devices to simulate
#######################################
stress_publish() {
  local count="${1}"
  info "Starting stress test: Publishing ${count} mock devices..."

  for ((i = 1; i <= count; i++)); do
    publish_mock "Mock TEST #${i}" "mock-id-${i}" "${DEFAULT_VERSION}"
    # Significant delay needed for unstable emulator network stacks.
    # Emulators often drop mDNS packets if published too rapidly,
    # sometimes resulting in a "4 device limit" visibility issue.
    sleep 0.5
  done

  success "${count} devices published. Press Ctrl+C to kill all mocks."
  # Wait for background jobs
  wait
}

usage() {
  cat <<EOF
Usage: ${0##*/} [COMMAND] [ARGS]

Commands:
  scan              Discover active PiPup instances on the network.
  mock [NAME] [ID]  Simulate a single PiPup device.
  stress [COUNT]    Simulate multiple devices to test UI list handling.
  help              Show this help message.

Examples:
  ${0##*/} scan
  ${0##*/} mock "Bedroom TV" "vader-123"
  ${0##*/} stress 10
EOF
  exit 1
}

#######################################
# MAIN
#######################################
main() {
  check_dependencies

  local cmd="${1:-help}"

  case "${cmd}" in
    scan)
      scan_services
      ;;
    mock)
      local name="${2:-Mock PiPup Test}"
      local dev_id="${3:-$(uuidgen 2>/dev/null || printf "mock-uuid-%s" "${RANDOM}")}"
      publish_mock "${name}" "${dev_id}"
      wait
      ;;
    stress)
      local count="${2:-5}"
      trap 'printf "\n"; success "Cleaning up..."; kill $(jobs -p) 2>/dev/null || true; exit 0' SIGINT SIGTERM
      stress_publish "${count}"
      ;;
    help|*)
      usage
      ;;
  esac
}

main "$@"
