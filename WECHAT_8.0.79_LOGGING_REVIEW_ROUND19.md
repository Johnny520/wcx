# WCX logging audit — Round 19

## Findings

1. The run logger had age-based cleanup but no per-file or total-size limit. A busy current-day file could therefore grow without a bound.
2. Verbose/debug records were always persisted to disk even in release builds, despite the logger also sending them to logcat.
3. `logChunked` allowed up to 200 × 4,000 characters per event, allowing individual network/database dumps to produce very large log output.
4. The run-log viewer's clear action deleted files directly while the writer could still hold the current file open. On Unix-like filesystems this can leave the logger writing to an unlinked file, so later records appear to vanish until the writer reopens.
5. The crash-log manager already limits the number of crash reports and caps the in-app preview, but individual exported crash reports remain full-sized by design.

## Implemented mitigations

- Size-based rotation at about 4 MiB per segment, age-based retention of three days, and pruning toward a 24 MiB total run-log budget.
- Maximum 32,000 characters per persisted record.
- Maximum 16 chunks per chunked dump; larger messages retain only the leading chunk plus a truncation notice.
- Persistent V logs disabled; persistent D logs restricted to debug builds. Android logcat remains available for verbose diagnostics.
- Serialized log clearing on the dedicated writer thread, including closing the open file and resetting dropped-record accounting.
- Log viewer recognizes rotated segment names and sorts by modification time.

## Limitations

- The 24 MiB budget is a best-effort cap checked during writer initialization/rotation; one active segment can make transient usage slightly exceed the budget until the next pruning point.
- Full Gradle compilation and Android runtime tests were not completed, so these changes require project build validation before release.
