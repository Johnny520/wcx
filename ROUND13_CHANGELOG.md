# Round 13 changelog

- Removed substring-based fuzzy method injection. A unique textual similarity is not proof of semantic equivalence, especially when the host application obfuscates method names.
- Added strict DEX descriptor grammar validation for cloud-provided method and field mappings before they can mutate a delegate descriptor.
- Kept exact feature-ID matching and valid explicitly configured cloud mappings as the only method/field mapping paths.
- Build status: source-level checks only; full Gradle compilation remains blocked by the unavailable Gradle 9.6.1 distribution in this environment.
