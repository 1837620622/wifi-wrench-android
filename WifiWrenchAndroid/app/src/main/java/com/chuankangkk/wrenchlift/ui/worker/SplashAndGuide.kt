package com.chuankangkk.wrenchlift.ui.worker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chuankangkk.wrenchlift.R
import com.chuankangkk.wrenchlift.measurement.LinkPhase
import com.chuankangkk.wrenchlift.measurement.MeasurementState
import com.chuankangkk.wrenchlift.measurement.WorkerText

/**
 * 启动页（§5.5）：定稿全屏场景图（示意 `docs/design-renders/03-splash.png`；运行时 `wifi_wrench_splash_scene.jpg`，标题/署名已在图内）。无端口、无按钮、无叠字。
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier, animate: Boolean = true) {
    val fade = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (animate) fade.animateTo(1f, tween(420)) }
    BoxWithConstraints(modifier.fillMaxSize().background(SplashColors.background).testTag("splash")) {
        // 启动页直接使用产品方定稿场景（docs/design-renders/03-splash.png 示意；标题/署名已烘焙在图内，不再叠字）。
        Image(
            painter = painterResource(R.drawable.wifi_wrench_splash_scene),
            contentDescription = "WiFi 扳手 · 隔振器顶升调频采集 · 传康Kk 免费供应",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(fade.value),
        )
    }
}

/** 启动页结束后的去向（SPL-04）：已在扳手 Wi‑Fi → 工作台；否则 → 连接引导（无端口）。 */
fun MeasurementState.onWrenchNetwork(): Boolean {
    val ssidMatches = targetWifiName.isNotBlank() && currentWifiName.equals(targetWifiName, ignoreCase = true)
    return linkPhase == LinkPhase.READY || linkPhase == LinkPhase.WAITING_REPLY || inWrenchSubnet || ssidMatches
}

/**
 * 连接引导（NET-04）：只提示去系统连扳手 Wi‑Fi、名称说明与连接状态；没有端口栏。
 */
/** wifi_wrench_splash_t_handle.png：820×430。 */
private const val GUIDE_IMAGE_ASPECT = 820f / 430f

@Composable
fun ConnectGuideScreen(
    state: MeasurementState,
    actions: WorkerActions,
    onOpenRecords: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val p = palette
    Column(
        Modifier.fillMaxSize().background(p.canvas).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = WrenchDimens.screenEdge).testTag("guide"),
    ) {
        PageTopBar("连接扳手", onBack = null) {
            IconAction(WrenchIcon.Records, "记录", onOpenRecords)
            Spacer(Modifier.width(8.dp))
            IconAction(WrenchIcon.Settings, "设置", onOpenSettings)
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(WrenchDimens.gap)) {
            Panel(Modifier.weight(0.42f).fillMaxHeight(), padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Box(Modifier.fillMaxSize().background(SplashColors.background), contentAlignment = Alignment.Center) {
                    // 产品图按原比例铺满面板宽度，四边渐隐进深炭底：不留硬边框，也不裁掉两侧手把。
                    Image(
                        painter = painterResource(R.drawable.wifi_wrench_splash_t_handle),
                        contentDescription = "T 形手把 Wi‑Fi 扭矩扳手",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(GUIDE_IMAGE_ASPECT)
                            .drawWithContent {
                                drawContent()
                                val bg = SplashColors.background
                                drawRect(Brush.verticalGradient(0f to bg, 0.22f to Color.Transparent, 0.78f to Color.Transparent, 1f to bg))
                                drawRect(Brush.horizontalGradient(0f to bg, 0.08f to Color.Transparent, 0.92f to Color.Transparent, 1f to bg))
                            },
                    )
                }
            }
            Panel(Modifier.weight(0.58f).fillMaxHeight(), padding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
                // 说明区从上往下排，矮屏或大字体时可以往下拉，不会挤压或叠到底部按钮；按钮固定在底部。
                val infoScroll = rememberScrollState()
                val panelColor = p.panel
                Column(
                    Modifier
                        .weight(1f)
                        .bottomScrollHint(infoScroll, panelColor)
                        .verticalScroll(infoScroll)
                        .testTag("guide_info"),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // 手动断行只放在逗号后：长标题在窄栏里不会把「扳手」从中间劈开。
                    GuideStep(1, "打开系统设置，\n连上扳手的 Wi‑Fi", state.targetWifiName.takeIf { it.isNotBlank() }?.let { "名称：$it" } ?: "名称见扳手铭牌")
                    GuideStep(2, "回到这里，\n会自动连上扳手", null)
                    // 状态胶囊单独一行；提示写在下面（未连 Wi‑Fi 时第 2 步已说明，不再重复）。胶囊允许两行，「请先在系统设置连接扳手 Wi‑Fi」不被省略。
                    StatusPill(WorkerText.link(state), linkColor(state.linkPhase), icon = WrenchIcon.Wifi, maxLines = 2, modifier = Modifier.testTag("guide_status"))
                    WorkerText.linkHint(state)?.takeIf { state.linkPhase != LinkPhase.NO_WIFI }?.let {
                        Text(it, style = WrenchType.labelStyle, color = p.textSecondary)
                    }
                    Text("当前 Wi‑Fi：${state.currentWifiName}", style = WrenchType.captionStyle.copy(fontSize = 13.sp), color = p.textTertiary)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ControlButton("去连接 Wi‑Fi", WrenchIcon.Wifi, actions.openWifiSettings, Modifier.weight(1f).testTag("open_wifi"), ButtonTone.Accent, height = 56.dp)
                    ControlButton(
                        if (state.linkPhase == LinkPhase.PROBING) "正在连接…" else "重新连接",
                        WrenchIcon.Refresh, actions.connect, Modifier.weight(1f).testTag("retry_connect"),
                        enabled = state.linkPhase != LinkPhase.PROBING, height = 56.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideStep(number: Int, title: String, detail: String?) {
    val p = palette
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(p.accentSoft), contentAlignment = Alignment.Center) {
            Text("$number", style = WrenchType.bodyStrong, color = p.accent)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = WrenchType.bodyStrong.copy(fontSize = 17.sp, lineHeight = 23.sp), color = p.textPrimary)
            if (detail != null) Text(detail, style = WrenchType.labelStyle, color = p.textSecondary, textAlign = TextAlign.Start)
        }
    }
}
