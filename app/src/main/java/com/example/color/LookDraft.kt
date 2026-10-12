package com.example.color

/**
 * The look currently being edited in the look editor.
 *
 * A draft is the *only* thing the editor writes to: the viewfinder previews
 * [look] live, and [CameraViewModel][com.example.CameraViewModel.saveLookDraft]
 * turns the draft into a `filesDir/looks/<id>.json` file on save. Keeping the
 * in-progress values out of the catalog means a cancelled edit can never leak
 * into the picker or the capture pipeline.
 *
 * @param id the existing user look being edited, or null for a brand-new look
 *        (the id is minted on save so a rename never orphans the selection).
 * @param basedOnId the look the draft was seeded from — a bundled look when
 *        duplicating, or the same id when editing one's own look.
 * @param basedOnLabel human-readable form of [basedOnId] for the editor's
 *        "based on" chip; null for a look built from scratch.
 */
data class LookDraft(
    val id: String?,
    val displayName: String,
    val look: RetroRenderParams,
    val basedOnId: String? = null,
    val basedOnLabel: String? = null
) {
    /** True when nothing exists on disk yet — saving will mint a new id. */
    val isNew: Boolean
        get() = id == null
}
