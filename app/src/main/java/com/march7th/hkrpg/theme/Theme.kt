package com.march7th.hkrpg.theme

import android.os.Build
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalContext
import com.march7th.hkrpg.data.preferences.AppSettingsManager

// ============================================================================
// 崩铁配色（Honkai: Star Rail）
// ============================================================================
//
// 取自崩铁游戏内 UI 的两组主色：
//   - **星穹列车金**：车厢、按钮描边、成就徽章的主色（#E8C87A 系）
//   - **深空蓝黑**：星际和平公司界面、模拟宇宙的底色（#0A0E1A 系）
//
// 辅色用**命途青**（#5DD3E8，技能/能量条）和**梦境紫**
// （#9B7FD4，梦境护照那一页的紫）。
//
// 明色模式走"羊皮纸"路线 —— 崩铁的邮件页、成就页都是暖米色底 + 金字，
// 所以明色不做成刺眼纯白，而是 #F5F1E8 这种带黄调的暖白。

/**
 * 主色。**深金**而非亮金：亮金（#E8C87A）在白底上对比度不足，
 * 一旦用在按钮底 + 白字上就读不清；深金配白字约 4.6:1，刚过 AA。
 */
private val AccentGold = Color(0xFF8A6A16)
private val AccentGoldDark = Color(0xFFE8C87A)

/**
 * 次要强调：中性冷灰。
 *
 * 原来是命途青（#2A9DB5）。在「白底 + 单一强调色」的工具型界面里，
 * 第二个饱和色会让所有次要元素都在抢注意力，改成中性灰蓝只做层级区分。
 */
private val NeutralAccent = Color(0xFF5A6572)

/**
 * 警示色：琥珀橙。
 *
 * ⚠️ 这里原来放的是**梦境紫 `#7B5FB8`**，但 `tertiary` 在 `HomeView`
 * 等处被当作 `StatusColorType.WARNING` 使用 —— 用紫色表示警告是错位的，
 * 用户看到一个紫色圆点不会联想到"有问题"。现在统一成琥珀色。
 */
private val WarningAmber = Color(0xFFB45309)

// --- 明色：中性白 + 深金强调 ---
//
// 原先是"羊皮纸"暖米底（#F7F3EA）。暖色调 + 金字是崩铁游戏内的观感，
// 但作为工具类 App，底色带黄会让所有内容显得发旧、也压不住信息层级。
// 现在改成中性灰白，金色只留在**按钮、选中态、强调**上。
private val LightBackground = Color(0xFFF4F5F7)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFF0F1F3)
private val LightOnSurface = Color(0xFF1A1A1A)
private val LightOnSurfaceVariant = Color(0xFF6B6B70)
private val LightOutline = Color(0xFFE4E5E8)

// --- 暗色：中性深灰 + 亮金 ---
//
// 原先是深空蓝黑（#0B0F1A）。蓝调在 OLED 上会偏色，也让"纯黑模式"
// 与"普通暗色"之间缺少明确分界；改成中性灰阶后两者只差明度。
private val DarkBackground = Color(0xFF121212)
private val DarkSurface = Color(0xFF1C1C1E)
private val DarkSurfaceVariant = Color(0xFF262629)
private val DarkOnSurface = Color(0xFFEDEDED)
private val DarkOnSurfaceVariant = Color(0xFF9E9EA3)
private val DarkOutline = Color(0xFF333336)

private val PureDarkBackground = Color(0xFF000000)
private val PureDarkSurface = Color(0xFF0A0A0A)
private val PureDarkSurfaceVariant = Color(0xFF161618)


private fun createLightColorScheme(
    primary: Color, primaryContainer: Color, onPrimaryContainer: Color
): ColorScheme {
    return lightColorScheme(
        primary = primary,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = NeutralAccent,
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE8EBEF),
        onSecondaryContainer = Color(0xFF2A3038),
        tertiary = WarningAmber,
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFE8D2),
        onTertiaryContainer = Color(0xFF3D2100),
        background = LightBackground,
        onBackground = LightOnSurface,
        surface = LightSurface,
        onSurface = LightOnSurface,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant,
        outline = LightOutline,
        // 专用于卡片描边：比 outline 更淡、比 surfaceVariant 更实，
        // 在 #F4F5F7 页底上刚好能看出边界又不抢线。
        outlineVariant = Color(0xFFE6E7EA),
        error = Color(0xfff53f3f),
        onError = Color.White,
        errorContainer = Color(0xFFFFD8D6),
        onErrorContainer = Color(0xFF690005)
    )
}

