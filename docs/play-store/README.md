# Play Store / EU / Germany Compliance Package

Everything needed to pass Google Play's pre-launch review and to meet EU
(DSA, GDPR, GPSR) and German (DDG, VSBG) requirements for **ZoomBox Camera**.

The app itself is the strongest compliance asset: it is **fully offline**
(declares no `INTERNET` permission — Android blocks all network access), has
**no ads, analytics, trackers or crash reporting**, stores photos **only on
the device**, and **never writes GPS/location data** into photos.

## What ships in the app (done)

| Item | Where |
|---|---|
| In-app Privacy summary (GDPR transparency, EN + DE) | Settings → Legal → Privacy |
| In-app Impressum (§ 5 DDG, EN + DE) | Settings → Legal → Imprint |
| In-app open-source licenses (GPL-3.0 / Apache-2.0 / MIT) | Settings → Legal → Licenses |
| Full German UI translation (all strings) | `app/src/main/res/values-de/strings.xml` |
| Photos excluded from cloud backup & device transfer | `app/src/main/res/xml/*backup*.xml` |

## What you must do before publishing (templates here)

1. **Fill every `[bracket]` placeholder** in [IMPRESSUM.md](IMPRESSUM.md) with
   your real name, address and email — then paste the same data into the
   in-app imprint strings (`imprint_p_provider`, `imprint_p_mstv`) in
   `values/strings.xml` **and** `values-de/strings.xml`, and into
   `licenses_p_app` (copyright year + name + repository URL).
2. **Host the privacy policy** ([PRIVACY_POLICY.md](PRIVACY_POLICY.md)) at a
   public URL (e.g. GitHub Pages of your repo) — Play Console requires a URL,
   not an upload. Keep the in-app summary consistent with it.
3. **Answer the Data safety form** exactly as written in
   [DATA_SAFETY.md](DATA_SAFETY.md) ("No data collected" is correct and honest
   for this app — Play's definition of *collect* = transmitting off-device).
4. **Declare DSA trader status** in Play Console (Monetisation setup → EU
   Digital Services Act). You are a **trader** if you publish the app with
   any revenue intent or continuous commercial activity; non-trader
   declaration is only for genuinely non-commercial apps. Trader contact
   details are then displayed on your listing — keep them identical to the
   Impressum.
5. **Content rating questionnaire** (IARC): a camera app without user
   interaction/chat/ads typically rates USK 0 / PEGI 3 / ESRB E. Answer
   honestly; do not tick "shares user content" — sharing photos is
   user-initiated via the Android share sheet, not a Play-relevant
   interaction surface.
6. **Ads declaration**: "No ads". **Data safety**: see file above.
7. **GPSR check** (Reg. (EU) 2023/988): see the checklist — an offline
   utility app sold via Play is generally covered by the platform's economic
   structure; if you sell the app or are established outside the EU, appoint
   an EU responsible person and name them in the Impressum.
8. **VerpackG/ElektroG etc.** do not apply (no physical goods).

## File map

| File | Purpose |
|---|---|
| [PRIVACY_POLICY.md](PRIVACY_POLICY.md) | Full privacy policy (EN, GDPR-structured) to host at a public URL |
| [DATA_SAFETY.md](DATA_SAFETY.md) | Exact Play Console Data safety form answers |
| [IMPRESSUM.md](IMPRESSUM.md) | German imprint (§ 5 DDG) + § 18 MStV template |
| [EU_COMPLIANCE_CHECKLIST.md](EU_COMPLIANCE_CHECKLIST.md) | DSA, GDPR, GPSR, VSBG, IARC walkthrough with Play Console click-paths |
| [STORE_LISTING.md](STORE_LISTING.md) | Ready-to-paste listing texts (EN + DE) |

## Post-release duties (German law)

- **Impressum must stay current** — update the in-app imprint + listing the
  moment address/contact changes (fines up to €50,000 possible under § 3 DDG).
- Any change that adds network code, analytics, or ads invalidates the
  "No data collected" declaration — re-audit the Data safety form in that
  case (deleting the `INTERNET` permission regression is the tripwire: see
  checklist § 6).
