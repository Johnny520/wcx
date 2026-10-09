# Round 20 Changelog

- Removed broad `androidx.compose.**` keep rule so R8 can use Compose consumer rules and shrink unreachable code.
- Removed broad `kotlinx.serialization.**` keep rule while retaining application serializer-specific rules.
- Removed the redundant keep rule for WeChat classes that are configured as `compileOnly` stubs.
- Removed 13 stale `.bak_*` Kotlin snapshots from the application source tree (1,004,253 bytes); these were not Kotlin compilation inputs.
- Added `WECHAT_8.0.79_FULL_SOURCE_OPTIMIZATION_ROUND20.md` documenting verified findings and unverified build/runtime checks.

Validation limitation: full Gradle release build and APK size comparison remain pending because the Gradle distribution is not available in the environment.
