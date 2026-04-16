#!/usr/bin/env bash
# =============================================================================
#  compile_and_run.sh
#  Zero-Maven build & run script for the Progressive Load & Recovery Engine.
#
#  Prerequisites: Java 17+, curl (for downloading SQLite JDBC if not present)
#  Usage:  chmod +x compile_and_run.sh && ./compile_and_run.sh
#
#  Flags:
#    --test    run the JUnit test suite instead of Main
#    --clean   wipe target/ before building
# =============================================================================

set -euo pipefail

# ── Configuration ──────────────────────────────────────────────────────────
SQLITE_JAR_URL="https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.45.1.0/sqlite-jdbc-3.45.1.0.jar"
JUNIT_URL="https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.2/junit-platform-console-standalone-1.10.2.jar"

LIB_DIR="lib"
SQLITE_JAR="$LIB_DIR/sqlite-jdbc-3.45.1.0.jar"
JUNIT_JAR="$LIB_DIR/junit-platform-console-standalone-1.10.2.jar"

SRC_DIR="src/main/java"
TEST_DIR="src/test/java"
RES_DIR="src/main/resources"
OUT_DIR="target/classes"
TEST_OUT="target/test-classes"

MAIN_CLASS="com.analytics.engine.Main"
TEST_CLASS="com.analytics.engine.EngineTest"

# ── Parse Flags ────────────────────────────────────────────────────────────
RUN_TESTS=false
DO_CLEAN=false
for arg in "$@"; do
    case $arg in
        --test)  RUN_TESTS=true ;;
        --clean) DO_CLEAN=true ;;
    esac
done

# ── Colour helpers ─────────────────────────────────────────────────────────
GREEN="\033[0;32m"; YELLOW="\033[0;33m"; RED="\033[0;31m"; RESET="\033[0m"
ok()   { echo -e "${GREEN}  ✔  $*${RESET}"; }
info() { echo -e "${YELLOW}  »  $*${RESET}"; }
err()  { echo -e "${RED}  ✘  $*${RESET}"; exit 1; }

echo ""
echo "════════════════════════════════════════════════════════════"
echo "  ⚡  Progressive Load & Recovery Engine — Build Script"
echo "════════════════════════════════════════════════════════════"

# ── Java version check ─────────────────────────────────────────────────────
if ! command -v javac &>/dev/null; then
    err "javac not found. Install Java 17+ (e.g. 'sudo apt install openjdk-21-jdk')."
fi
JAVA_VER=$(javac -version 2>&1 | awk '{print $2}' | cut -d. -f1)
if [[ "$JAVA_VER" -lt 17 ]]; then
    err "Java 17+ required. Found Java $JAVA_VER."
fi
ok "Java $JAVA_VER detected."

# ── Clean ──────────────────────────────────────────────────────────────────
if $DO_CLEAN; then
    info "Cleaning target/..."
    rm -rf target/
    ok "Clean done."
fi

# ── Create directories ─────────────────────────────────────────────────────
mkdir -p "$LIB_DIR" "$OUT_DIR" "$TEST_OUT"

# ── Download dependencies ──────────────────────────────────────────────────
download_jar() {
    local url="$1" dest="$2" label="$3"
    if [[ -f "$dest" ]]; then
        ok "$label already present — skipping download."
        return
    fi
    info "Downloading $label..."
    if command -v curl &>/dev/null; then
        curl -fsSL -o "$dest" "$url" || err "Failed to download $label. Check your internet connection."
    elif command -v wget &>/dev/null; then
        wget -q -O "$dest" "$url" || err "Failed to download $label."
    else
        err "Neither curl nor wget found. Please download manually:\n  $url\n  → $dest"
    fi
    ok "$label downloaded."
}

download_jar "$SQLITE_JAR_URL" "$SQLITE_JAR" "sqlite-jdbc-3.45.1.0.jar"
if $RUN_TESTS; then
    download_jar "$JUNIT_URL" "$JUNIT_JAR" "junit-platform-console-standalone-1.10.2.jar"
fi

# ── Copy schema.sql to classpath ───────────────────────────────────────────
cp "$RES_DIR/schema.sql" "$OUT_DIR/"
ok "schema.sql copied to classpath."

# ── Compile main sources ───────────────────────────────────────────────────
info "Compiling main sources..."
MAIN_SOURCES=$(find "$SRC_DIR" -name "*.java" | tr '\n' ' ')
# shellcheck disable=SC2086
javac --release 17 \
      -cp "$SQLITE_JAR" \
      -d "$OUT_DIR" \
      $MAIN_SOURCES 2>&1 | grep -v "^Note:" || true
ok "Main sources compiled ($(find "$OUT_DIR" -name '*.class' | wc -l | tr -d ' ') classes)."

# ── Compile tests (if --test) ──────────────────────────────────────────────
if $RUN_TESTS; then
    cp "$RES_DIR/schema.sql" "$TEST_OUT/"
    info "Compiling test sources..."
    # Extract JUnit API/Params from standalone jar for compilation
    TEST_SOURCES=$(find "$TEST_DIR" -name "*.java" | tr '\n' ' ')
    # shellcheck disable=SC2086
    javac --release 17 \
          -cp "$SQLITE_JAR:$JUNIT_JAR:$OUT_DIR" \
          -d "$TEST_OUT" \
          $TEST_SOURCES 2>&1 | grep -v "^Note:" || true
    ok "Test sources compiled."
fi

echo ""
echo "════════════════════════════════════════════════════════════"

# ── Run ────────────────────────────────────────────────────────────────────
if $RUN_TESTS; then
    echo "  Running test suite..."
    echo "════════════════════════════════════════════════════════════"
    echo ""
    java -cp "$SQLITE_JAR:$JUNIT_JAR:$OUT_DIR:$TEST_OUT" \
         org.junit.platform.console.ConsoleLauncher \
         --select-class="$TEST_CLASS" \
         --details=tree
else
    echo "  Launching application..."
    echo "════════════════════════════════════════════════════════════"
    echo ""
    java -cp "$SQLITE_JAR:$OUT_DIR" "$MAIN_CLASS"
fi
