# VoltNet Pro v40 / 2.22 — Navigation and first-run setup

## Changes in this source update
- Reordered the bottom navigation to: Home, Subscribers, Packages/Pricing, Accounts/Expenses, Network, Reports, Settings.
- Added a home-page section hub for subscribers, packages, accounts, network, reports, messaging/notifications settings, support reports, and general settings.
- Home-page shortcuts open the corresponding Settings subsection for messaging/notifications and support.
- Kept subscriber filter chips inside the subscriber page; these are filters, not app-level navigation tabs.
- Replaced the one-screen initial setup prompt with a four-step guided setup.
- The SAS step saves credentials only after a real read-only SAS test and user-list fetch succeed.
- Added a preview and explicit import action. Existing PPPoE usernames are counted as duplicates and skipped by the existing import routine; the wizard displays missing-name/expiry counts.
- Explicitly states that tower selection is unavailable in the current SAS connector, rather than pretending to select a tower.
- Allows deferring setup and returning to configuration later.

## Verification limitations
- ZIP/source integrity and Kotlin parser-level syntax scan were checked.
- A full Android/Gradle build was not run: this source archive does not include `gradle-wrapper.jar`, and no system Gradle executable is available in the build environment.
- SAS connection, preview, and import must be tested against the user's actual SAS4 panel and credentials.
