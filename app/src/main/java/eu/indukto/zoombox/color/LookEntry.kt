package eu.indukto.zoombox.color

import eu.indukto.zoombox.FilmPreset

/**
 * One selectable entry in the Film-Style picker.
 *
 * A look is identified by a stable string [id] (the `id` declared in
 * `assets/cameras/<id>.json`, or the enum's [FilmPreset.profileId] for
 * enum-backed looks). The picker, the live viewfinder and the capture
 * pipeline all key off that id, so a JSON-only look — one with no
 * [FilmPreset] entry — is selectable and renderable exactly like an
 * enum-backed one.
 *
 * [preset] is non-null only when the look also exists in the [FilmPreset]
 * enum; it is kept so the enum-shaped UI surfaces (tooltips, the preview
 * route switch) can still ask "is this the pass-through Normal preset?"
 * without the enum having to be the source of truth for the catalog.
 *
 * @param isUser true for a look the *user* authored from scratch (a file in
 *        `filesDir/looks` with an id of its own) rather than one that ships
 *        bundled. The manager screen groups on it, and pure user looks are
 *        the only entries the editor offers to delete — a bundled look with
 *        user edits keeps its slot and offers a restore-to-original instead.
 * @param hasUserOverride true when a bundled look carries user-saved edits
 *        shadowing its shipped grade. The entry keeps its bundled slot, order
 *        and preset; only its grade (and display name, if renamed) comes from
 *        the user's file.
 */
data class LookEntry(
    val id: String,
    val displayName: String,
    val category: String,
    val preset: FilmPreset?,
    val profile: CameraProfile,
    val isUser: Boolean = false,
    val hasUserOverride: Boolean = false
) {
    /**
     * True when the look grades nothing: no LUT to sample and no render
     * stage switched on. Those looks stay on the plain CameraX
     * `PreviewView` route and skip `applyRetroFilter` entirely — see
     * `CameraPreviewView`'s `useFilteredPreview` and the capture pipeline's
     * `currentLut != null || renderParams.needsProcessing` guard.
     */
    val isPassThrough: Boolean
        get() = profile.look.isPassThrough
}
