#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VARIANT="${1:-debug}"
case "$VARIANT" in
    debug) TASK_VARIANT=Debug ;;
    release) TASK_VARIANT=Release ;;
    *) echo "Usage: $0 [debug|release]" >&2; exit 1 ;;
esac

cd "$PROJECT_DIR"
bash ./gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:lintDebug ":app:assemble${TASK_VARIANT}"
mkdir -p dist
cp "app/build/outputs/apk/${VARIANT}/app-${VARIANT}.apk" "dist/gemma-translator-s24-${VARIANT}.apk"
echo "APK ready: ${PROJECT_DIR}/dist/gemma-translator-s24-${VARIANT}.apk"
