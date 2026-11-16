#!/usr/bin/env bash
# Exports a packed CoreProtect database (the ericlmao/CoreProtect SQLite layout) into the neutral row
# files that ReplayBenchmark reads. See README.md in this folder.
#
#   export.sh <CoreProtect checkout> <database.db> <output folder> [first day] [last day] [threads]
set -euo pipefail

if [ "$#" -lt 3 ]; then
    echo "usage: $0 <CoreProtect checkout> <database.db> <output folder> [first day] [last day] [threads]" >&2
    exit 2
fi

here="$(cd "$(dirname "$0")" && pwd)"
fork="$1"
database="$2"
output="$3"
first="${4:-0}"
last="${5:-99999}"
threads="${6:-5}"
zstd="${ZSTD_JAR:-$HOME/.m2/repository/com/github/luben/zstd-jni/1.5.7-6/zstd-jni-1.5.7-6.jar}"
sqlite="${SQLITE_JAR:-$HOME/.m2/repository/org/xerial/sqlite-jdbc/3.53.0.0/sqlite-jdbc-3.53.0.0.jar}"
classes="${EXPORT_CLASSES:-$HOME/.cache/hawkeye-bench/reference-export-classes}"
codec="$fork/src/main/java/net/coreprotect/database"

for file in "$zstd" "$sqlite" "$codec/ColdSegmentCodec.java"; do
    if [ ! -f "$file" ]; then
        echo "missing $file (set ZSTD_JAR / SQLITE_JAR, or check the CoreProtect checkout)" >&2
        exit 1
    fi
done

mkdir -p "$classes" "$output"
# The fork's codec and the two small classes it uses, a stand-in for its configuration-bound
# compression settings, and the exporter.
javac -nowarn -cp "$zstd:$sqlite" -d "$classes" \
    "$codec/ColdSegmentCodec.java" "$codec/SegmentFilter.java" "$codec/ColdSegmentReadCounter.java" \
    "$here/stub/net/coreprotect/utility/serialize/BlobCompression.java" "$here/RefDump.java"
exec java -Xmx5g -cp "$classes:$zstd:$sqlite" RefDump "$database" "$output" "$first" "$last" "$threads"
