package com.example.zoom

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.SizeF
import androidx.compose.runtime.Stable

/**
 * Discovers and catalogs the physical camera lenses on the device at runtime.
 *
 * Uses Camera2 characteristics to identify which physical camera ID corresponds
 * to Ultra-wide, Primary, and Telephoto lenses. Never hardcodes camera ID indices
 * as they are not stable across OS versions.
 */
class LensCatalog(private val context: Context) {

    companion object {
        private const val TAG = "LensCatalog"

        // 35mm full-frame sensor diagonal in mm
        private const val FULL_FRAME_DIAGONAL_MM = 43.27f

        // Thresholds for identifying lens roles based on equivalent focal length
        private const val ULTRA_WIDE_THRESHOLD_MM = 20f
        private const val TELE_THRESHOLD_MM = 70f

        /**
         * Minimum image area we'll trust as a real Preview size for
         * [hasUsablePreviewStream]. Some HALs claim a non-empty size table
         * while the only entry is a 1×1 (or otherwise impractically small)
         * placeholder; CameraX still tries to bind those and fails async.
         * 320×240 (QVGA) is comfortably below anything a real preview
         * pipeline would advertise on any Android device.
         */
        private const val MIN_PHANTOM_AREA_PX = 320 * 240
    }

    @Stable
    data class CatalogResult(
        val ultraWide: LensProfile?,
        val primary: LensProfile?,
        val tele: LensProfile?,
        val allLenses: List<LensProfile>
    )

