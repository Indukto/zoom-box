# EU & Germany compliance checklist — ZoomBox Camera

Walkthrough order matches the Play Console flow. Anything marked ✅ is
already implemented in the app.

## 1. Data safety (EU: GDPR / DSGVO)

- ✅ App is offline by design — no `INTERNET` permission, no SDKs that
  transmit data → **"No data collected"** is the correct, honest answer.
- ✅ Photos excluded from cloud backup (`backup_rules.xml`,
  `data_extraction_rules.xml`); settings-only backup is standard Android
  platform behavior.
- ✅ No location permission; no GPS EXIF written to photos.
- Fill the form per [DATA_SAFETY.md](DATA_SAFETY.md); host
  [PRIVACY_POLICY.md](PRIVACY_POLICY.md) at a public URL.
- ✅ In-app transparency: Settings → Legal → Privacy (EN + DE) — satisfies
  GDPR Arts. 12–14 transparency even for users who never open the listing.

## 2. DSA — trader status (EU)

- Play Console → **Monetisation setup → EU Digital Services Act** →
  declare. New apps for the EU **cannot be published** without this since
  Feb 2025.
- Selling the app, IAP, ads, or any continuous commercial intent ⇒ you are
  a **trader** (Art. 2 (4) DSA) and must pass verification (name, address,
  phone, email + bank/business document). Verified details appear publicly
  on your listing — keep identical to the Impressum.
- Truly non-commercial hobby app ⇒ "non-trader" declaration possible, but
  Play then suppresses the app in the EU for users it deems
  commercially-targeted; the safer reading for a public Play release is
  trader.

## 3. German Impressum (§ 5 DDG, § 18 MStV)

- ✅ In-app: Settings → Legal → Imprint (EN + DE).
- Fill [IMPRESSUM.md](IMPRESSUM.md) and the in-app strings
  (`imprint_p_provider`, `imprint_p_mstv`) with real identity data —
  **before** release; a missing/incorrect Impressum is the single most
  common German fine vector for apps (up to €50,000, § 3 DDG).
- The store listing itself also needs the imprint URL in "Store listing →
  website" or the privacy policy field — German case law (LG München,
  LG Aschaffenburg) treats app-store listings as telemedia.

## 4. GPSR — General Product Safety Regulation (Reg. (EU) 2023/988)

Applies to "products" placed on the EU market; consumer software offered
via app stores is treated as covered by platform-level enforcement and the
DSA trader regime. Practical duties for this release:

- ✅ Safe by design: the app is a pure offline utility; no user accounts,
  no UGC platform, no dark patterns, no data-driven risk.
- Camera + flash usage around children/eyes is the only physical-world
  hazard vector: ✅ flash modes are user-initiated only; no strobe/looping
  flash behavior.
- If you are established outside the EU or want belt-and-braces coverage:
  appoint an EU **responsible person** (economic operator, Art. 16 GPSR)
  and add them to the Impressum ("EU responsible person: …").
- Keep technical documentation (this repo) available for market
  surveillance for 10 years if you take the GPSR route seriously.

## 5. Content rating (IARC) + PEGI/USK

- Play Console → **Policy → App content → Content rating** → questionnaire:
  camera app, no chat, no UGC, no purchases, no ads → expect **PEGI 3 /
  USK 0 / ESRB E / IARC 4+**.
- Do **not** tick "users can share content with each other" — the share
  sheet is user-initiated system functionality, not in-app sharing.
- Germany: USK 0 needs no age gate. ✅ No age verification needed anywhere.

## 6. Target audience & ads

- Target audience: 13+ (or 18+; either is defensible) — NOT children.
- Ads declaration: **No ads**. ✅ No ad SDK present.

## 7. Accessibility & language (Germany)

- ✅ Full German UI (values-de) — strongly expected for a German-market
  listing (DOM test: listing languages should include German; Play localizes
  automatically once translations exist).
- Content descriptions present for interactive icons (screen readers).
  Consider TalkBack pass before release.

## 8. Export / crypto

- App content → Export regulations: standard "does not use encryption
  beyond Android platform defaults" answer is fine — the app ships no
  custom cryptography.

## 9. Open-source compliance

- ✅ GPL-3.0 declared in-app (Settings → Legal → Licenses) with source URL —
  satisfies GPL § 4–6 for a distributed binary (via Play the source offer is
  the repo link; keep it valid).
- ✅ MIT notice for DAZZ-derived LUTs (`app/src/main/assets/luts/NOTICE.txt`
  + in-app licenses screen).
- Apache-2.0 libraries: attribution in-app; AndroidX "NOTICE" files are
  included automatically by AGP packaging.

## 10. Release tripwires (re-check every update)

1. `INTERNET` permission regression → Data safety form wrong → app removal
   risk. CI check: grep merged manifest.
2. Imprint/privacy placeholders (`[Legal name]`, `[DATE]`, `[EMAIL]`) left
   unfilled.
3. DSA trader data ≠ Impressum data after a move/rename.
4. New dependency with telemetry (e.g. Firebase) → full Data safety +
   privacy policy rewrite, DSGVO Verarbeitervereinbarung needed.
5. versionCode not bumped → Play rejects the upload.

## 11. Consumer law notes (Germany, for a paid app)

- Price incl. 19% VAT shown on Play; you are the seller of record unless
  using Google's merchant program differently — keep invoices for 10 years
  (§ 147 AO / § 257 HGB).
- Widerrufsrecht (right of withdrawal, 14 days) applies to consumers for
  digital content; Google's Play billing flow handles the EU withdrawal
  notice — do not promise anything contradictory in the listing.
- Gewährleistung (liability for defects) per §§ 475 ff. BGB — no
  disclaimer possible vs. consumers; the in-app "NO WARRANTY" text is GPL
  boilerplate and applies between you and non-consumers/licensees, not as
  a consumer-law shield. (Common misunderstanding — harmless here, but
  don't rely on it.)
