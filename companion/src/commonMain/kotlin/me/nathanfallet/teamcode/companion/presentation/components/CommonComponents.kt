package me.nathanfallet.teamcode.companion.presentation.components

import me.nathanfallet.teamcode.companion.presentation.viewmodels.Failure
import me.nathanfallet.teamcode.companion.presentation.viewmodels.words
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretLeft
import com.adamglin.phosphoricons.regular.Warning
import com.adamglin.phosphoricons.regular.WarningCircle
import me.nathanfallet.teamcode.companion.presentation.theme.ChipStyle
import me.nathanfallet.teamcode.companion.presentation.theme.Radii
import me.nathanfallet.teamcode.companion.presentation.theme.SectionStyle
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCode

/** Content columns stay readable on a wide window: centered, at most this wide. */
val ContentWidth = 720.dp

// ---- Buttons -------------------------------------------------------------------------------------------------------

enum class ButtonKind { PRIMARY, SECONDARY, GHOST, DANGER }

/** A text button, 48 dp high (44 dp when [small]); [fill] makes it take the row's width. */
@Composable
fun TcButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    small: Boolean = false,
    fill: Boolean = !small,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    busy: Boolean = false,
) {
    val colors = TeamCode.colors
    val (base, contentBase, borderBase) = when (kind) {
        ButtonKind.PRIMARY -> Triple(colors.accent, colors.onAccent, Color.Transparent)
        ButtonKind.SECONDARY -> Triple(colors.panel, colors.text, colors.line)
        ButtonKind.GHOST -> Triple(Color.Transparent, colors.accentText, Color.Transparent)
        ButtonKind.DANGER -> Triple(Color.Transparent, colors.danger, colors.line)
    }
    // Disabled dims the colors themselves rather than through a graphics layer: a layer toggled between 0.45 and 1
    // left a primary button without its fill on the first enable, in dark mode.
    fun Color.dimmed() = if (enabled) this else copy(alpha = alpha * 0.45f)
    val background = base.dimmed()
    val content = contentBase.dimmed()
    val border = borderBase.dimmed()
    // A mouse needs no 44 dp target: the desktop's buttons are as dense as a native app's.
    val dense = LocalDense.current
    Row(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = when { dense && small -> 30.dp; dense -> 36.dp; small -> 44.dp; else -> 48.dp })
            .clip(Radii.control)
            .background(background)
            .then(if (enabled && !busy) Modifier.hoverHighlight(Radii.control, if (kind == ButtonKind.PRIMARY) colors.onAccent.copy(alpha = 0.10f) else null).handCursor() else Modifier)
            .border(1.dp, border, Radii.control)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small || dense) 12.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = content, strokeWidth = 2.dp)
        else if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(if (small) 15.dp else 18.dp))
        Text(text, color = content, fontSize = when { dense && small -> 12.5.sp; small || dense -> 13.5.sp; else -> 15.sp }, fontWeight = if (kind == ButtonKind.GHOST && small) FontWeight.Medium else FontWeight.SemiBold, maxLines = 1)
    }
}

enum class IconButtonKind { PLAIN, FILLED, OUTLINED }

/** A 44 dp icon button; [FILLED][IconButtonKind.FILLED] is the accent disc of the main action. */
@Composable
fun TcIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: IconButtonKind = IconButtonKind.PLAIN,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val colors = TeamCode.colors
    val shape = if (kind == IconButtonKind.FILLED) CircleShape else RoundedCornerShape(if (LocalDense.current) 8.dp else 12.dp)
    fun Color.dimmed() = if (enabled) this else copy(alpha = alpha * 0.45f)
    val dense = LocalDense.current
    val button = @Composable {
        Box(
            modifier
                .size(if (dense) 32.dp else 44.dp)
                .clip(shape)
                .background(when (kind) { IconButtonKind.FILLED -> colors.accent; IconButtonKind.OUTLINED -> colors.panel; IconButtonKind.PLAIN -> Color.Transparent }.dimmed())
                .then(if (enabled) Modifier.hoverHighlight(shape, if (kind == IconButtonKind.FILLED) colors.onAccent.copy(alpha = 0.10f) else null).handCursor() else Modifier)
                .border(1.dp, (if (kind == IconButtonKind.OUTLINED) colors.line else Color.Transparent).dimmed(), shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = (tint ?: if (kind == IconButtonKind.FILLED) colors.onAccent else colors.text).dimmed(), modifier = Modifier.size(if (dense) 18.dp else 20.dp))
        }
    }
    // With a mouse, an icon says what it does when the pointer rests on it.
    if (dense) Tooltip(contentDescription) { button() } else button()
}

