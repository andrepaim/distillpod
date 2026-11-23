#!/usr/bin/env bash
# Install pinned Android SDK packages on a hosted runner, retrying a corrupt or interrupted
# download (#281). sdkmanager exits nonzero when a package archive cannot be read.
set -euo pipefail
(( $# > 0 )) || { echo 'Name the Android SDK packages to install' >&2; exit 2; }
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
attempts=3
for (( attempt = 1; ; attempt++ )); do
    if sdkmanager --install "$@"; then
        exit 0
    fi
    if (( attempt >= attempts )); then
        echo "Android SDK installation failed after $attempt attempts" >&2
        exit 1
    fi
    echo "Android SDK installation attempt $attempt failed; discarding partial downloads before retrying" >&2
    rm -rf "${ANDROID_HOME:?}/.temp" "${ANDROID_HOME:?}/.downloadIntermediates"
    sleep $(( attempt * 15 ))
done
