#!/usr/bin/env bash
# Runs the emulator steps of android-ci.yml for one flavor. Usage: ci_emulator_tests.sh <Fdroid|Play>
#
# android-emulator-runner runs every line of its `script` input in a separate shell, so exit codes
# and background processes cannot be shared between lines. Keeping the steps in one script file
# makes the exit codes and the memory sampler reliable.
set -euo pipefail

flavor="${1:?usage: ci_emulator_tests.sh <Fdroid|Play>}"

(while true; do echo "$(date +%T) $(free -m | awk '/Mem:/{print "used=" $3 "MB"}')"; sleep 5; done) > mem-emulator.log 2>&1 &
sampler=$!
trap 'kill "$sampler" 2>/dev/null || true' EXIT

# run_logged <log> <gradle task>: writes the Gradle output and its exit code to <log>, then fails
# the script with that exit code when Gradle failed.
run_logged() {
    local log="$1"
    shift
    local rc=0
    ./gradlew --no-daemon "$@" > "$log" 2>&1 || rc=$?
    echo "gradle exit code: $rc" >> "$log"
    return "$rc"
}

run_logged gradle-install.log :security-test-client:installDebug
run_logged gradle-connected.log ":app:connected${flavor}DebugAndroidTest"
