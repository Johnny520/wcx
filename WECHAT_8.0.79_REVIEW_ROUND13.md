# WCX / WeChat 8.0.79 review — Round 13

## Changes

`DynamicHookInjector` no longer maps a method using a substring match between a delegate property and an arbitrary candidate method name. Uniqueness alone cannot establish that two methods have the same semantics, and obfuscated names make the heuristic particularly unreliable.

Cloud method and field mappings are now checked against the DEX descriptor grammar before a descriptor is written to a delegate. The method validator checks every parameter descriptor and the return descriptor; the field validator requires exactly one non-void type descriptor. Malformed cached/cloud data is rejected and logged.

## Remaining blockers

- The registry contains 12 generic `ClassFeature` scan IDs, but there is not yet a complete, evidence-backed mapping from those IDs to actual `BaseFeature` delegates. The code intentionally does not guess mappings.
- No successful full compile, APK build, or in-process runtime regression test is claimed. The Gradle wrapper requires Gradle 9.6.1, and this environment previously failed to resolve its distribution host.
- This is a defensive source change, not proof that all WCX features work on WeChat 8.0.79.