private fun createDarkColorScheme(
    primary: Color, primaryContainer: Color, onPrimaryContainer: Color, isPureDark: Boolean = false
): ColorScheme {
    val bg = if (isPureDark) PureDarkBackground else DarkBackground
    val surface = if (isPureDark) PureDarkSurface else DarkSurface
    val surfaceVariant = if (isPureDark) PureDarkSurfaceVariant else DarkSurfaceVariant

    return darkColorScheme(
        primary = primary,
        onPrimary = Color(0xFF2A2000),
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = Color(0xFFA8B2BE),
        onSecondary = Color(0xFF2A3038),
        secondaryContainer = Color(0xFF2E343C),
        onSecondaryContainer = Color(0xFFDCE1E7),
        tertiary = Color(0xFFF0A75A),
        onTertiary = Color(0xFF3D2100),
        tertiaryContainer = Color(0xFF5A3410),
        onTertiaryContainer = Color(0xFFFFE8D2),
        background = bg,
        onBackground = DarkOnSurface,
        surface = surface,
        onSurface = DarkOnSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,
        outline = DarkOutline,
        outlineVariant = Color(0xFF2A2A2D),
        error = Color(0xFFFF453A),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6)
    )
}

// --- 崩铁配色（默认）---
private val AppLightScheme = createLightColorScheme(
    primary = AccentGold,
    primaryContainer = Color(0xFFF5EBD5),
    onPrimaryContainer = Color(0xFF3B2E08)
)

private val AppDarkScheme = createDarkColorScheme(
    primary = AccentGoldDark,
    primaryContainer = Color(0xFF4A3B14),
    onPrimaryContainer = Color(0xFFF5EBD5)
)

private val AppPureDarkScheme = createDarkColorScheme(
    primary = AccentGoldDark,
    primaryContainer = Color(0xFF4A3B14),
    onPrimaryContainer = Color(0xFFF5EBD5),
    isPureDark = true
)

// --- 曾有一套"原蓝色配色"（primary #2B6BCA），已于 0.5.6beta 删除 ---
//
// 那三套 Blue* 是早期默认主题的遗留物。改成崩铁配色后，
// [AppTheme] 的兜底分支就只走 [HonkaiLight]/[HonkaiDark] 了，
// 它们**一个引用点都没有**，属于纯死代码 —— 留着只会让"关闭莫奈
// 到底用哪套颜色"这个问题每次都要翻一遍源码才能回答。
//
// 现在关系是唯一的：关闭莫奈 = 金色（[HonkaiLight]/[HonkaiDark]/[HonkaiPureDark]），
// 开启莫奈 = 系统壁纸取色。设置页文案与这里保持同一句话。

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(AppDesignTokens.CornerRadius.inner),
    small = RoundedCornerShape(AppDesignTokens.CornerRadius.button),
    medium = RoundedCornerShape(AppDesignTokens.CornerRadius.card),
    large = RoundedCornerShape(AppDesignTokens.CornerRadius.card),
    extraLarge = RoundedCornerShape(AppDesignTokens.CornerRadius.pill)
)


private object NoIndication : IndicationNodeFactory {
    private class NoIndicationNode : Modifier.Node(), DrawModifierNode {
        override fun ContentDrawScope.draw() {
            drawContent()
        }
    }

    override fun create(interactionSource: InteractionSource): DelegatableNode {
        return NoIndicationNode()
    }

    override fun hashCode(): Int = -1

    override fun equals(other: Any?): Boolean = other === this
}

object AppThemeAlphas {
    const val DISABLED = 0.38f
    const val SECONDARY = 0.60f
    const val MEDIUM = 0.74f
}

/**
 * 保存启用玻璃背景前的「原始不透明」ColorScheme，供 [OpaqueTheme] 在弹窗中恢复。
 * 由 [AppTheme] 统一下发；未进入 App 主题时为 null。
 */
val LocalOpaqueColorScheme = staticCompositionLocalOf<ColorScheme?> { null }

/** 主界面自定义背景启用时，卡片/表面的默认不透明度（玻璃拟态）。 */
const val GLASS_SURFACE_ALPHA = 0.82f

/**
 * 生成玻璃版配色：背景置透明（露出背景图），各 surface 族加透明度让卡片透出背景，
 * 前景 on* 色保持不透明以保证文字清晰。
 */
