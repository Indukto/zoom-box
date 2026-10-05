# Privacy Policy — ZoomBox Camera

**Effective date:** [DATE] · **App:** ZoomBox Camera (Android)
**Publisher:** [LEGAL NAME], [STREET], [POSTCODE] [CITY], Germany
**Contact:** [EMAIL]

This policy describes how ZoomBox Camera ("the app") handles data. The
short answer: **it doesn't — there is no data flow to the publisher.**

## 1. Controller (Verantwortlicher)

The controller under Art. 4 (7) GDPR is the publisher named above.

## 2. No internet access by design

The app does not declare the `android.permission.INTERNET` permission. The
Android operating system therefore **blocks every network connection** the
app could attempt. The app contains no advertising SDKs, no analytics, no
telemetry, no crash-reporting services and no tracking of any kind. It
cannot transmit data to the publisher or to any third party.

## 3. Data processed on your device only

- **Photos & camera images** are captured, filtered, graded and stored
  exclusively on the device (public `Pictures/ZoomBoxCamera/` directory and
  the app's private storage). This processing happens locally; it is not
  "collection" under GDPR because no data reaches the publisher or any
  third party.
- **No location data:** the app does not access GPS, network location or any
  location permission, and it never embeds coordinates or place names in
  photo EXIF metadata.
- **App settings** (film look, grid lines, flash mode, etc.) are stored
  locally with Jetpack DataStore.

## 4. Permissions

| Permission | Purpose |
|---|---|
| `CAMERA` | Taking photos — core function, requested at first launch |
| `READ_MEDIA_IMAGES` (Android 13+) / `READ_EXTERNAL_STORAGE` (Android 10–12) | Showing previously saved photos in the in-app gallery (needed after a reinstall) |
| `WRITE_EXTERNAL_STORAGE` (Android 10 and older only) | Saving photos to `Pictures/ZoomBoxCamera/` |

The app requests permissions only in the foreground, only for the stated
purpose, and works without any network, telephony or identifier access.

## 5. Backups

The app opts into Android's automatic backup **for settings only**; photos
are explicitly excluded (`data_extraction_rules.xml` / `backup_rules.xml`).
Encrypted backups are stored in the user's own Google account, governed by
Google's privacy policy, and are not accessible to the publisher. Users can
disable backup in Android system settings.

## 6. No data from children

The app is not directed at children under 13 and does not knowingly collect
personal data from anyone — of any age.

## 7. Your rights (Art. 15–21 GDPR)

You have rights of access, rectification, erasure, restriction of
processing, data portability and objection, and the right to lodge a
complaint with a supervisory authority (in Germany: the data protection
authority of your federal state, e.g. LDI NRW, BayLDA, Berlin BlnBDI).

Because the publisher holds **no personal data** about you, these rights are
exercised directly on your device: delete photos via the in-app gallery or
the Android Photos app, or uninstall the app to remove all remaining app
data (including settings). Contact for privacy questions: [EMAIL].

## 8. Legal bases

There is no processing of personal data by the publisher. Local processing
described above occurs on the user's device under the user's control and is
not attributable to the publisher (no transfer, Art. 28 GDPR not triggered;
no third-country transfers, Chapter V GDPR not triggered).

## 9. Changes

Changes to this policy will be published at this URL with a new effective
date. The in-app Settings → Legal → Privacy screen always mirrors this
document.
