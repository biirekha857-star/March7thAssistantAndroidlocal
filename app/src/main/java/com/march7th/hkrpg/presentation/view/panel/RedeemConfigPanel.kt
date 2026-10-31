package com.march7th.hkrpg.presentation.view.panel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.march7th.hkrpg.R
import com.march7th.hkrpg.data.api.RedeemCode
import com.march7th.hkrpg.data.api.RedeemCodeService
import com.march7th.hkrpg.data.model.RedeemTaskConfig
import com.march7th.hkrpg.data.model.TaskParamProvider
import org.koin.compose.koinInject
import java.time.LocalDate

/**
 * 自定义码的字符集，比站点清单宽松一点但规则一致：字母、数字、汉字
 * （含假名/韩文），2~32 位，不含空白和标点。
 */
private val CUSTOM_CODE_PATTERN =
    Regex("^[A-Za-z0-9\\u4E00-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF]{2,32}$")

/**
 * 「兑换码」任务的配置面板。
 *
 * # 为什么码不在 APK 里
 *
 * 兑换码是运营侧随时追加、随时失效的东西：内置在包里，发版第二天就有一半
 * 兑不出来；塞进 `update.md` 又会把「版本发布」和「日常运营」绑死 ——
 * 改一个码就得动版本号。所以码单独放站点的 `codes.md`，和公告同构：
 * 执行前拉一次，失败就用本地缓存，最差也只是「这次不兑」。
 *
 * # 分类从哪来
 *
 * 站点上 `## 3.8 前瞻直播 · 2026-11-15` 这种标题，客户端读成
 * 「分组 = 3.8 前瞻直播，截止 = 2026-11-15」；`## 长期` 或 `## 社群福利`
 * 没有日期就是长期码。分组和有效期都由站点决定，客户端不写死任何分类。
 *
 * # 自定义码
 *
 * 用户自己加的码单独存 [RedeemTaskConfig.customCodes]，不跟站点快照混在一起 ——
 * 快照每次执行前会被覆盖，混在一起第二天就没了。
 */