fun ColorScheme.toGlass(surfaceAlpha: Float = GLASS_SURFACE_ALPHA): ColorScheme = copy(
    background = Color.Transparent,
    surface = surface.copy(alpha = surfaceAlpha),
    surfaceVariant = surfaceVariant.copy(alpha = surfaceAlpha),
    surfaceBright = surfaceBright.copy(alpha = surfaceAlpha),
    surfaceDim = surfaceDim.copy(alpha = surfaceAlpha),
    surfaceContainer = surfaceContainer.copy(alpha = surfaceAlpha),
    surfaceContainerLowest = surfaceContainerLowest.copy(alpha = surfaceAlpha),
    surfaceContainerLow = surfaceContainerLow.copy(alpha = surfaceAlpha),
    surfaceContainerHigh = surfaceContainerHigh.copy(alpha = surfaceAlpha),
    surfaceContainerHighest = surfaceContainerHighest.copy(alpha = surfaceAlpha),
)

/**
 * 在玻璃背景作用域内恢复不透明配色的包装器。
 *
 * 用于弹窗（[com.march7th.hkrpg.presentation.components.AdaptiveTaskPromptDialog] 等）——
 * 它们在自身独立窗口内呈现，不应透出主界面背景图。若当前不处于玻璃作用域（[LocalOpaqueColorScheme] 为
 * 空或与当前配色一致），则为无副作用的透传。
 */
@Composable
fun OpaqueTheme(content: @Composable () -> Unit) {
    val opaque = LocalOpaqueColorScheme.current
    if (opaque == null || opaque === MaterialTheme.colorScheme) {
        content()
    } else {
        ProvideColorScheme(opaque, content)
    }
}

/**
 * 以指定配色应用 MaterialTheme（沿用当前排版与形状），并把内容色同步为 onSurface。
 * 玻璃配色（[toGlass]）与弹窗恢复不透明配色（[OpaqueTheme]）共用此包装。
 */
@Composable
fun ProvideColorScheme(scheme: ColorScheme, content: @Composable () -> Unit) {
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}

/**
 * 应用主题。
 *
 * ## 默认用崩铁配色，不跟随莫奈
 *
 * 原来是「默认跟随系统莫奈取色」，但那样界面颜色会随壁纸变 ——
 * 对一个崩铁助手来说反而失去了辨识度。现在默认走 [HonkaiLight]/[HonkaiDark]
 * 这套「星穹列车金 + 深空蓝黑」，莫奈降级成可选项（设置里那个开关）。
 */
@Composable
fun AppTheme(
    themeMode: AppSettingsManager.ThemeMode = AppSettingsManager.ThemeMode.SYSTEM,
    useSystemMonetColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val isDarkTheme = when (themeMode) {
        AppSettingsManager.ThemeMode.SYSTEM -> systemDarkTheme
        AppSettingsManager.ThemeMode.WHITE -> false
        AppSettingsManager.ThemeMode.DARK, AppSettingsManager.ThemeMode.PURE_DARK -> true
    }
    val isPureDark = themeMode == AppSettingsManager.ThemeMode.PURE_DARK
    val colorScheme: ColorScheme = remember(themeMode, useSystemMonetColor, isDarkTheme, context) {
        when {
            // Android 12+ 且用户显式开启莫奈 ==> 跟随系统取色
            useSystemMonetColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val dynamic =
                    if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(
                        context
                    )
                // PURE_DARK keeps the monet-tinted primary but forces pure-black surfaces
                if (isPureDark) {
                    dynamic.copy(
                        background = PureDarkBackground,
                        surface = PureDarkSurface,
                        surfaceVariant = PureDarkSurfaceVariant
                    )
                } else dynamic
            }
            // 默认：崩铁配色
            else -> when (themeMode) {
                AppSettingsManager.ThemeMode.SYSTEM -> if (systemDarkTheme) AppDarkScheme else AppLightScheme
                AppSettingsManager.ThemeMode.WHITE -> AppLightScheme
                AppSettingsManager.ThemeMode.DARK -> AppDarkScheme
                AppSettingsManager.ThemeMode.PURE_DARK -> AppPureDarkScheme
            }
        }
    }

    val reduceMotion = remember(context) { M7aMotion.reduceMotion(context) }
    CompositionLocalProvider(
        LocalIndication provides NoIndication,
        LocalOpaqueColorScheme provides colorScheme,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
        ) {
            ProvideLogPalette(isDark = isDarkTheme, content = content)
        }
    }
}
