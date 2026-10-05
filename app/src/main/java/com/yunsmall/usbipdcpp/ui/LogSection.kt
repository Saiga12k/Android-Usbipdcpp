package com.yunsmall.usbipdcpp.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yunsmall.usbipdcpp.R
import kotlinx.coroutines.flow.first

// 日志自动跟随的"贴底"宽容度（dp）：用户拖拽停止后与底部的距离在此之内
// 仍视为贴底、继续跟随最新；用 dp 是因为行高随系统字体缩放
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

// 自动滚动的共用 effect（LogSection 与 FullLogDialog）：是否跟随是显式状态，
// 只有用户的拖拽能改变；slackPx 由调用方按自身 LocalDensity 计算传入
@Composable
private fun AutoScrollEffect(scrollState: ScrollState, logVersion: Long, slackPx: Int) {
    // 贴底跟随开关，默认开。判定不能用"读 value 与 maxValue 的瞬时差"来做：
    // 内容单次增长超过 slack（日志突发、单条折行多行）会一次把底部推远，
    // 瞬时判定会把内容增长误认成"用户上翻了"，跟随会就此永久停住
    var followLatest by rememberSaveable { mutableStateOf(true) }

    // 开关的唯一改变点：用户拖拽停止后按最终位置决定。等惯性滑动也停下再
    // 判定；程序性滚动（下面的 scrollTo）不产生 DragInteraction，不会误改状态。
    // saveable 让旋转重建后维持上翻用户不被拉底
    LaunchedEffect(scrollState, slackPx) {
        scrollState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Stop) {
                snapshotFlow { scrollState.isScrollInProgress }.first { !it }
                followLatest = scrollState.value >= scrollState.maxValue - slackPx
                // 拖拽期间到达的日志在下面的 effect 里被跳过（不抢拖拽），
                // 这里按判定补一次，避免要等下一条日志才归位
                if (followLatest) {
                    scrollState.scrollTo(scrollState.maxValue)
                }
            }
        }
    }

    LaunchedEffect(logVersion, slackPx, followLatest) {
        if (!followLatest) return@LaunchedEffect
        // 拖拽/惯性滑动进行中不抢位置：停下后由上方的判定归位
        if (scrollState.isScrollInProgress) return@LaunchedEffect
        // 新文本的测量/布局晚于本 effect 启动：等一帧让滚动目标尽量准确。
        // 即使这次读到旧布局的 maxValue，下一条日志的 effect 会继续滚到底，
        // 不像"位置判定"方案那样把滞后积累成停摆
        withFrameNanos { }
        // 用立即滚动而不是动画：新日志会不断重启本 effect 取消动画，
        // 洪水下位置逐次落后于追加速度，跟随会肉眼可见地停住
        scrollState.scrollTo(scrollState.maxValue)
    }
}
