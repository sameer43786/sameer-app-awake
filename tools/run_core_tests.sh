#!/usr/bin/env bash
set -euo pipefail

# Dependency-free core test runner.
# By: Sameer Ali | Contact: sameer43786@gmail.com

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_output="${TMPDIR:-/tmp}/sameer-app-awake-core-tests"

mkdir -p "$test_output"

javac \
  -d "$test_output" \
  "$project_root/app/src/main/java/com/sameerali/appawake/ForegroundAppTracker.java" \
  "$project_root/tools/ForegroundAppTrackerTest.java"

java -cp "$test_output" com.sameerali.appawake.ForegroundAppTrackerTest
