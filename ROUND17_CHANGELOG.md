# Round 17 changelog

- Inspect the uploaded WeChat 8.0.79 APK's DEX strings and cross-check the settings classes used by WCX.
- Namespace the legacy settings preference key as `wcx_settings_entry_v1`.
- Replace the modern test/WeKit-like key with `SettingGroup_Main_WCX_Settings_v1`.
- Remove WCX's hook on `SettingGroupPersonalInfo` as an insertion anchor, reducing last-hook-wins conflicts with other modules that alter the same built-in settings location.
- Add a review report documenting validation scope and remaining build/runtime limitations.