/** Material's plain tooltip over [content], shown on hover (and on a long press on touch screens). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Tooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        content = content,
    )
}

/** A text link button (Retry, Discard...) in a status line. */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = TeamCode.colors.accentText) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(Radii.control).handCursor().clickable(role = Role.Button, onClick = onClick).heightIn(min = 36.dp).padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

// ---- Segmented control ---------------------------------------------------------------------------------------------

/** Mutually exclusive filters in one pill: Active / Paused / Ended, Mine / Team, System / Light / Dark. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, tag: (T) -> String? = { null }) {
    val colors = TeamCode.colors
    Row(
        modifier.clip(Radii.segment).background(colors.panelAlt).border(1.dp, colors.line, Radii.segment).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((value, label) in options) {
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = if (LocalDense.current) 30.dp else 38.dp)
                    .clip(Radii.segmentItem)
                    .background(if (on) colors.panel else Color.Transparent)
                    .border(1.dp, if (on) colors.line else Color.Transparent, Radii.segmentItem)
                    .then(if (on) Modifier else Modifier.hoverHighlight(Radii.segmentItem).handCursor())
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(value) })
                    .then(tag(value)?.let { Modifier.testTag(it) } ?: Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) colors.text else colors.muted, fontSize = if (LocalDense.current) 12.5.sp else 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

// ---- Surfaces ------------------------------------------------------------------------------------------------------

/** A panel with a hairline border, 14 dp corners. */
@Composable
fun TcCard(
    modifier: Modifier = Modifier,
    borderColor: Color = TeamCode.colors.line,
    onClick: (() -> Unit)? = null,
    padding: Dp = 14.dp,
    spacing: Dp = 7.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TeamCode.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(Radii.card)
            .background(colors.panel)
            // The hover tint goes under the border, which stays visible.
            .then(if (onClick != null) Modifier.hoverHighlight(Radii.card) else Modifier)
            .border(1.dp, borderColor, Radii.card)
            .then(if (onClick != null) Modifier.focusRing(Radii.card).handCursor().clickable(onClick = onClick) else Modifier)
            .padding(horizontal = padding, vertical = padding - 1.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** A grouped list: rows separated by hairlines, as in settings. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = TeamCode.colors
    Column(modifier.fillMaxWidth().clip(Radii.card).background(colors.panel).border(1.dp, colors.line, Radii.card), content = content)
}

@Composable
fun GroupDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(TeamCode.colors.line))
}

