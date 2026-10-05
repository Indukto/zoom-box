# Rename "aistudio" to "indukto"

The goal is to rename the occurrences of "aistudio" to "indukto" within the project, specifically focusing on the `applicationId`.

## User Review Required

> [!IMPORTANT]
> Changing the `applicationId` will change the unique identity of the app on the Google Play Store and on-device. If you have already published the app or have users with the current ID, they will see it as a completely different app.

## Proposed Changes

### [Component Name] Build Configuration

#### [MODIFY] [build.gradle.kts](file:///F:/#an/Bhig/app/build.gradle.kts)
- Update `applicationId` from `com.aistudio.zoomboxcamera.qvtkpd` to `com.indukto.zoomboxcamera.qvtkpd`.

## Verification Plan

### Automated Tests
- Run `./gradlew app:assembleDebug` to ensure the project still builds correctly with the new Application ID.
- Verify the generated `BuildConfig.java` contains the updated ID.

### Manual Verification
- Deploy the app to a device and verify it installs as a new app (if the old one was present).
