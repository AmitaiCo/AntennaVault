#!/bin/bash

set -o pipefail
adb logcat -c

runTests() {
    ./gradlew connectedFreeDebugAndroidTest connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.notAnnotation=de.test.antennapod.IgnoreOnCi
}

if runTests || runTests; then
    echo "Tests succeeded"
else
    echo "Tests FAILED. Dumping logcat:"
    mkdir -p app/build/reports/androidTests
    adb logcat -d > app/build/reports/androidTests/logcat.txt
    exit 1
fi
