# Round 16 changelog

- Added defensive validation to the direct scan-result injection path, not only cloud mappings.
- Method injection now rejects malformed class names/signatures and prevents constructors/class initializers from being injected as ordinary methods.
- Constructor injection requires a valid method descriptor whose return type is `V`.
- Field injection now validates owner names and DEX field descriptors before setting a delegate descriptor.
- These checks reduce the chance that malformed or stale scan results overwrite a valid delegate descriptor. They do not establish missing `ClassFeature` ↔ `BaseFeature` mappings.

## Validation status

- Static source checks and focused descriptor test cases are included in the review workflow.
- Full Android/Gradle build and WeChat runtime tests remain unavailable because the Gradle 9.6.1 distribution cannot be downloaded in this environment.
