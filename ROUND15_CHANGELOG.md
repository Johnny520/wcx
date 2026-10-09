# Round 15 changelog — strict field and array descriptor verification

- Runtime verification now checks a discovered field's actual Java type against the field descriptor, not just whether the field name exists.
- Fixed array descriptor validation so valid object arrays whose class names contain the letter `V` (for example `[Lcom/example/Video;`) are not rejected accidentally.
- Added internal class-name and reference/primitive descriptor grammar checks before resolving object and array types.

## Validation status

- Source-level structural checks and ZIP integrity checks only.
- Gradle/Kotlin compilation and in-host runtime verification are still not available in this environment.
- Cloud feature cache now rejects descriptors saved for a different WeChat version, preventing stale class/method mappings from leaking across host upgrades.
- Cloud HTTP connections are disconnected in `finally`, including non-200 responses and JSON parse failures.
- Corrected scanned field type normalization: DexKit exposes field types as Java names in this project, while injected field descriptors require DEX syntax. Scanned field types are now normalized to DEX descriptors before verification/injection (including primitives and arrays); invalid/void field types are rejected.
