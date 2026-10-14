package eu.indukto.zoombox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/**
 * GitHub issue tracker. Pasted at the foot of every copied report so a report
 * forwarded through any channel still points back at the right place.
 */
const val ISSUE_TRACKER_URL = "https://github.com/Indukto/zoom-box/issues"

/**
 * Everything [formatBugReport] renders. Plain data (no Context, no Build) so
 * the formatting is pinnable by JVM unit tests; [collectBugReportSnapshot]
 * is the only impure step that reads the device and ViewModel.
 */
data class BugReportSnapshot(
    val appVersion: String,
    val packageName: String,
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val device: String,
    val lensLines: List<String>,
    val settingLines: List<String>
)

/**
 * Reads the device and the ViewModel's current state into a snapshot.
 * Best-effort throughout: a report must still copy on a device whose camera
 * enumeration failed, so every nullable source degrades to "unknown".
 */
fun collectBugReportSnapshot(context: Context, viewModel: CameraViewModel): BugReportSnapshot {
    val catalog = runCatching { viewModel.lensCatalogResult }.getOrNull()
    val lensLines = catalog?.allLenses?.map { profile ->
        buildString {
            append(profile.role)
            append(": ${profile.equivFocalMm}mm")
            append(", ${profile.nativeMegapixels}MP")
            append(", f/${profile.maxApertureF}")
            if (profile.hasOIS) append(", OIS")
            append(if (profile.hasVariableFocus) ", AF" else ", fixed-focus")
            append(if (profile.supportsRaw) ", RAW" else ", no RAW")
        }
    } ?: listOf("lens catalog unavailable")
    val settings = listOf(
        "look=${viewModel.activeLookId.value}",
        "lens=${viewModel.selectedLensRole.value}" +
            (if (viewModel.isFrontCamera.value) " (front)" else " (rear)") +
            ", zoom=${viewModel.digitalZoomRatio.value}x" +
            ", ~${viewModel.effectiveFocalLength.value}mm",
        "resolution=${viewModel.outputResolution.value}, aspect=${viewModel.aspectRatio.value}",
        "raw=${viewModel.rawModeEnabled.value} " +
            "(supported on this lens: ${viewModel.rawAvailableForCurrentLens.value})",
        "flash=${viewModel.flashMode.value}, timer=${viewModel.selfTimerMode.value}s",
        "grid=${viewModel.showGridLines.value}, frame=${viewModel.showGalleryFrame.value}, " +
            "doubleExposure=${viewModel.doubleExposureActive.value}",
        "extension=${viewModel.activeExtension.value} " +
            "(available: ${viewModel.availableExtensions.value}, probed: ${viewModel.extensionsProbeDone.value})"
    )
    return BugReportSnapshot(
        appVersion = runCatching { AppVersion.display }.getOrDefault("unknown"),
        packageName = context.packageName,
        androidRelease = Build.VERSION.RELEASE ?: "unknown",
        sdkInt = Build.VERSION.SDK_INT,
        manufacturer = Build.MANUFACTURER ?: "unknown",
        model = Build.MODEL ?: "unknown",
        device = Build.DEVICE ?: "unknown",
        lensLines = lensLines,
        settingLines = settings
    )
}

/**
 * Renders the snapshot as Markdown for a GitHub issue body. Deliberately
 * device + settings only: no identifiers, no location, no photo content, so
 * pasting it into a public issue leaks nothing sensitive.
 */
fun formatBugReport(snapshot: BugReportSnapshot): String = buildString {
    appendLine("## Bug report (ZoomBox Camera ${snapshot.appVersion})")
    appendLine()
    appendLine("- App: ${snapshot.appVersion} (${snapshot.packageName})")
    appendLine("- Android: ${snapshot.androidRelease} (SDK ${snapshot.sdkInt})")
    appendLine("- Device: ${snapshot.manufacturer} ${snapshot.model} (${snapshot.device})")
    appendLine("- Lenses:")
    snapshot.lensLines.forEach { appendLine("  - $it") }
    appendLine("- Settings:")
    snapshot.settingLines.forEach { appendLine("  - $it") }
    appendLine("- Contains no location data, identifiers or photo content.")
    appendLine()
    appendLine("Please paste this into a new issue at $ISSUE_TRACKER_URL")
    appendLine("and describe what you expected vs. what happened.")
}

/** Copies [text] to the clipboard; false when the service is unavailable. */
fun copyBugReportToClipboard(context: Context, text: String): Boolean = runCatching {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("zoombox-bug-report", text))
    true
}.getOrDefault(false)
