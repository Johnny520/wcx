# Round 19 — Logging subsystem size and lifecycle fixes

## Changes

- Added size-based run-log rotation: a single active daily log segment is limited to approximately 4 MiB; rotated segments use `wcx-YYYY-MM-DD-<epochMillis>.log` names.
- Added retention enforcement: run logs older than three days are removed, and total run-log storage is pruned toward a 24 MiB budget.
- Reduced the maximum chunked dump from 200 chunks to 16 chunks (about 64 KiB); oversized dumps now retain a bounded head and an explicit truncation notice.
- Truncate any single persisted record to 32,000 characters, including throwable stack traces, to prevent one unusually large message from bypassing the file size limit.
- Keep V-level messages out of persistent files and persist D-level messages only in debug builds. Logcat behavior remains unchanged; INFO/WARN/ERROR/ASSERT continue to be persisted.
- Fixed the “clear run logs” lifecycle: clearing is serialized on the writer thread, closes the active writer before deleting files, and allows subsequent messages to create a fresh log file.
- Updated the log viewer to list rotated segments and sort by modification time, and routed its clear action through the safe logger API.

## Validation

- Static source checks and log filename-pattern checks completed.
- ZIP integrity checked after packaging.
- Full Android Gradle compilation and runtime validation remain unavailable in this environment; this round is not represented as a successful APK build.
