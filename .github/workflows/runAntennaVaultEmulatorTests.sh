#!/bin/bash

set -o pipefail
adb logcat -c

runTests() {
    if [ -n "$TEST_CLASS" ]; then
        ./gradlew connectedFreeDebugAndroidTest \
            -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS"
    else
        ./gradlew connectedFreeDebugAndroidTest connectedDebugAndroidTest \
            -Pandroid.testInstrumentationRunnerArguments.notAnnotation=de.test.antennapod.IgnoreOnCi
    fi
}

if runTests || runTests; then
    echo "Tests succeeded"
else
    echo "Tests FAILED. Dumping logcat:"
    mkdir -p app/build/reports/androidTests
    adb logcat -d > app/build/reports/androidTests/logcat.txt
    exit 1
fi
