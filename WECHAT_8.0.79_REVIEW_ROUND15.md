# WCX / WeChat 8.0.79 review — round 15

This round tightens runtime descriptor validation only; it does not claim that every WCX feature is adapted.

## Changes

1. `DynamicClassScanner.verify` now verifies field type equality as well as field existence.
2. Array descriptors are parsed by component type instead of using a substring check for `V`, which incorrectly rejected valid types such as `[Lcom/example/Video;`.
3. Object descriptors validate the internal class name before class loading.

## Remaining limitations

- The generic `ClassFeature` registry IDs still do not provide a complete explicit mapping to all `BaseFeature` instances. Fuzzy mapping is intentionally not introduced because it could inject an unrelated target.
- No successful Gradle compile, APK build, or runtime hook test is claimed. The Gradle wrapper distribution is unavailable in the current environment.
4. Cloud feature cache is now accepted only when its recorded WeChat version matches the running host version; mismatched cache entries are discarded so stale descriptors cannot be reused after an upgrade.
5. Cloud HTTP connections are closed through `finally` on success and failure paths.
6. Corrected a field descriptor mismatch: the existing project consumes DexKit field `typeName` values as Java names elsewhere, but the dynamic injector emits DEX field descriptors. Scan results now normalize Java field types such as `java.lang.String`, `boolean`, and `java.lang.String[]` into `Ljava/lang/String;`, `Z`, and `[Ljava/lang/String;` before verification and injection.
