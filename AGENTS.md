# Project handoff

- For repertoire optimization, read `docs/repertoire-optimization-handoff.md` first.
  Work from synthetic fixtures. Do not request, publish or depend on private books,
  local edit backups, account tokens or production signing keys.
- In a GitHub-only environment without the configured Telegram bridge, report
  completion and test results in the thread or PR. Patchy will handle local private
  validation, production deployment and the user's Telegram notification.
- After every user-requested InstinctaZero change, send a concise completion notification through the configured Telegram bridge once the usable result is ready.
- If work is genuinely blocked on a user decision, send a concise blocker notification instead.
- For Android releases, include the accessible GitHub release or APK download link. Do not notify completion for an unpublished or unverified artifact.