@Composable
internal fun RedeemConfigPanel(
    config: RedeemTaskConfig,
    onConfigChange: (TaskParamProvider) -> Unit,
    service: RedeemCodeService = koinInject(),
) {
    val today = remember { LocalDate.now() }

    var siteCodes by remember { mutableStateOf(emptyList<RedeemCode>()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }
    var input by remember { mutableStateOf("") }
    var inputRejected by remember { mutableStateOf(false) }

    // 首次进来用 30 分钟内的缓存（快），点「刷新」才强制走网络。
    LaunchedEffect(reloadToken) {
        loading = true
        siteCodes = service.load(forceRefresh = reloadToken > 0)
        loadFailed = service.lastLoadFailed
        loading = false

        // 顺手存一份快照：断网又没缓存时，执行任务还能用上一次同步到的码。
        // 只在真的变了才写，避免每次打开面板都存一遍配置。
        val fetched = siteCodes.map { it.code }
        if (fetched.isNotEmpty() && fetched != config.codes) {
            onConfigChange(config.copy(codes = fetched))
        }
    }

    val disabledSet = remember(config.disabledCodes) {
        config.disabledCodes.map { it.trim().uppercase() }.toSet()
    }
    val enabledCount = siteCodes.count { it.code !in disabledSet } + config.customCodes.size

    /** 勾选/取消一个站点码：只记「不要哪些」，站点新加的码才会自动进来。 */
    fun toggle(code: RedeemCode, checked: Boolean) {
        val next = if (checked) {
            config.disabledCodes.filterNot { it.equals(code.code, ignoreCase = true) }
        } else {
            (config.disabledCodes + code.code).distinct()
        }
        onConfigChange(config.copy(disabledCodes = next))
    }

    fun submitCustom() {
        // 按空白/逗号切分。不能用"非字母数字"做分隔符 —— 中文码会被整条切碎。
        val tokens = input.split(Regex("[\\s,，、;；]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            inputRejected = true
            return
        }
        val accepted = tokens
            .filter { CUSTOM_CODE_PATTERN.matches(it) }
            .map { it.uppercase() }
        inputRejected = accepted.size != tokens.size
        if (accepted.isEmpty()) return

        onConfigChange(config.copy(customCodes = (config.customCodes + accepted).distinct()))
        input = ""
    }

    Column(
        // ⚠️ 和 DailyConfigPanel 一样：不能再套 verticalScroll/fillMaxSize，
        // 外层卡片在可滚动列表里测量，内层滚动会拿到无限高度直接崩。
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 说明 ----
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.panel_redeem_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    lineHeight = 18.sp,
                )
            }
        }

        // ---- 站点码 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.panel_redeem_site_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (siteCodes.isNotEmpty()) {
                TextButton(onClick = {
                    onConfigChange(config.copy(disabledCodes = emptyList()))
                }) { Text(stringResource(R.string.panel_redeem_select_all)) }
                TextButton(onClick = {
                    onConfigChange(config.copy(disabledCodes = siteCodes.map { it.code }))
                }) { Text(stringResource(R.string.panel_redeem_clear_all)) }
            }
            TextButton(onClick = { reloadToken++ }, enabled = !loading) {
                Text(stringResource(R.string.panel_redeem_refresh))
            }
        }

        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.panel_redeem_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            siteCodes.isEmpty() -> Text(
                text = stringResource(
                    if (loadFailed) R.string.panel_redeem_failed else R.string.panel_redeem_empty
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            else -> {
                // 限时的按到期先后排前面，长期码垫底
                val groups = remember(siteCodes) {
                    siteCodes.groupBy { it.sectionKey }.values.sortedWith(
                        compareBy<List<RedeemCode>> { it.first().expiresAt == null }
                            .thenBy { it.first().expiresAt ?: LocalDate.MAX }
                    )
                }
                groups.forEach { group ->
                    RedeemGroupCard(
                        group = group,
                        today = today,
                        disabled = disabledSet,
                        onToggle = ::toggle,
                    )
                }
            }
        }

        if (loadFailed && siteCodes.isEmpty() && config.codes.isNotEmpty()) {
            Text(
                text = stringResource(R.string.panel_redeem_using_snapshot, config.codes.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ---- 自定义码 ----
        Text(
            text = stringResource(R.string.panel_redeem_custom_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.panel_redeem_custom_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    inputRejected = false
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = inputRejected,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                placeholder = {
                    Text(
                        text = stringResource(R.string.panel_redeem_custom_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                ),
            )
            Button(onClick = ::submitCustom, enabled = input.isNotBlank()) {
                Text(stringResource(R.string.panel_redeem_add))
            }
        }

        if (inputRejected) {
            Text(
                text = stringResource(R.string.panel_redeem_custom_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        config.customCodes.forEach { code ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    onConfigChange(
                        config.copy(
                            customCodes = config.customCodes.filterNot { it.equals(code, ignoreCase = true) }
                        )
                    )
                }) { Text(stringResource(R.string.panel_redeem_remove)) }
            }
        }

        Text(
            text = stringResource(R.string.panel_redeem_summary, enabledCount),
            style = MaterialTheme.typography.bodySmall,
            color = if (enabledCount == 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 一个分组（同批次的码）。 */
@Composable
private fun RedeemGroupCard(
    group: List<RedeemCode>,
    today: LocalDate,
    disabled: Set<String>,
    onToggle: (RedeemCode, Boolean) -> Unit,
) {
    val first = group.first()
    val fallbackTitle = stringResource(
        if (first.expiresAt == null) R.string.panel_redeem_group_long
        else R.string.panel_redeem_group_timed
    )
    val title = first.group.ifEmpty { fallbackTitle }
    val daysLeft = first.daysLeft(today)
    val expiryText = when {
        first.expiresAt == null -> stringResource(R.string.panel_redeem_long_term)
        daysLeft != null && daysLeft < 0 -> stringResource(R.string.panel_redeem_expired)
        daysLeft == 0L -> stringResource(R.string.panel_redeem_expire_today)
        else -> stringResource(R.string.panel_redeem_days_left, daysLeft ?: 0L)
    }
    val urgent = daysLeft != null && daysLeft <= 3

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = expiryText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (urgent) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            group.forEach { code ->
                val checked = code.code !in disabled
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(code, !checked) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { onToggle(code, it) })
                    Text(
                        text = code.code,
                        style = MaterialTheme.typography.bodyMedium
                            .copy(fontFamily = FontFamily.Monospace),
                        color = if (checked) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
