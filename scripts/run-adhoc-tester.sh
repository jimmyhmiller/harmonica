#!/bin/bash

# Run the Harmonica Parser Ad-hoc Tester
#
# Usage:
#   ./scripts/run-adhoc-tester.sh [options] <source-dirs...>
#
# Examples:
#   ./scripts/run-adhoc-tester.sh --mode=parse ../some-js-project
#   ./scripts/run-adhoc-tester.sh --mode=json --threads=8 test-oracles/test262/test

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Build if needed
echo "Building project..."
cd "$PROJECT_ROOT"
mvn compile -q -pl harmonica-jackson -am

# Change to harmonica-jackson directory so acorn can be found
cd "$PROJECT_ROOT/harmonica-jackson"

# Run the ad-hoc tester using Maven exec plugin
echo "Starting ad-hoc tester..."
mvn -q exec:java -Dexec.args="$*"