/** One row of a [Group], at least 48 dp. */
@Composable
fun GroupRow(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (LocalDense.current) 40.dp else 48.dp)
            .then(if (onClick != null) Modifier.hoverHighlight(RoundedCornerShape(0.dp)).handCursor().clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

enum class Tone { NEUTRAL, ACCENT, MUTED, WARN, DANGER }

/** A monospace pill: a state, a role, an access level. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.NEUTRAL, dot: Boolean = false) {
    val colors = TeamCode.colors
    val (background, content, border) = when (tone) {
        Tone.NEUTRAL, Tone.ACCENT -> Triple(colors.chip, colors.chipText, colors.line)
        Tone.MUTED -> Triple(Color.Transparent, colors.muted, colors.line)
        Tone.WARN -> Triple(colors.warnBackground, colors.warn, Color.Transparent)
        Tone.DANGER -> Triple(colors.dangerBackground, colors.danger, Color.Transparent)
    }
    Row(
        modifier.clip(Radii.pill).background(background).border(1.dp, border, Radii.pill).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dot) Dot(if (tone == Tone.ACCENT) colors.accentText else content)
        Text(text, style = ChipStyle, color = content, maxLines = 1)
    }
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** A section title: small spaced capitals. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = SectionStyle, color = TeamCode.colors.muted, modifier = modifier.padding(horizontal = 4.dp).semantics { heading() })
}

/** A tinted banner: a warning, a failure, with an optional action at its end. */
@Composable
fun Banner(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = if (tone == Tone.DANGER) PhosphorIcons.Regular.WarningCircle else PhosphorIcons.Regular.Warning,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = TeamCode.colors
    val (background, content, border) = when (tone) {
        Tone.WARN -> Triple(colors.warnBackground, colors.warn, Color.Transparent)
        Tone.DANGER -> Triple(colors.dangerBackground, colors.danger, Color.Transparent)
        Tone.ACCENT -> Triple(colors.panel, colors.text, colors.accentDim)
        else -> Triple(colors.panel, colors.muted, colors.line)
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(background).border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = if (action != null) 4.dp else 12.dp, top = if (action != null) 4.dp else 10.dp, bottom = if (action != null) 4.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(text, color = content, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f).padding(vertical = if (action != null) 6.dp else 0.dp))
        action?.invoke(this)
    }
}

/** An empty or blocking state: a dashed outline (solid when [dashed] is false), an icon, a title, a line, an action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    dashed: Boolean = true,
    iconTint: Color = TeamCode.colors.chipText,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = TeamCode.colors
    Column(
        modifier
            .fillMaxWidth()
            .then(if (dashed) Modifier.dashedBorder(colors.line, 14.dp) else Modifier.clip(Radii.card).background(colors.panel).border(1.dp, colors.line, Radii.card))
            .padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconTile(icon, tint = iconTint)
        Text(title, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(body, color = colors.muted, fontSize = 13.5.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(2.dp))
            action()
        }
    }
}

fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
    )
}

/** A square tile holding an icon (36 dp): server checks, account cards, artifact cards. */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = TeamCode.colors.chipText, size: Dp = 38.dp) {
    val colors = TeamCode.colors
    Box(modifier.size(size).clip(Radii.control).background(colors.chip).border(1.dp, colors.line, Radii.control), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** An account's initial in a tile. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 36.dp, dimmed: Boolean = false) {
    val colors = TeamCode.colors
    Box(modifier.size(size).clip(Radii.control).background(colors.chip).border(1.dp, colors.line, Radii.control).alpha(if (dimmed) 0.6f else 1f), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = colors.chipText, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

/** TeamCode's mark: a rounded square with the accent at its center. */
@Composable
fun BrandGlyph(modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val colors = TeamCode.colors
    val shape = RoundedCornerShape(size * 0.32f)
    Box(
        modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(colors.chip, colors.panel))).border(1.5.dp, colors.accentDim, shape),
        contentAlignment = Alignment.Center,
    ) {
        Dot(colors.accent, size = size * 0.28f)
    }
}

/** A loading placeholder bar. */
@Composable
fun Skeleton(fraction: Float, height: Dp = 12.dp) {
    Box(Modifier.fillMaxWidth(fraction).height(height).clip(RoundedCornerShape(6.dp)).background(TeamCode.colors.skeleton))
}

@Composable
fun ErrorText(message: Failure?, modifier: Modifier = Modifier) {
    if (message != null) Text(message.words(), color = TeamCode.colors.danger, fontSize = 13.sp, modifier = modifier.testTag("error"))
}

@Composable
fun BusyRow(busy: Boolean) {
    if (busy) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), color = TeamCode.colors.accent, strokeWidth = 2.5.dp)
    }
}

// ---- Fields --------------------------------------------------------------------------------------------------------

