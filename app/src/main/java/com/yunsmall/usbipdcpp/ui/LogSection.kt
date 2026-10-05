package com.yunsmall.usbipdcpp.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yunsmall.usbipdcpp.R

// 日志自动跟随的"贴底"宽容度（dp）：追加日志会把 maxValue 在当前位置之上
// 拉长一两行，跟随动画也可能被新日志打断停在半途，判定要给这些"仍算贴底"
// 的位置留出行高级别的余量；用 dp 是因为行高随系统字体缩放
private const val AUTO_SCROLL_SLACK_DP = 72

@Composable
fun ColumnScope.LogSection(
    logMessages: List<String>,
    logVersion: Long,
    onClear: () -> Unit,
    onViewFullLog: () -> Unit,
    onCopyLog: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1.5f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.log), style = MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(onClick = onViewFullLog) {
                        Text(stringResource(R.string.expand))
                    }
                    TextButton(onClick = onCopyLog) {
                        Text(stringResource(R.string.copy))
                    }
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.clear))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp)
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(8.dp)
            ) {
                // rememberScrollState 放条件外：分支里的 remember 会在分支切换
                //（清空日志↔重新产生）时被丢弃重建
                val scrollState = rememberScrollState()
                // 贴底宽容度换算成像素：dp 随系统字体缩放，任何字号下都留足行高余量
                val slackPx = with(LocalDensity.current) { AUTO_SCROLL_SLACK_DP.dp.roundToPx() }

                if (logMessages.isEmpty()) {
                    Text(
                        stringResource(R.string.no_logs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    AutoScrollEffect(scrollState, logVersion, slackPx)

                    Text(
                        // 拼接只在日志内容变化时发生（参数不变时重组被 Compose 跳过，
                        // 滚动不触发重组），500 条上限下开销微秒级；加缓存或换
                        // LazyColumn 的复杂度换不来收益
                        text = logMessages.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                    )
                }
            }
        }
    }
}

@Composable
fun FullLogDialog(logMessages: List<String>, logVersion: Long, onDismiss: () -> Unit) {
    val scrollState = rememberScrollState()
    val slackPx = with(LocalDensity.current) { AUTO_SCROLL_SLACK_DP.dp.roundToPx() }

    AutoScrollEffect(scrollState, logVersion, slackPx)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_messages)) },
        text = {
            SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = logMessages.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

// 自动滚动的共用 effect（LogSection 与 FullLogDialog）：判定与滚动都放进
// key 驱动的 effect，slackPx 由调用方按自身 LocalDensity 计算传入
@Composable
private fun AutoScrollEffect(scrollState: ScrollState, logVersion: Long, slackPx: Int) {
    LaunchedEffect(logVersion, slackPx) {
        // 新文本的测量/布局晚于本 effect 启动：等一帧再读写 maxValue，
        // 否则判定与滚动目标都基于旧布局
        withFrameNanos { }
        // 用户往上翻看历史时不要打断：只在贴近底部时跟随最新日志。
        // 宽容度见 AUTO_SCROLL_SLACK_DP 的说明；slackPx 进 key 让
        // 字体缩放变化后按新值重新判断——判定在本 effect 内做，
        // 缩放重启 effect 不会把远离底部的历史读者拉回底部
        if (scrollState.value >= scrollState.maxValue - slackPx) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
}
