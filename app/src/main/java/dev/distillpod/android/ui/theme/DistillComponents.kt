package dev.distillpod.android.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SelectableChipElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.FilterChip as M3FilterChip
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.material3.TextButton as M3TextButton

/*
 * Ember component defaults.
 *
 * Material 3 1.4 changed several component defaults (outlined buttons became neutral grey, unselected
 * filter chips and outlined buttons use the dimmer outlineVariant border, disabled labels use
 * onSurfaceVariant, and the selected navigation label uses secondary). The approved Ember design was
 * specified and reviewed against the earlier defaults, so these same-named wrappers pin them. UI files
 * import them explicitly in place of the Material versions; `EmberComponentDefaultsTest` keeps that so.
 * Every parameter keeps the Material signature, so a call site can still override any default.
 */
internal object DistillComponentDefaults {
    private const val DisabledContainerAlpha = 0.12f
    private const val DisabledContentAlpha = 0.38f

    @Composable fun buttonColors(): ButtonColors = MaterialTheme.colorScheme.let {
        ButtonDefaults.buttonColors(disabledContainerColor = it.onSurface.copy(alpha = DisabledContainerAlpha),
            disabledContentColor = it.onSurface.copy(alpha = DisabledContentAlpha))
    }

    /** Amber label and the brighter `outline` border. */
    @Composable fun outlinedButtonColors(): ButtonColors = MaterialTheme.colorScheme.let {
        ButtonDefaults.outlinedButtonColors(contentColor = it.primary,
            disabledContentColor = it.onSurface.copy(alpha = DisabledContentAlpha))
    }

    @Composable fun outlinedButtonBorder(enabled: Boolean): BorderStroke = MaterialTheme.colorScheme.outline.let {
        BorderStroke(1.dp, if (enabled) it else it.copy(alpha = DisabledContainerAlpha))
    }

    @Composable fun textButtonColors(): ButtonColors =
        ButtonDefaults.textButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContentAlpha))

    @Composable fun filterChipBorder(enabled: Boolean, selected: Boolean): BorderStroke =
        FilterChipDefaults.filterChipBorder(enabled, selected, borderColor = MaterialTheme.colorScheme.outline)

    /** The selected tab label stays onSurface (Material 3 1.4 tints it with secondary). */
    @Composable fun navigationBarItemColors(): NavigationBarItemColors =
        NavigationBarItemDefaults.colors(selectedTextColor = MaterialTheme.colorScheme.onSurface)
}

@Composable internal fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = DistillComponentDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = M3Button(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable internal fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = DistillComponentDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = DistillComponentDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = M3OutlinedButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable internal fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = DistillComponentDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = M3TextButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable internal fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = FilterChipDefaults.shape,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
    elevation: SelectableChipElevation? = FilterChipDefaults.filterChipElevation(),
    border: BorderStroke? = DistillComponentDefaults.filterChipBorder(enabled, selected),
    interactionSource: MutableInteractionSource? = null,
) = M3FilterChip(selected, onClick, label, modifier, enabled, leadingIcon, trailingIcon, shape, colors, elevation, border, interactionSource)
