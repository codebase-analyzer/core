#!/bin/bash
# Codebase Analyzer - run script
# Usage: ./analyze.sh /path/to/your/project [options]

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/analyzer-cli/target/analyzer-cli-0.1.0-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
    echo "Building project..."
    cd "$SCRIPT_DIR" && mvn clean package -q -DskipTests
fi

if [ ! -f "$JAR" ]; then
    echo "Build failed. Run 'mvn clean package' manually to see errors."
    exit 1
fi

java -jar "$JAR" "$@"