/**
 * A labelled field, 48 dp, on the alternate panel; [error] outlines it in red. The [tag] and [contentType] go on the
 * editable node itself, so tests type into it and password managers fill it.
 */
@Composable
fun TcTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    hint: String? = null,
    error: Boolean = false,
    mono: Boolean = false,
    password: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    tag: String? = null,
    contentType: ContentType? = null,
    shape: RoundedCornerShape = Radii.control,
    minHeight: Dp = if (LocalDense.current) 38.dp else 48.dp,
    leading: ImageVector? = null,
    /**
     * Enter from a physical keyboard does this (send, sign in, open) instead of breaking the line; Shift+Enter and
     * Alt+Enter break it. An on-screen keyboard keeps its own Enter. Null: Enter behaves as by default.
     */
    onSubmit: (() -> Unit)? = null,
    /** Inside the field, at its end (the composer's microphone). */
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = TeamCode.colors
    // The field edits a TextFieldValue to insert a line break where the cursor is; the caller keeps a String.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // Changed from outside (a draft cleared after sending, a value trimmed): the caret stays where it was, within the
    // new text; a field filled from empty (a restored draft), or whose caret was at the end (dictated words streaming
    // in), puts it at the end.
    if (field.text != value) {
        val atEnd = field.selection.collapsed && field.selection.end == field.text.length
        val caret = if (field.text.isEmpty() || atEnd) TextRange(value.length) else TextRange(field.selection.start.coerceAtMost(value.length), field.selection.end.coerceAtMost(value.length))
        field = TextFieldValue(value, caret)
    }
    val submit by rememberUpdatedState(onSubmit)
    val style = TextStyle(
        color = colors.text,
        fontSize = if (mono) 14.sp else 15.sp,
        lineHeight = 21.sp,
        fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else androidx.compose.ui.text.font.FontFamily.Default,
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(
            value = field,
            onValueChange = { next ->
                field = next
                if (next.text != value) onValueChange(next.text)
            },
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = style,
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onSubmit == null) Modifier else Modifier.onPreviewKeyEvent { event ->
                    when (enterAction(event, singleLine)) {
                        EnterAction.PASS -> false
                        // Every event of the key is consumed, its release and the line feed it types included.
                        EnterAction.SUBMIT -> { if (event.type == KeyEventType.KeyDown) submit?.invoke(); true }
                        EnterAction.NEWLINE -> {
                            if (event.type == KeyEventType.KeyDown) {
                                val text = field.text.replaceRange(field.selection.min, field.selection.max, "\n")
                                field = TextFieldValue(text, TextRange(field.selection.min + 1))
                                onValueChange(text)
                            }
                            true
                        }
                    }
                })
                .then(tag?.let { Modifier.testTag(it) } ?: Modifier)
                .then(contentType?.let { type -> Modifier.semantics { this.contentType = type } } ?: Modifier),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = minHeight)
                        .clip(shape)
                        .background(colors.panelAlt)
                        .border(1.dp, if (error) colors.danger else colors.line, shape)
                        .padding(horizontal = 12.dp, vertical = if (singleLine) 0.dp else 11.dp),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (leading != null) Icon(leading, contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
                        Box(Modifier.weight(1f), contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                            if (value.isEmpty() && placeholder != null) Text(placeholder, style = style.copy(color = colors.muted), maxLines = if (singleLine) 1 else 3, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                        trailing?.invoke()
                    }
                }
            },
        )
        if (hint != null) Text(hint, color = colors.muted, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

// ---- Screen frames -------------------------------------------------------------------------------------------------

/** A tab's large title, under the status bar, with its actions at the end. */
@Composable
fun LargeTitle(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, color = TeamCode.colors.text, modifier = Modifier.weight(1f).semantics { heading() })
        actions()
    }
}

