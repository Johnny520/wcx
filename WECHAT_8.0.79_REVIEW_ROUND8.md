# WCX / WeChat 8.0.79 — Round 8 review

This round fixes concrete matcher-composition and type-encoding problems in the dynamic scanner, and a false-success condition in the active local adaptation engine.

## Why the scanner changes matter

DexKit's documented finder model exposes a matcher for each query. Conditions within a matcher compose together; the scanner previously called `matcher { ... }` repeatedly for different constraints. That could replace previously configured conditions, weakening a supposedly specific query. The updated implementation places mandatory constraints in a single matcher and explicitly unions query results where the feature contract says “any string/keyword”.

The feature registry stores some JVM descriptor forms (such as `V`, `I`, `Ljava/lang/String;`) while DexKit matcher APIs accept Java type names (such as `void`, `int`, `java.lang.String`). These are now normalized before method/field queries.

## Why the local-engine change matters

Previously `LocalAdaptationEngine.performAdaptation()` saved the current version and later logged success even if `scanAllFeatures()` had logged failures. That could suppress a future retry because the version appeared already adapted. The engine now fails the adaptation run before persisting version metadata whenever a Dex-resolvable item fails.

## Not completed / limitations

- The generic `ClassFeature` registry still does not provide a complete, verified mapping to every `BaseFeature` delegate in the project.
- Some registered signatures are still generic/heuristic and must be validated against the supplied WeChat 8.0.79 DEX.
- No complete Gradle build or APK build was possible in this environment because the configured Gradle distribution could not be downloaded.
- No runtime Hook tests or WeChat process regression tests were performed.

Do not treat version recognition or static scanner fixes as proof that every feature is compatible.
