#!/usr/bin/env bash
# =============================================================================
# rtlsdr-spring — startup script
# =============================================================================
set -euo pipefail

# -----------------------------------------------------------------------------
# Configuration — override any of these with environment variables
# -----------------------------------------------------------------------------

# Absolute path to the JAR (default: next to this script)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${RTLSDR_JAR:-${SCRIPT_DIR}/target/rtlsdr-spring-1.0.0.jar}"

# Java executable — uses JAVA_HOME if set, otherwise expects java on PATH
JAVA="${JAVA_HOME:+${JAVA_HOME}/bin/}java"

# Log file
LOG_DIR="${RTLSDR_LOG_DIR:-/var/log/rtlsdr-spring}"
LOG_FILE="${LOG_DIR}/rtlsdr-spring.log"

# librtlsdr shared library directory (leave empty to use OS default search)
RTLSDR_LIB_DIR="${RTLSDR_LIB_DIR:-}"

# Spring profile (default = production, set to 'dev' for local overrides)
SPRING_PROFILE="${SPRING_PROFILE:-default}"

# PID file location
PID_FILE="${RTLSDR_PID_FILE:-/var/run/rtlsdr-spring.pid}"

# JVM heap (tune to available RAM)
JVM_XMS="${JVM_XMS:-64m}"
JVM_XMX="${JVM_XMX:-256m}"

# Extra JVM flags (space-separated, append as needed)
EXTRA_JVM_OPTS="${EXTRA_JVM_OPTS:-}"

# -----------------------------------------------------------------------------
# Colour output helpers
# -----------------------------------------------------------------------------
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

