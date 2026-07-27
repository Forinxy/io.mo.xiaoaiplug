package io.mo.xiaoaiplug.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mo.xiaoaiplug.config.NoteKind
import io.mo.xiaoaiplug.config.ReleaseInfo
import io.mo.xiaoaiplug.config.ReleaseNote
import io.mo.xiaoaiplug.ui.liquid.lens
import io.mo.xiaoaiplug.ui.liquid.vibrancy
import io.mo.xiaoaiplug.ui.theme.LocalIsDark
import androidx.core.graphics.drawable.toBitmap
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「发现新版本」弹窗。整屏一层暗色遮罩 + 居中的液态玻璃卡片。
 *
 * **刻意不用系统 Dialog（独立 window）**：玻璃卡要靠 [drawBackdrop] 采样它**背后**的应用
 * 内容才有毛玻璃质感，而独立 window 采不到主 window 那一层。所以这整个组件是贴在
 * `AppRoot` 内容层**之上**的一层 overlay，[backdrop] 就是应用内容那张 backdrop。
 *
 * 显隐由外部（[visible]）控制，这里只管淡入淡出 + 缩放。
 *
 * @param backdrop 应用内容层的 backdrop，卡片采它做模糊折射。
 */
@Composable
fun UpdateDialog(
    visible: Boolean,
    release: ReleaseInfo?,
    currentVersion: String,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit
) {
    // release 置空后淡出动画还要拿它渲染，所以记住最后一次非空的
    val shown = release ?: lastRelease
    if (shown != null) lastRelease = shown

    AnimatedVisibility(
        visible = visible && shown != null,
        enter = fadeIn(spring(stiffness = 500f)),
        exit = fadeOut(spring(stiffness = 700f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 遮罩本身吃掉点击 = 点空白处关闭
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible && shown != null,
                enter = fadeIn(spring(stiffness = 500f)) +
                    scaleIn(spring(stiffness = 380f), initialScale = 0.85f),
                exit = fadeOut(spring(stiffness = 700f)) +
                    scaleOut(spring(stiffness = 700f), targetScale = 0.9f)
            ) {
                shown?.let {
                    UpdateCard(
                        release = it,
                        currentVersion = currentVersion,
                        backdrop = backdrop,
                        onDismiss = onDismiss,
                        onUpdate = onUpdate
                    )
                }
            }
        }
    }
}

// AnimatedVisibility 退出期间 release 已经被清空，用它兜住最后一帧的内容。
// 顶层可变量而非 remember：这个组件在 pending 置空后整棵被移除，remember 也就跟着没了。
private var lastRelease: ReleaseInfo? = null

private val CardShape = RoundedCornerShape(28.dp)

@Composable
private fun UpdateCard(
    release: ReleaseInfo,
    currentVersion: String,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit
) {
    val isDark = LocalIsDark.current
    val isGlass = isRuntimeShaderSupported()
    val surfaceColor = MiuixTheme.colorScheme.surface
    // 玻璃模式下卡面必须半透明，实色会盖掉背后采样出来的内容
    val cardTint =
        if (isGlass) surfaceColor.copy(alpha = if (isDark) 0.55f else 0.6f) else surfaceColor

    // 玻璃边缘那圈亮线：深色下用微白提亮，浅色下用微黑收边
    val rimColor =
        if (isDark) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.08f)

    // 卡片最高占屏幕八成：内容再多也不许把按钮顶出屏幕，超出的靠亮点区自己滚。
    val maxCardHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp

    Box(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 32.dp)
            .width(340.dp)
            .heightIn(max = maxCardHeight)
            .clip(CardShape)
            .then(
                if (isGlass) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { CardShape },
                        effects = {
                            padding = maxOf(padding, 24.dp.toPx())
                            vibrancy()
                            blur(12.dp.toPx(), 12.dp.toPx())
                            lens(
                                refractionHeight = 12.dp.toPx(),
                                refractionAmount = 12.dp.toPx()
                            )
                        },
                        onDrawSurface = { drawRect(cardTint) }
                    )
                } else {
                    Modifier.background(cardTint, CardShape)
                }
            )
            .border(1.dp, rimColor, CardShape)
            // 吃掉落在卡片上的点击，免得穿透到遮罩把自己关了
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .padding(24.dp)
    ) {
        Column {
            // 头部固定：不参与滚动
            HeaderRow(release)

            if (release.notes.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "新功能亮点",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(6.dp))
                // 只有日志这段滚动。weight(fill = false)：内容少时按内容高，多到撑满
                // 卡片上限时就在这里滚，头部和按钮各自钉在上下两端。
                // 行距由每行自己的 padding 决定，分组标题和条目的间距不一样。
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    release.notes.forEachIndexed { index, note ->
                        NoteRow(note, isFirst = index == 0)
                    }
                }
            }

            // 按钮固定：不参与滚动
            Spacer(Modifier.height(24.dp))
            ButtonRow(onDismiss = onDismiss, onUpdate = onUpdate)
        }
    }
}

@Composable
private fun HeaderRow(release: ReleaseInfo) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // App 自己的图标。自适应图标（前景+背景）会被 toBitmap 渲染成完整一张，裁成圆角方块即可。
    val appIcon = remember(context) {
        val sizePx = with(density) { 64.dp.roundToPx() }
        context.packageManager.getApplicationIcon(context.applicationInfo)
            .toBitmap(sizePx, sizePx).asImageBitmap()
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            bitmap = appIcon,
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = "发现新版本！（${release.displayVersion}）",
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "为您准备了全新的功能与体验。",
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                fontSize = 13.sp
            )
            release.sizeText?.let { size ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "更新大小：$size",
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/** 每级缩进的横向偏移。 */
private val IndentStep = 18.dp

@Composable
private fun NoteRow(note: ReleaseNote, isFirst: Boolean) {
    when (note.kind) {
        // 分组标题：加粗小标题，上面留白把上一组隔开（首行不留，紧挨着「新功能亮点」）
        NoteKind.SECTION -> Text(
            text = note.text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = if (isFirst) 6.dp else 14.dp, bottom = 4.dp)
        )

        NoteKind.ITEM -> Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .padding(start = IndentStep * note.indent, top = 4.dp, bottom = 4.dp)
        ) {
            if (note.indent == 0) {
                // 顶层项：↑ 图标
                Icon(
                    imageVector = MiuixIcons.Update,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(16.dp)
                )
                Spacer(Modifier.width(10.dp))
            } else {
                // 子项：小圆点，跟顶层的箭头拉开层级
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp, start = 3.dp, end = 3.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantActions)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = note.text,
                fontSize = 14.sp,
                color = if (note.indent == 0) MiuixTheme.colorScheme.onSurface
                else MiuixTheme.colorScheme.onSurfaceVariantActions
            )
        }
    }
}

@Composable
private fun ButtonRow(onDismiss: () -> Unit, onUpdate: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(
            text = "稍后再说",
            onClick = onDismiss,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = onUpdate,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColorsPrimary()
        ) {
            Text(
                text = "立即更新",
                color = MiuixTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = MiuixIcons.Refresh,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
