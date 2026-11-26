# LinkPeek development

- Preserve public preview URLs, provider SPI boundaries, configured title styles, and existing report/asset URLs unless the task explicitly changes them.
- Java 17, Spring Boot, SQLite, and native browser JavaScript are the current stack. Prefer small changes within it.
- The admin UI is native ES modules under `static/admin/js/` (no build step): `core/` holds state, API and DOM helpers, `shell/` holds navigation, panels and modals, and `features/` has one module per panel. Shared mutable state lives only in `core/state.js`; modules must not run DOM code at import time, so tests can import them with `src/test/js/support/fake-dom.mjs`.
- Model responses, crawled pages, and Markdown are untrusted data. Only final assistant output may become generated content; escape or sanitize rendered content.
- Database migrations must preserve existing tasks, reports, and asset references. Verify upgrades using fixture copies, including repeated initialization.
- Use isolated local databases and fake upstreams/notification recipients for tests. Do not use production credentials, logs, model calls, or notification channels as fixtures.
- Keep secrets and raw model content out of ordinary logs and committed tests. Use synthetic protocol examples.
- A passing test should establish user-visible behavior, compatibility, or a failure boundary. Avoid tests that merely assert CSS snippets or copy the implementation.
- Run `./mvnw -B verify` and `node --test linkpeek-server/src/test/js/*.test.mjs extensions/linkpeek-chrome/test/*.test.mjs` for behavior changes. Check unexpected asynchronous errors as well as exit status.
- For UI changes, verify the affected workflow in the browser, keyboard focus, and narrow layouts. Never claim unperformed checks passed.
- Update relevant docs with final behavior, validation, and remaining limitations.