/** A pushed screen's bar: the way back (named after where it leads), the title, the screen's actions. */
@Composable
fun NavBar(backLabel: String, onBack: (() -> Unit)?, title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val colors = TeamCode.colors
    Row(
        modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A screen at the root of the desktop's content panel has nowhere to go back to: the sidebar is the way out.
        if (onBack != null) BackLink(backLabel, onBack, Modifier.widthIn(max = 120.dp)) else Spacer(Modifier.width(44.dp))
        Text(title, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { heading() })
        Row(Modifier.widthIn(min = 44.dp), horizontalArrangement = Arrangement.End, content = actions)
    }
}

/** "‹ Sessions": a back button carrying its destination's name. */
@Composable
fun BackLink(label: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TeamCode.colors
    Row(
        modifier.heightIn(min = if (LocalDense.current) 32.dp else 44.dp).clip(Radii.control).hoverHighlight(Radii.control).handCursor().clickable(role = Role.Button, onClick = onBack).padding(start = 2.dp, end = 6.dp).testTag("back"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PhosphorIcons.Regular.CaretLeft, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(20.dp))
        Text(label, color = colors.accentText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A full-height form without tabs (add a server, sign in): scrollable content, the main button kept at the bottom,
 * above the keyboard.
 */
@Composable
fun FormPage(
    modifier: Modifier = Modifier,
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxSize().background(TeamCode.colors.background).windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = footer)
    }
}

// ---- Sheets --------------------------------------------------------------------------------------------------------

/** A bottom sheet on the app's background, with its grab handle; [header] holds its title and buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TcSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = TeamCode.colors
    // A bottom sheet across a desktop window or a tablet is a strip 1 000 dp wide: there, the same content is a dialog.
    if (LocalWideLayout.current) {
        val window = LocalWindowInfo.current.containerSize
        val maxHeight = with(LocalDensity.current) { (window.height * 0.85f).toDp() }
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight).clip(Radii.card).background(colors.background)
                    .border(1.dp, colors.line, Radii.card).padding(top = 8.dp, bottom = 4.dp).testTag("dialog"),
                content = content,
            )
        }
        return
    }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        modifier = modifier,
        shape = Radii.sheet,
        containerColor = colors.background,
        contentColor = colors.text,
        scrimColor = colors.shade,
        tonalElevation = 0.dp,
        dragHandle = { Box(Modifier.padding(top = 8.dp, bottom = 2.dp).size(width = 36.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(colors.line)) },
        content = content,
    )
}

/** A sheet's title row: an optional button at the start (Cancel), the title, an optional button at the end. */
@Composable
fun SheetHeader(title: String, modifier: Modifier = Modifier, centered: Boolean = false, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = if (centered) 8.dp else 16.dp, vertical = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (centered) Box(Modifier.width(88.dp)) { leading?.invoke() }
        Text(title, color = TeamCode.colors.text, fontWeight = FontWeight.SemiBold, fontSize = if (centered) 16.sp else 18.sp, textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.weight(1f).semantics { heading() })
        Box(Modifier.then(if (centered) Modifier.width(88.dp) else Modifier), contentAlignment = Alignment.CenterEnd) { trailing?.invoke() }
    }
}

/**
 * View models made inside a sheet live as long as the sheet: each opening starts from a fresh form, and closing it
 * clears them instead of keeping them on the screen underneath.
 */
@Composable
fun SheetViewModels(content: @Composable () -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
}

/** The shared frame of a screen's body on a wide window: centered, at most [ContentWidth]. */
@Composable
fun Centered(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = ContentWidth).fillMaxWidth().fillMaxHeight(), content = content)
    }
}

/** A tap target that only consumes clicks (no ripple), for rows that are not buttons. */
@Composable
fun Modifier.quietClickable(onClick: () -> Unit): Modifier = clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

/**
 * A tap that no child consumed (empty space, plain text) drops focus and closes the on-screen keyboard.
 * Taps on buttons, links, cards and text fields are consumed by those, and scrolling is untouched.
 */
@Composable
fun Modifier.dismissKeyboardOnTap(): Modifier {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return pointerInput(focus, keyboard) {
        detectTapGestures {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
}
