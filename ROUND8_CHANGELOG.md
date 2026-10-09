# WCX 8.0.79 — Round 8 source fixes

## Changes

1. `DynamicClassScanner` now builds each mandatory DexKit class/method query in one matcher instead of repeatedly calling `matcher {}`. The DexKit finder has a single matcher property; repeated calls risk replacing earlier constraints and making queries broader than intended.
2. Class string features now respect their declared semantics: `stringConstantsAll` is queried together as an AND set; `stringConstants` and fallback keywords are queried as OR sets and merged by class name.
3. Inheritance/string/fallback scans apply known superclass and interface constraints alongside string constraints rather than matching strings first and accidentally accepting unrelated classes after a failed filter.
4. Method return types, parameter types and field types normalize JVM descriptors (`V`, `I`, `Ljava/lang/String;`, arrays) to Java type names before passing them to DexKit matchers.
5. Method and field candidate matching now applies declared static/non-static constraints and rejects ambiguity rather than selecting an arbitrary candidate.
6. `LocalAdaptationEngine` no longer records the current WeChat version as successfully adapted if one or more Dex-resolvable features fail. Failed feature names are included in the error, leaving the next app launch able to retry.
7. Added verified class-name hints and corrected direct superclasses/members for `LauncherUI`, `ChattingUI`, `ConversationListView`, `ImproveSnsTimelineUI`, legacy `SnsTimeLineUI`, `WebViewUI`, and `ChatFooter`, based on the supplied 8.0.79 DEX inventory. Evidence is recorded in `WECHAT_8.0.79_DEX_VERIFIED_TARGETS.md`.
8. `DynamicHookInjector` now rejects ambiguous fuzzy method matches instead of injecting whichever method happens to appear first.

## Verification status

- Source-level checks and ZIP integrity checks are performed for this archive.
- Full Gradle/Kotlin build could not be run because the wrapper's Gradle distribution (`gradle-9.6.1-bin.zip`) is not available locally and the configured mirror is unreachable in this environment (`UnknownHostException`).
- This is not a verified installable APK and does not claim all WeChat 8.0.79 hooks are adapted. A runtime DEX-by-DEX audit and in-process regression testing remain necessary.
