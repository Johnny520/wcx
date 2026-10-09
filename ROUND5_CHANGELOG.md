# Round 5 Changelog

- Guarded against empty exact-match queries that can match arbitrary classes.
- Made exact, inheritance, and string-constant class selection conservative when multiple candidates exist.
- Rejected signature-based class selection when matching methods span multiple classes.
- Added a review report describing safety trade-offs and remaining blockers.
