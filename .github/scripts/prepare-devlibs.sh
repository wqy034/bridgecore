#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEVLIBS_DIR="${DEVLIBS_DIR:-$ROOT_DIR/devlibs}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
mkdir -p "$DEVLIBS_DIR"

verify() {
    local file="$1"
    local expected="$2"
    printf '%s  %s\n' "$expected" "$file" | sha256sum --check --status -
}

download() {
    local url="$1"
    local name="$2"
    local expected="$3"
    local target="$WORK_DIR/$name"
    curl --fail --location --silent --show-error --retry 3 --retry-all-errors \
        --connect-timeout 20 --output "$target" "$url"
    verify "$target" "$expected"
    cp "$target" "$DEVLIBS_DIR/$name"
}

extract() {
    local archive="$1"
    local member="$2"
    local name="$3"
    local expected="$4"
    local target="$WORK_DIR/$name"
    unzip -p "$WORK_DIR/$archive" "$member" > "$target"
    verify "$target" "$expected"
    cp "$target" "$DEVLIBS_DIR/$name"
}

download \
    "https://github.com/wqy034/cbc-armature/releases/download/v1.7.0-alpha/fe_railgun-1.7.0-alpha.jar" \
    "fe_railgun-1.7.0-alpha.jar" \
    "c03d6a8918c3f88e7f407bc0f9f84c4a841e0938e6f6cf1c1db45bdcc02f5638"

download \
    "https://cdn.modrinth.com/data/gu7yAYhd/versions/8XEJbAee/cc-tweaked-1.21.1-forge-1.120.0.jar" \
    "cc-tweaked-1.21.1-forge-1.120.0.jar" \
    "81a903710d109d129c249c105695a96f4bad5e0acf7068920fb191ba791c14ce"

download \
    "https://api.modrinth.com/maven/maven/modrinth/create/6.0.10%2Bmc1.21.1/create-6.0.10%2Bmc1.21.1.jar" \
    "create-6.0.10+mc1.21.1.jar" \
    "ef87fe5709f1ba1f5b8bb20a2925b5afb4669e178fd6d8bf10c167759eefe37a"

extract \
    "create-6.0.10+mc1.21.1.jar" \
    "META-INF/jarjar/ponder-neoforge-1.0.82+mc1.21.1.jar" \
    "ponder-neoforge-1.0.82+mc1.21.1.jar" \
    "0cf4611ad853042b689ac386184c5bbe02950efcffddb49e5f604e82baddb0dc"

download \
    "https://cdn.modrinth.com/data/oWaK0Q19/versions/w7zlLnea/create-aeronautics-bundled-1.21.1-1.3.0.jar" \
    "create-aeronautics-bundled-1.21.1-1.3.0.jar" \
    "482c90e0e6fe72f33fe7abb079e5fda581cd660ee53c38c44448a64283e1044c"

extract \
    "create-aeronautics-bundled-1.21.1-1.3.0.jar" \
    "META-INF/jarjar/dev.simulated_team.simulated.simulated-neoforge-1.21.1-1.3.0.jar" \
    "simulated-nested-neoforge-1.21.1-1.3.0.jar" \
    "9cbfdaf421b450727232d7a2de5f9e2ab826ac2113a25fb1286ba431a1a8a403"

printf 'Prepared build-only dependencies in %s\n' "$DEVLIBS_DIR"