info()  { echo -e "${CYAN}[INFO]${RESET}  $*"; }
ok()    { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
die()   { echo -e "${RED}[ERROR]${RESET} $*" >&2; exit 1; }

# -----------------------------------------------------------------------------
# Pre-flight checks
# -----------------------------------------------------------------------------
preflight() {
  info "Running pre-flight checks…"

  # Java
  if ! command -v "$JAVA" &>/dev/null; then
    die "Java not found. Set JAVA_HOME or add java to PATH."
  fi

  JAVA_VERSION=$("$JAVA" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
  if [[ "${JAVA_VERSION:-0}" -lt 21 ]]; then
    die "Java 21+ required (found version ${JAVA_VERSION:-unknown})."
  fi
  ok "Java: $("$JAVA" -version 2>&1 | head -1)"

  # JAR
  [[ -f "$JAR" ]] || die "JAR not found: $JAR\n       Run: mvn clean package -DskipTests"
  ok "JAR: $JAR"

  # librtlsdr
  if command -v ldconfig &>/dev/null; then
    # Linux
    if ldconfig -p 2>/dev/null | grep -q librtlsdr; then
      ok "librtlsdr found (ldconfig)"
    else
      warn "librtlsdr not found via ldconfig — device operations will be unavailable."
      warn "Install with: sudo apt install librtlsdr-dev"
    fi
  else
    # macOS
    for search_dir in /opt/homebrew/lib /usr/local/lib; do
      if [[ -f "${search_dir}/librtlsdr.dylib" ]]; then
        ok "librtlsdr found: ${search_dir}/librtlsdr.dylib"
        : "${RTLSDR_LIB_DIR:=${search_dir}}"
        break
      fi
    done
    if [[ -z "${RTLSDR_LIB_DIR:-}" ]]; then
      warn "librtlsdr not found — device operations will be unavailable."
      warn "Install with: brew install librtlsdr"
    fi
  fi

  # Log directory
  if [[ ! -d "$LOG_DIR" ]]; then
    info "Creating log directory: $LOG_DIR"
    mkdir -p "$LOG_DIR" 2>/dev/null || {
      warn "Cannot create $LOG_DIR — try: sudo mkdir -p $LOG_DIR && sudo chown $(whoami) $LOG_DIR"
      LOG_DIR="${SCRIPT_DIR}/logs"
      LOG_FILE="${LOG_DIR}/rtlsdr-spring.log"
      mkdir -p "$LOG_DIR"
      warn "Falling back to: $LOG_DIR"
    }
  fi

  if [[ ! -w "$LOG_DIR" ]]; then
    warn "$LOG_DIR is not writable — falling back to ${SCRIPT_DIR}/logs"
    LOG_DIR="${SCRIPT_DIR}/logs"
    LOG_FILE="${LOG_DIR}/rtlsdr-spring.log"
    mkdir -p "$LOG_DIR"
  fi

  # PID file directory
  PID_DIR="$(dirname "$PID_FILE")"
  if [[ ! -w "$PID_DIR" ]]; then
    PID_FILE="${SCRIPT_DIR}/rtlsdr-spring.pid"
    warn "Cannot write to $PID_DIR — using $PID_FILE"
  fi

  ok "Log file: $LOG_FILE"
}

# -----------------------------------------------------------------------------
# Build JVM arguments
# -----------------------------------------------------------------------------
build_jvm_args() {
  local args=(
    "-Xms${JVM_XMS}"
    "-Xmx${JVM_XMX}"

    # GC — ZGC is low-latency; suits a streaming I/O app
    "-XX:+UseZGC"

    # Crash dump next to the JAR for easy retrieval
    "-XX:ErrorFile=${SCRIPT_DIR}/hs_err_%p.log"

    # Log file location (overrides application.yml value)
    "-Dlogging.file.name=${LOG_FILE}"

    # Spring profile
    "-Dspring.profiles.active=${SPRING_PROFILE}"
  )

  # librtlsdr native library path (only when explicitly set)
  if [[ -n "${RTLSDR_LIB_DIR:-}" ]]; then
    args+=("-Djna.library.path=${RTLSDR_LIB_DIR}")
  fi

  # User-supplied extras
  if [[ -n "${EXTRA_JVM_OPTS:-}" ]]; then
    # shellcheck disable=SC2206
    args+=(${EXTRA_JVM_OPTS})
  fi

  echo "${args[@]}"
}

# -----------------------------------------------------------------------------
# Commands
# -----------------------------------------------------------------------------
cmd_start() {
  if [[ -f "$PID_FILE" ]]; then
    local old_pid
    old_pid=$(<"$PID_FILE")
    if kill -0 "$old_pid" 2>/dev/null; then
      die "Already running (PID $old_pid). Use '$0 restart' to restart."
    else
      warn "Stale PID file found — removing."
      rm -f "$PID_FILE"
    fi
  fi

  preflight

  local jvm_args
  # shellcheck disable=SC2207
  jvm_args=($(build_jvm_args))

  echo ""
  info "Starting rtlsdr-spring…"
  info "JVM args: ${jvm_args[*]}"
  echo ""

  "$JAVA" "${jvm_args[@]}" -jar "$JAR" &
  local pid=$!
  echo "$pid" > "$PID_FILE"

  # Give the app a moment then check it didn't immediately crash
  sleep 2
  if kill -0 "$pid" 2>/dev/null; then
    ok "Started — PID ${pid}"
    ok "Logs:    ${LOG_FILE}"
    echo ""
  else
    rm -f "$PID_FILE"
    die "Process exited immediately. Check ${LOG_FILE} for details."
  fi
}

cmd_start_fg() {
  preflight

  local jvm_args
  # shellcheck disable=SC2207
  jvm_args=($(build_jvm_args))

  echo ""
  info "Starting rtlsdr-spring in foreground…"
  echo ""

  exec "$JAVA" "${jvm_args[@]}" -jar "$JAR"
}

cmd_stop() {
  if [[ ! -f "$PID_FILE" ]]; then
    warn "PID file not found — is the application running?"
    return
  fi

  local pid
  pid=$(<"$PID_FILE")

  if ! kill -0 "$pid" 2>/dev/null; then
    warn "Process $pid is not running — cleaning up stale PID file."
    rm -f "$PID_FILE"
    return
  fi

  info "Stopping PID $pid…"
  kill -TERM "$pid"

  local waited=0
  while kill -0 "$pid" 2>/dev/null && [[ $waited -lt 30 ]]; do
    sleep 1
    (( waited++ ))
  done

  if kill -0 "$pid" 2>/dev/null; then
    warn "Graceful shutdown timed out — sending SIGKILL."
    kill -KILL "$pid"
  fi

  rm -f "$PID_FILE"
  ok "Stopped."
}

cmd_restart() {
  cmd_stop
  sleep 1
  cmd_start
}

cmd_status() {
  if [[ ! -f "$PID_FILE" ]]; then
    echo -e "${YELLOW}STOPPED${RESET} — no PID file found."
    return
  fi

  local pid
  pid=$(<"$PID_FILE")

  if kill -0 "$pid" 2>/dev/null; then
    ok "RUNNING — PID ${pid}"
    if command -v ps &>/dev/null; then
      ps -p "$pid" -o pid,vsz,rss,pcpu,etime 2>/dev/null || true
    fi
  else
    echo -e "${RED}DEAD${RESET} — PID file exists ($pid) but process is not running."
    echo "       Remove stale PID file with: rm $PID_FILE"
  fi
}

cmd_log() {
  local lines="${1:-50}"
  if [[ ! -f "$LOG_FILE" ]]; then
    warn "Log file not found: $LOG_FILE"
    return
  fi
  tail -n "$lines" -f "$LOG_FILE"
}

# -----------------------------------------------------------------------------
# Usage
# -----------------------------------------------------------------------------
usage() {
  echo ""
  echo -e "${BOLD}rtlsdr-spring startup script${RESET}"
  echo ""
  echo -e "  ${CYAN}$0${RESET}             Start in foreground (Ctrl+C to stop)"
  echo -e "  ${CYAN}$0 start${RESET}       Start in background"
  echo -e "  ${CYAN}$0 stop${RESET}        Graceful shutdown"
  echo -e "  ${CYAN}$0 restart${RESET}     Stop then start"
  echo -e "  ${CYAN}$0 status${RESET}      Show running state and PID"
  echo -e "  ${CYAN}$0 log [N]${RESET}     Tail log file (default last 50 lines)"
  echo ""
  echo -e "${BOLD}Environment variables:${RESET}"
  echo "  JAVA_HOME         Java installation directory"
  echo "  RTLSDR_JAR        Path to the JAR (default: ./target/rtlsdr-spring-1.0.0.jar)"
  echo "  RTLSDR_LIB_DIR    Path to librtlsdr directory (default: OS search path)"
  echo "  RTLSDR_LOG_DIR    Log directory (default: /var/log/rtlsdr-spring)"
  echo "  RTLSDR_PID_FILE   PID file path (default: /var/run/rtlsdr-spring.pid)"
  echo "  SPRING_PROFILE    Spring active profile (default: default)"
  echo "  JVM_XMS           JVM initial heap (default: 64m)"
  echo "  JVM_XMX           JVM max heap (default: 256m)"
  echo "  EXTRA_JVM_OPTS    Additional JVM flags"
  echo ""
}

# -----------------------------------------------------------------------------
# Entry point
# -----------------------------------------------------------------------------
case "${1:-}" in
  "")      cmd_start_fg ;;
  start)   cmd_start ;;
  stop)    cmd_stop ;;
  restart) cmd_restart ;;
  status)  cmd_status ;;
  log)     cmd_log "${2:-50}" ;;
  *)       usage; exit 1 ;;
esac
