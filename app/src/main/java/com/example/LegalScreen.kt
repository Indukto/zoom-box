package com.example

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.FilmDarkColorScheme

/**
 * Legal & compliance pages — Privacy (GDPR), Imprint (§ 5 DDG) and
 * open-source licenses — reachable from Settings → Legal.
 *
 * Pages render as full-screen overlays in the same film-chrome dark palette
 * as [SettingsScreen], so they read as part of the camera identity rather
 * than a system dialog. Body copy comes entirely from localized string
 * resources (values/ + values-de/) so the legal disclosure itself satisfies
 * the GDPR transparency requirement in the user's language.
 *
 * Each page is a [LazyColumn] of section header + paragraph pairs; the
 * [Section] model below is plain data so adding a clause never touches the
 * layout code.
 */
enum class LegalPage { Privacy, Imprint, Licenses }

@Composable
fun LegalScreen(page: LegalPage, onClose: () -> Unit) {
    BackHandler(onBack = onClose)

    MaterialTheme(
        colorScheme = FilmDarkColorScheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography
    ) {
        LegalContent(page = page, onClose = onClose)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LegalContent(page: LegalPage, onClose: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    val titleRes = when (page) {
        LegalPage.Privacy -> R.string.privacy_title
        LegalPage.Imprint -> R.string.imprint_title
        LegalPage.Licenses -> R.string.licenses_title
    }
    val sections = when (page) {
        LegalPage.Privacy -> listOf(
            Section(R.string.privacy_h_about, R.string.privacy_p_about),
            Section(R.string.privacy_h_photos, R.string.privacy_p_photos),
            Section(R.string.privacy_h_permissions, R.string.privacy_p_permissions),
            Section(R.string.privacy_h_backup, R.string.privacy_p_backup),
            Section(R.string.privacy_h_nodata, R.string.privacy_p_nodata),
            Section(R.string.privacy_h_children, R.string.privacy_p_children),
            Section(R.string.privacy_h_rights, R.string.privacy_p_rights)
        )
        LegalPage.Imprint -> listOf(
            Section(R.string.imprint_h_provider, R.string.imprint_p_provider),
            Section(R.string.imprint_h_contact, R.string.imprint_p_contact),
            Section(R.string.imprint_h_mstv, R.string.imprint_p_mstv),
            Section(R.string.imprint_h_dispute, R.string.imprint_p_dispute)
        )
        LegalPage.Licenses -> listOf(
            Section(R.string.licenses_h_app, R.string.licenses_p_app),
            Section(R.string.licenses_h_libs, R.string.licenses_p_libs),
            Section(R.string.licenses_h_texts, R.string.licenses_p_texts)
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top bar — mirrors the Settings header chrome ────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colorScheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .displayCutoutPadding()
                        .statusBarsPadding()
                        .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClose()
                        },
                        modifier = Modifier.testTag("legal_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.legal_back_desc),
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(titleRes),
                        color = colorScheme.onSurface,
                        style = typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // ── Body: one card-like column of section headers + prose ───
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                sections.forEachIndexed { index, section ->
                    item(key = "${page.name}_$index") {
                        SectionBlock(section = section)
                    }
                }
            }
        }
    }
}

/** One legal section: small primary-colored header + body paragraph. */
private data class Section(val headerRes: Int, val bodyRes: Int)

@Composable
private fun SectionBlock(section: Section) {
    val colorScheme = MaterialTheme.colorScheme
    Text(
        text = stringResource(section.headerRes),
        style = MaterialTheme.typography.titleSmall,
        color = colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)
    )
    Text(
        text = stringResource(section.bodyRes),
        style = MaterialTheme.typography.bodyMedium,
        color = colorScheme.onSurface
    )
}
