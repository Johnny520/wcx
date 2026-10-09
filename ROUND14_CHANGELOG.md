# Round 14 changelog — runtime descriptor verification

- Strengthened `DynamicClassScanner.verify` to load the root target class even when the result has no member descriptors.
- Method verification now parses and checks the declared return type in addition to resolving the method by owner/name/parameter types.
- Constructor descriptors must explicitly use `V` as their return type.
- Added a strict JVM/DEX type-descriptor resolver for primitive, object, and array types.

## Validation status

- Source-level checks and ZIP integrity checks only.
- Full Gradle/Kotlin compilation and in-host runtime verification remain unperformed in this environment; do not treat this round as a built or runtime-tested APK.