    /**
     * Enumerates all back-facing cameras and classifies them by role.
     * Call this once at app start and cache the result.
     *
     * On logical multi-camera devices (e.g. Pixel 7 Pro) the rear UW / Primary / Tele
     * lenses are hidden behind a single *logical* camera ID. `cameraIdList` only returns
     * that logical ID, so to discover each real lens we must expand it via
     * `getPhysicalCameraIds()` (requires REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
     * API 28+). For each logical back camera we therefore enumerate its physical sub-cameras
     * as distinct lenses. Devices without a logical multi-camera fall back to the single ID.
     */
    fun enumerate(): CatalogResult {
        val profiles = mutableListOf<LensProfile>()

        // Parallel to profiles: SENSOR_INFO_PHYSICAL_SIZE key for detecting
        // virtual in-sensor-zoom crop modes (same sensor, different crop level).
        val profileSensorKeys = mutableListOf<String?>()

        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

                // Only consider back-facing cameras
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                // Is this a logical multi-camera? If so, enumerate each physical lens.
                // Otherwise treat the camera itself as a single lens.
                val isLogicalMulti = isLogicalMultiCamera(characteristics)
                val physicalIds = if (isLogicalMulti) {
                    getPhysicalCameraIds(characteristics)
                } else {
                    listOf(cameraId)
                }

                for (physicalId in physicalIds) {
                    // Physical sub-cameras expose their own per-lens characteristics;
                    // fall back to the logical camera's if the lookup fails.
                    val lensChars = try {
                        cameraManager.getCameraCharacteristics(physicalId)
                    } catch (e: Exception) {
                        characteristics
                    }

                    val focalLengths = lensChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val physicalSize = lensChars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

                    if (focalLengths == null || focalLengths.isEmpty()) continue

                    // Drop phantom physical IDs. Hardware emulators (Pixel 5 AVD,
                    // older SDK profiles) sometimes expose a logical multi-camera
                    // whose secondary physical sub-cams have no Preview / Video /
                    // YUV_420_888 streams at all. Forcing setPhysicalCameraId() on
                    // one of those via Camera2-Interop causes CameraX to fire
                    // onConfigureFailed asynchronously and we end up with a black
                    // feed we can't recover from inside the bind call.
                    //
                    // On Xiaomi/MediaTek devices the physical sub-cameras do NOT
                    // individually advertise YUV_420_888 stream configurations —
                    // those streams only exist on the logical camera. CameraX
                    // uses the logical camera's stream configs for session
                    // negotiation regardless of setPhysicalCameraId(), so a
                    // missing YUV table on the physical camera is NOT a sign
                    // of a phantom lens on MTK. We therefore check BOTH the
                    // physical AND the logical camera: skip only when NEITHER
                    // has a usable preview stream.
                    val physicalHasYuv = hasUsablePreviewStream(lensChars)
                    // Only consider the logical camera's YUV streams when
                    // this is a genuine sub-camera within a logical
                    // multi-camera setup (MTK-style). On single-ID devices
                    // (physicalId == cameraId) or non-logical-multi-cam,
                    // the physical camera's own stream config is authoritative.
                    val isSubCamera = isLogicalMulti && physicalId != cameraId
                    val logicalFallback = isSubCamera && hasUsablePreviewStream(characteristics)
                    if (!physicalHasYuv && !logicalFallback) {
                        Log.w(
                            TAG,
                            "Skipping physical camera $physicalId: no YUV_420_888 " +
                                "preview stream on physical or logical (phantom sub-camera)"
                        )
                        Log.i("LensSwitch", "SKIP phantom | physical=$physicalId logical=$cameraId (no YUV streams)")
                        continue
                    }
                    if (!physicalHasYuv) {
                        Log.i("LensSwitch", "KEEP mtk-style | physical=$physicalId logical=$cameraId (YUV only on logical)")
                    }

                    // Use the widest (smallest) focal length as the lens's native focal length
                    val nativeFocalMm = focalLengths[0]
                    val cropFactor = computeCropFactor(physicalSize)
                    val equivFocalMm = nativeFocalMm * cropFactor

                    // Estimate megapixels from sensor size
                    val megapixels = estimateMegapixels(lensChars)

                    val hasOIS = hasOpticalStabilization(lensChars)
                    val maxAperture = estimateMaxAperture(lensChars)
                    val supportsRaw = supportsRawCapture(lensChars)

                    val role = classifyLens(equivFocalMm)

                    profiles.add(
                        LensProfile(
                            role = role,
                            logicalCameraId = cameraId,
                            physicalCameraId = physicalId,
                            equivFocalMm = equivFocalMm,
                            nativeMegapixels = megapixels,
                            maxApertureF = maxAperture,
                            hasOIS = hasOIS,
                            supportsRaw = supportsRaw
                        )
                    )
                    profileSensorKeys.add(
                        // Non-null physical sizes are used to detect duplicate
                        // sensors; for null sizes we fall back to the physical
                        // camera ID so profiles are never falsely merged.
                        physicalSize?.let { "${it.width}_${it.height}" } ?: physicalId
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating cameras", e)
        }

        // ── Deduplicate in-sensor crop modes ──────────────────────────────
        // Xiaomi/MediaTek HALs commonly expose a "virtual" physical camera
        // within a logical multi-camera that represents an in-sensor zoom
        // crop mode of the primary sensor — not a separate physical lens.
        // These share the same SENSOR_INFO_PHYSICAL_SIZE but report a longer
        // equivalent focal length. Detecting them by grouping (logical camera
        // + physical sensor size) and keeping only the widest focal length
        // eliminates phantom lenses that show a "116mm" label but route to
        // the same sensor as the primary lens.
        if (profileSensorKeys.size == profiles.size) {
            val toRemove = mutableSetOf<Int>()
            val seenGroup = mutableMapOf<String, Int>()
            for (i in profiles.indices) {
                val logicalId = profiles[i].logicalCameraId
                val sensorKey = profileSensorKeys[i] ?: continue
                val groupKey = "$logicalId|$sensorKey"
                val existing = seenGroup[groupKey]
                if (existing != null) {
                    if (profiles[i].equivFocalMm < profiles[existing].equivFocalMm) {
                        toRemove.add(existing)
                        seenGroup[groupKey] = i
                        Log.w(TAG, "Dedup sensor=$sensorKey logical=$logicalId — " +
                            "keep=${profiles[i].equivFocalMm}mm(physical=${profiles[i].physicalCameraId}) " +
                            "drop=${profiles[existing].equivFocalMm}mm(physical=${profiles[existing].physicalCameraId})")
                    } else {
                        toRemove.add(i)
                        Log.w(TAG, "Dedup sensor=$sensorKey logical=$logicalId — " +
                            "keep=${profiles[existing].equivFocalMm}mm(physical=${profiles[existing].physicalCameraId}) " +
                            "drop=${profiles[i].equivFocalMm}mm(physical=${profiles[i].physicalCameraId})")
                    }
                } else {
                    seenGroup[groupKey] = i
                }
            }
            if (toRemove.isNotEmpty()) {
                val deduped = profiles.filterIndexed { i, _ -> i !in toRemove }
                Log.i("LensSwitch", "Dedup removed ${toRemove.size} in-sensor-zoom crop(s), " +
                    "${deduped.size} profile(s) remain")
                profiles.clear()
                profiles.addAll(deduped)
            }
        }

        // Sort by focal length ascending (UW first, Tele last)
        profiles.sortBy { it.equivFocalMm }

        val ultraWide = profiles.find { it.role == LensRole.ULTRA_WIDE }
        val primary = profiles.find { it.role == LensRole.PRIMARY }
        val tele = profiles.find { it.role == LensRole.TELE }

        if (profiles.isEmpty()) {
            // Defensive: enumerating produced no usable back lens. The caller
            // (CameraPreviewView) falls back to CameraSelector.DEFAULT_BACK_CAMERA,
            // so preview/capture still work, but the bubble's focal-length label
            // will stay empty. Surface a warning so this scenario is diagnosable
            // from logcat rather than appearing as a silent regression.
            Log.w(TAG, "enumerate(): no back-facing lenses with usable preview stream")
        }

        Log.i("LensSwitch", "Catalog done | total=${profiles.size} UW=${ultraWide != null} PRI=${primary != null} TELE=${tele != null}")

        return CatalogResult(
            ultraWide = ultraWide,
            primary = primary,
            tele = tele,
            allLenses = profiles
        )
    }

    /**
     * Returns true if this camera is a logical multi-camera (i.e. a group of
     * physical lenses exposed under a single logical ID). API 28+.
     */
    private fun isLogicalMultiCamera(characteristics: CameraCharacteristics): Boolean {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        return capabilities.any {
            it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
        }
    }

    /**
     * Returns the physical camera IDs backing a logical multi-camera.
     * `getPhysicalCameraIds()` is public since API 28; callers must gate on
     * [isLogicalMultiCamera] (which is only true on API 28+).
     */
    private fun getPhysicalCameraIds(characteristics: CameraCharacteristics): Set<String> {
        return try {
            characteristics.physicalCameraIds
        } catch (e: Exception) {
            Log.e(TAG, "getPhysicalCameraIds not available", e)
            emptySet()
        }
    }

    /**
     * Computes the 35mm crop factor from the sensor's physical dimensions.
     */
    private fun computeCropFactor(physicalSize: SizeF?): Float {
        if (physicalSize == null) return 1.0f
        val sensorDiagonal = kotlin.math.sqrt(
            physicalSize.width * physicalSize.width + physicalSize.height * physicalSize.height
        )
        return FULL_FRAME_DIAGONAL_MM / sensorDiagonal
    }

    /**
     * Estimates megapixels from the sensor's active array size.
     */
    private fun estimateMegapixels(characteristics: CameraCharacteristics): Int {
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (sensorSize != null) {
            val mp = (sensorSize.width().toLong() * sensorSize.height().toLong()) / 1_000_000
            return mp.toInt().coerceAtLeast(1)
        }
        return 12 // fallback
    }

    /**
     * Checks if the lens has optical image stabilization.
     */
    private fun hasOpticalStabilization(characteristics: CameraCharacteristics): Boolean {
        val availableOisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        return availableOisModes?.any { it != CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF } == true
    }

    /**
     * Estimates the maximum aperture from available aperture values.
     */
    private fun estimateMaxAperture(characteristics: CameraCharacteristics): Float {
        val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        return apertures?.minOrNull() ?: 2.0f
    }

    /**
     * True when the sensor's stream config map advertises at least one
     * `YUV_420_888` output size — i.e. the camera can actually deliver a
     * Preview surface. A property of the physical sub-camera itself; we
     * deliberately probe this *before* classifying/adding a profile so we
     * never hand `PreviewSessionManager.setPhysicalCameraId(...)` an ID
     * whose sensor can't stream.
     *
     * Backing rationale: Pixel-style AVDs frequently expose logical
     * multi-camera configs with a "real" primary physical ID and one or more
     * phantom placeholder IDs that Camera2 returns characteristics for but
     * never publishes streams for. Binding to such an ID asynchronously
     * triggers `Camera2CameraImpl.onConfigureFailed` and CameraX reports it
     * as `IllegalStateException: onConfigureFailed`, which we cannot catch
     * because it is raised on CameraX's internal SequencerExecutor. The only
     * safe defense is to filter phantom IDs *before* the bind attempt.
     *
     * We additionally require the advertised size to clear
     * [MIN_PHANTOM_AREA_PX]; some HALs claim a non-empty size table while
     * the only entry is a 1×1 (or otherwise impractically small) placeholder,
     * which CameraX still tries to bind and fails on async.
     */
    private fun hasUsablePreviewStream(characteristics: CameraCharacteristics): Boolean {
        return try {
            val configMap = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: return false
            val yuvSizes = configMap.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                ?: return false
            yuvSizes.any { it.width * it.height >= MIN_PHANTOM_AREA_PX }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reports whether this sensor can capture RAW bayer data.
     *
     * Requires both the `REQUEST_AVAILABLE_CAPABILITIES_RAW` capability and a
     * non-empty set of `RAW_SENSOR` output sizes on the stream config map.
     * Front cameras and many ultra-wide/tele sensors do NOT advertise this,
     * so the result drives whether the app offers a "RAW" toggle for the lens.
     */
    private fun supportsRawCapture(characteristics: CameraCharacteristics): Boolean {
        return try {
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: return false
            val hasRawCapability = capabilities.any {
                it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
            }
            if (!hasRawCapability) return false
            val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return false
            val rawSizes = configMap.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
            !rawSizes.isNullOrEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "supportsRawCapture probe failed", e)
            false
        }
    }

    /**
     * Classifies a lens by its 35mm-equivalent focal length.
     */
    private fun classifyLens(equivFocalMm: Float): LensRole {
        return when {
            equivFocalMm < ULTRA_WIDE_THRESHOLD_MM -> LensRole.ULTRA_WIDE
            equivFocalMm > TELE_THRESHOLD_MM -> LensRole.TELE
            else -> LensRole.PRIMARY
        }
    }
}