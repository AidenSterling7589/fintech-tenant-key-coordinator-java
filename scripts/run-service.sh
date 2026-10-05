#!/usr/bin/env sh
set -eu

BUILD_DIR="${TMPDIR:-/tmp}/tenant-keys-fintech-java-service"
mkdir -p "$BUILD_DIR"
find src/main/java -name '*.java' -print | sort > "$BUILD_DIR/sources.txt"
javac --release 17 -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
java -cp "$BUILD_DIR/classes" edu.fintech.tenantkeys.TenantKeysHttpService
