package com.kavach.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.kavach.app.R
import com.kavach.domain.ModelCatalog
import com.kavach.domain.ModelSpec
import com.kavach.domain.ModelState

/**
 * Staging the optional Tier-2 model.
 *
 * The two-step shape of this screen *is* the privacy architecture, not a
 * workaround for it: Kavach holds no INTERNET permission, so it cannot fetch
 * anything. The browser downloads, and the user hands the finished file over
 * through the system picker — which also means no storage permission, just
 * access to exactly one file, once. The screen says so in as many words,
 * because a user who understands why it is awkward will trust the claim that
 * makes it awkward.
 */
@Composable
fun ModelSetupScreen(
    spec: ModelSpec,
    state: ModelState,
    freeSpaceBytes: Long,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(KavachTokens.Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = KavachTokens.Gutter)
            .padding(top = 22.dp, bottom = 22.dp),
    ) {
        Row(
            Modifier
                .clickable(role = Role.Button, onClick = onBack)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhosphorGlyph(Ph.arrowLeft, 20.dp, KavachTokens.InkSoft)
            GapW(10.dp)
            Text(
                stringResource(R.string.model_setup_heading),
                style = MaterialTheme.typography.bodyMedium,
                color = KavachTokens.InkSoft,
            )
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Gap(16.dp)
            Text(
                stringResource(R.string.model_title),
                style = MaterialTheme.typography.headlineMedium,
                color = KavachTokens.Ink,
                modifier = Modifier.semantics { heading() },
            )
            Gap(8.dp)
            Text(
                stringResource(R.string.model_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = KavachTokens.InkSoft,
            )

            Gap(16.dp)
            ModelCard(spec, state, freeSpaceBytes)

            when (state) {
                is ModelState.Importing -> {
                    Gap(18.dp)
                    ImportProgress(state)
                }

                is ModelState.Ready -> {
                    Gap(18.dp)
                    PillButton(
                        label = stringResource(R.string.model_action_delete),
                        onClick = onDelete,
                        filled = false,
                        content = KavachTokens.Ink,
                        height = 46.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                else -> {
                    Gap(18.dp)
                    Step(
                        number = 1,
                        text = stringResource(R.string.model_step_download),
                        actionLabel = stringResource(R.string.model_action_download),
                        actionIcon = Ph.arrowSquareOut,
                        onAction = onDownload,
                        filled = true,
                    )
                    Gap(12.dp)
                    Step(
                        number = 2,
                        text = stringResource(R.string.model_step_import),
                        actionLabel = stringResource(R.string.model_action_import),
                        actionIcon = Ph.folderOpen,
                        onAction = onImport,
                        filled = false,
                    )
                }
            }

            Gap(20.dp)
        }

        Rule(KavachTokens.Ink.copy(alpha = 0.14f))
        Gap(16.dp)
        Text(
            stringResource(R.string.model_privacy_note),
            style = MaterialTheme.typography.labelMedium,
            color = KavachTokens.InkSoft,
        )
    }
}

/** The model, its provenance, and what it will cost this phone. */
@Composable
private fun ModelCard(
    spec: ModelSpec,
    state: ModelState,
    freeSpaceBytes: Long,
) {
    val (statusRes, statusColor) =
        when (state) {
            is ModelState.Ready -> R.string.model_status_ready to KavachTokens.CyanDeep
            is ModelState.Invalid -> R.string.model_status_invalid to KavachTokens.PressRed
            else -> R.string.model_status_absent to KavachTokens.CyanDeep
        }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KavachTokens.RadiusCard))
            .background(KavachTokens.Card)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                spec.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = KavachTokens.Ink,
                modifier = Modifier.weight(1f),
            )
            GapW(10.dp)
            Text(
                stringResource(statusRes),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.06.em,
                color = statusColor,
                modifier =
                    Modifier
                        .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(KavachTokens.RadiusTag))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }

        Gap(10.dp)
        Detail(stringResource(R.string.model_detail_size), ModelCatalog.formatBytes(spec.sizeBytes))
        Detail(stringResource(R.string.model_detail_backend), spec.notes)
        Detail(stringResource(R.string.model_detail_licence), spec.licence)
        Detail(stringResource(R.string.model_detail_source), ModelCatalog.PROVIDER)
        Detail(stringResource(R.string.model_detail_free), ModelCatalog.formatBytes(freeSpaceBytes))

        if (state is ModelState.Invalid) {
            Gap(12.dp)
            Text(
                state.reason,
                style = MaterialTheme.typography.bodySmall,
                color = KavachTokens.PressRed,
            )
        }
    }
}

/** One label/value row, ruled off from the one above it. */
@Composable
private fun Detail(
    label: String,
    value: String,
) {
    Rule(KavachTokens.Ink.copy(alpha = 0.1f))
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = KavachTokens.InkMuted)
        GapW(16.dp)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = KavachTokens.Ink,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** A numbered step and the one control that completes it. */
@Composable
private fun Step(
    number: Int,
    text: String,
    actionLabel: String,
    actionIcon: PhosphorIcon,
    onAction: () -> Unit,
    filled: Boolean,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "%02d".format(number),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = KavachTokens.CyanDeep,
            modifier = Modifier.padding(top = 3.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Normal,
                color = KavachTokens.Ink,
            )
            Gap(10.dp)
            PillButton(
                label = actionLabel,
                onClick = onAction,
                icon = actionIcon,
                filled = filled,
                container = KavachTokens.Cyan,
                content = if (filled) KavachTokens.Card else KavachTokens.Ink,
                height = 46.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Copy progress. Drawn as a plain rule filling up rather than a spinner: this
 * takes minutes, and a person watching three gigabytes move deserves to see
 * how far along it actually is.
 */
@Composable
private fun ImportProgress(state: ModelState.Importing) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(
                R.string.model_importing,
                ModelCatalog.formatBytes(state.copiedBytes),
                ModelCatalog.formatBytes(state.totalBytes),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = KavachTokens.Ink,
        )
        Gap(10.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(KavachTokens.Cyan.copy(alpha = 0.22f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(state.fraction)
                    .height(5.dp)
                    .background(KavachTokens.Cyan),
            )
        }
    }
}
