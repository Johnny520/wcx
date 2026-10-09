# WCX / WeChat 8.0.79 review — round 16

## Changes

The injector previously validated cloud-provided mappings, but direct `ScanResult` mappings were accepted with less defensive checking. The direct method, constructor, and field paths now validate class names and DEX signatures before writing descriptors to delegates. Constructor descriptors must return `V`; constructors and class initializers are rejected by ordinary method injection.

## Not claimed

- This is defensive validation, not a substitute for mapping each generic scanner ID to the corresponding real WCX feature.
- No full Gradle build, APK output, or on-device WeChat 8.0.79 verification is claimed.
- Cloud sync remains disabled while its configured endpoint is a placeholder (`example.com`).
