# Reference export

`ReplayBenchmark` measures Hawkeye against a real CoreProtect history: it replays every row of a
packed CoreProtect database (the SQLite layout of the `ericlmao/CoreProtect` fork) as the events
Hawkeye would have captured, and compares the size of the two database files. Hawkeye's tests do not
contain the fork's codec, so the database is first exported into neutral row files with this script.

```bash
git clone https://github.com/ericlmao/CoreProtect ~/.cache/hawkeye-bench/src/CoreProtect
scripts/bench/reference-export/export.sh ~/.cache/hawkeye-bench/src/CoreProtect \
    /path/to/database.db ~/.cache/hawkeye-bench/dump
```

It needs a JDK 21 or newer and the `zstd-jni` and `sqlite-jdbc` jars (`ZSTD_JAR`, `SQLITE_JAR`; the
defaults point into `~/.m2`, where a build of the fork leaves them). The export decodes each segment
with the fork's own `ColdSegmentCodec`, attaches the stored entity to each kill, sorts every table
and day by time and row id, and writes `days/<day>/<table>.rows.zst` plus the dictionaries under
`meta/`; `ReferenceDump` in the test sources documents the row format. A run can be interrupted and
started again: finished files are kept. 1.4 billion rows take about an hour and 9 GB.

A database copied while the server ran can be one page shorter than its header says, because the
last page was still in the write-ahead log. SQLite then reports `database disk image is malformed`.
Appending one page of zeros (`page_size` bytes, 8192 for the fork) to a copy makes every packed row
readable; only rows that were still waiting to be packed are affected.
