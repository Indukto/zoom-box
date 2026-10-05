# Play Console — Data safety form: exact answers

Path: **Play Console → your app → App content → Data safety → Start**

These answers reflect the app's real behavior (offline, no `INTERNET`
permission, no SDKs that phone home). Play's rule: *"Collect means
transmitting data from your app off a user's device"* — on-device-only
processing is explicitly out of scope (Play Console Help → Data safety →
"Not in scope for data collection: On-device access/processing").

## Section 1 — Data collection and security

> **Does your app collect or share any of the required user data types?**

**No.**

That single answer routes the form to the short path. Play will then show
the listing badge: *"No data collected"* and will still require the privacy
policy URL (next question):

> **Privacy policy URL:** `https://[your-domain]/privacy` (host
> PRIVACY_POLICY.md — a GitHub Pages / raw GitHub URL works)

## Section 2 — Data types

Nothing selected. (Do **not** select "Photos" — camera images never leave
the device, and per Play's on-device exception they are not collected.
Sharing a photo via the Android share sheet is a **user-initiated action**,
also excluded from "sharing".)

## Section 3 — Security practices (only shown if you answer Yes above)

Not applicable with "No". For completeness, the true answers would have
been: data encrypted in transit — n/a (nothing transmits); deletion
mechanism — n/a (no data held by developer); compliant with Families
policy — leave uncommitted (don't target children).

## Why "No data collected" is honest here — re-verify before every release

1. `AndroidManifest.xml` must contain **no** `INTERNET` permission
   (`adb shell dumpsys package com.indukto.zoomboxcamera | grep INTERNET`
   must return nothing; the merged manifest in `app/build/intermediates/`
   is the authoritative check).
2. No analytics/ads/crash SDKs in `gradle/libs.versions.toml`.
3. `AppScreenshotsTest`-grade CI note: if a future dependency ever pulls in
   network code, the merged-manifest check trips — re-audit and update this
   form **before** shipping.

## Ads declaration

App content → Ads → **"No ads"**.

## App access / credentials

App content → App access: **"All functionality is available without special
access"** (no login, no restricted area).
