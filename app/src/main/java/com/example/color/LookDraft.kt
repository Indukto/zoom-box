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
 * @param id the look being edited, or null for a brand-new look (the id is
 *        minted on save so a rename never orphans the selection). Bundled
 *        looks keep their own id: saving overwrites the user override for
 *        that id instead of minting a copy.
 * @param basedOnId the look the draft was seeded from — the same id when
 *        editing in place, null for a look built from scratch.
 * @param basedOnLabel human-readable form of [basedOnId] for the editor's
 *        "based on" chip; null for a look built from scratch.
 * @param initialLook the grading the draft started from — the saved look's
 *        snapshot, or neutral for a blank look. Reset restores exactly this,
 *        so the button never has to re-read the registry (which may have
 *        changed since the draft opened).
 * @param factoryLook the shipped grade for a bundled look, or null for looks
 *        the user authored from scratch. Restoring the original deletes the
 *        override and returns the draft to exactly this.
 * @param factoryName the shipped display name matching [factoryLook].
 */
data class LookDraft(
    val id: String?,
    val displayName: String,
    val look: RetroRenderParams,
    val basedOnId: String? = null,
    val basedOnLabel: String? = null,
    val initialLook: RetroRenderParams = look,
    val factoryLook: RetroRenderParams? = null,
    val factoryName: String? = null
) {
    /** True when nothing exists on disk yet — saving will mint a new id. */
    val isNew: Boolean
        get() = id == null

    /** True when a slider, the LUT row or an import moved the draft off its start. */
    val isDirty: Boolean
        get() = look != initialLook
}
