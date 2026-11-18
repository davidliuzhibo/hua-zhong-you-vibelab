package com.vrgames.huazhong.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.PhysicalLengthConverter
import com.vrgames.huazhong.core.Scrolls
import com.vrgames.huazhong.core.Task
import com.vrgames.huazhong.game.Game
import kotlin.math.roundToInt

const val PANEL_CARD = "card"
const val PANEL_HOME = "home"
const val PANEL_TASK = "task"
const val PANEL_RESULT = "result"
const val PANEL_NOTE = "note"
const val PANEL_MENU = "menu"

// Ink for text on the scrolls' paper (the page and scroll frames); light text on the title frame.
private val Ink = Color(0xFFF6EEDC)
// v0.3.1 (device 2026-11-18: 「部分字体的颜色跟面板底色的颜色过于接近……很多文字看不清楚」): the paper of the 3D
// scroll frames is lit with the scene and went grey inside the darker paintings, under dark ink. Now the text sits on a
// dark lacquer panel of its own (unlit, the same in every scene, see [Paper]) in light colours: rice-white for the body,
// warm bright amber and orange for what must stand out.
private val InkSoft = Color(0xFFE2D5BC)
private val SealRed = Color(0xFFFFC27A)
val LabelInk = Color(0xFFF4EEE2)
val Brass = Color(0xFFE2B75A)
val Dim = Color(0xFFE2DACB)
private val PanelBg = Color(0xE6151A22)
private val PaperBg = Color(0xF51C1511)
private val TitleRed = Color(0xFFFFA567)
private val Green = Color(0xFFA8E8B4)
private val GoldInk = Color(0xFFFFD86E)

@Composable
fun PhysicalLengthConverter.m(meters: Float): Dp = lengthToDp(meters, LengthUnit.Meters)

@Composable
fun PhysicalLengthConverter.sz(meters: Float) = m(meters).value.sp

@Composable
private fun PanelBox(c: PhysicalLengthConverter, width: Float, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(c.m(0.014f))
    Column(
        Modifier.width(c.m(width)).clip(shape).background(PanelBg).border(c.m(0.0012f), Color(0x55E2B75A), shape).padding(c.m(0.014f)),
        verticalArrangement = Arrangement.spacedBy(c.m(0.006f)),
    ) { content() }
}

/** The one step that moves the visit on: brass with dark text, never a plain dark button. */
@Composable
fun CallToAction(c: PhysicalLengthConverter, text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(c.m(0.012f))
    Text(
        text,
        Modifier.clip(shape).background(Brass).border(c.m(0.002f), Color(0xFFFFF1C9), shape)
            .clickable { onClick() }.padding(horizontal = c.m(0.018f), vertical = c.m(0.009f)),
        fontSize = c.sz(0.021f),
        color = Color(0xFF1B1408),
    )
}

@Composable
fun Pill(c: PhysicalLengthConverter, text: String, color: Color = Dim, onClick: () -> Unit) {
    val shape = RoundedCornerShape(c.m(0.01f))
    Text(
        text,
        Modifier.clip(shape).border(c.m(0.0015f), color, shape).clickable { onClick() }.padding(horizontal = c.m(0.011f), vertical = c.m(0.006f)),
        fontSize = c.sz(0.015f),
        color = color,
    )
}

/** An answer to pick: a big outlined line in ink on the scroll's paper. */
@Composable
private fun Answer(c: PhysicalLengthConverter, text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(c.m(0.01f))
    Text(
        text,
        Modifier.clip(shape).background(Color(0x33FFC27A)).border(c.m(0.0018f), SealRed, shape).clickable { onClick() }.padding(horizontal = c.m(0.014f), vertical = c.m(0.008f)),
        fontSize = c.sz(0.018f),
        color = Ink,
    )
}

/** Text on the paper of a thick scroll frame (the model is added by the stage): scaled down to fit, never cut. */
@Composable
private fun Paper(c: PhysicalLengthConverter, width: Float, height: Float, content: @Composable () -> Unit) {
    Layout(
        content = { Column(verticalArrangement = Arrangement.spacedBy(c.m(0.006f))) { content() } },
        modifier = Modifier.width(c.m(width)).height(c.m(height)).clip(RoundedCornerShape(c.m(0.008f))).background(PaperBg)
            .padding(horizontal = c.m(0.018f), vertical = c.m(0.006f)),
    ) { measurables, constraints ->
        val p = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity))
        val s = if (p.height > constraints.maxHeight) constraints.maxHeight.toFloat() / p.height else 1f
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = ((constraints.maxWidth - p.width * s) / 2f).roundToInt().coerceAtLeast(0)
            val y = ((constraints.maxHeight - p.height * s) / 2f).roundToInt().coerceAtLeast(0)
            p.placeWithLayer(x, y) {
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

private fun stars(n: Int) = "★".repeat(n) + "☆".repeat(3 - n)

@Composable
fun CardPanel(c: PhysicalLengthConverter) {
    @Suppress("UNUSED_VARIABLE") val v = Game.version
    Paper(c, 0.52f, 0.25f) {
        Text("画中游", fontSize = c.sz(0.036f), color = TitleRed)
        Text("走进两幅宋代名画：卷一《清明上河图》，卷二《千里江山图》。画卷在你面前展开，前景、中景、远景分在不同的深度；在画里找东西、把掉出来的画片放回去、回答小问题，每一段都有语音讲解。", fontSize = c.sz(0.016f), color = Ink)
        Text("怎么玩：看着画，用手指捏一下要找的东西；画片捏住不放，拖回墙上的空白处再松手。卷一 6 段、卷二 4 段，每段没有失误得三星。", fontSize = c.sz(0.014f), color = InkSoft)
        Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.01f)), verticalAlignment = Alignment.CenterVertically) {
            CallToAction(c, "展开长卷 →") { Game.toHome() }
            Pill(c, if (Game.speaking) "停止讲解" else "听开场白", SealRed) { if (Game.speaking) Game.stopVoice() else Game.speak("intro") }
        }
    }
}

@Composable
fun HomePanel(c: PhysicalLengthConverter) {
    @Suppress("UNUSED_VARIABLE") val v = Game.version
    val p = Game.progress
    val next = p.next()
    Paper(c, 0.48f, 0.2f) {
        Text("长卷 · 共 ${Scrolls.ALL.size} 段　星 ${p.stars} / ${p.maxStars}", fontSize = c.sz(0.02f), color = SealRed)
        Text("捏一下长卷上的任意一段，就能走进画里（卷一、卷二各自按顺序打开）。", fontSize = c.sz(0.014f), color = Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.008f)), verticalAlignment = Alignment.CenterVertically) {
            if (next != null) CallToAction(c, "下一段：${if (next.scroll == 0) "卷一" else "卷二"}第 ${next.number} 段 ${next.title} →") { Game.open(next) }
            else CallToAction(c, "上元夜游虹桥 →") { Game.lanternNight() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.008f))) {
            Pill(c, "返回封面", InkSoft) { Game.toCard() }
            if (next != null && p.gold) Pill(c, "上元夜游虹桥", SealRed) { Game.lanternNight() }
            Text(trophyLine(), fontSize = c.sz(0.013f), color = InkSoft)
        }
    }
}

private fun trophyLine() = when (Game.trophies) {
    0 -> "游完卷一得铜印「游」"
    1 -> "已得铜印「游」；两卷都游完得银印「览」"
    2 -> "已得银印「览」；每段三星得金印「珍」"
    else -> "金印「珍」已得：上元夜灯会开放"
}

@Composable
fun TaskPanel(c: PhysicalLengthConverter) {
    @Suppress("UNUSED_VARIABLE") val v = Game.version
    val s = Game.section ?: return
    val visit = Game.visit ?: return
    val total = Scrolls.of(s.scroll).size
    Paper(c, 0.46f, 0.32f) {
        Text("${if (s.scroll == 0) "卷一" else "卷二"} · 第 ${s.number} 段 / 共 $total 段　${s.title}", fontSize = c.sz(0.019f), color = SealRed)
        Text(s.intro, fontSize = c.sz(0.0135f), color = InkSoft)
        val t = visit.task
        if (Game.hasWorld(s) && !Game.inside && t != null) {
            Text("画里的景物正在桌上铺开……", fontSize = c.sz(0.016f), color = Ink)
        } else if (t == null) {
            Text("这一段游完了！", fontSize = c.sz(0.018f), color = Ink)
        } else {
            Text("任务 ${visit.at + 1} / ${s.tasks.size}：${t.say}", fontSize = c.sz(0.016f), color = Ink)
            when (t) {
                is Task.Quiz -> Column(verticalArrangement = Arrangement.spacedBy(c.m(0.005f))) {
                    t.options.forEachIndexed { i, o -> Answer(c, o) { Game.answer(i) } }
                }
                is Task.Rub -> Text("进度 ${(visit.rubbed * 100).toInt()}%", fontSize = c.sz(0.015f), color = InkSoft)
                is Task.Match -> Text("已画：" + (visit.matched.values.joinToString("、").ifEmpty { "还没有" }), fontSize = c.sz(0.015f), color = InkSoft)
                else -> Unit
            }
        }
        Game.lastDone?.let { Text(it, fontSize = c.sz(0.0145f), color = Green) }
        // v0.3: the miniature on the table is where the tasks are; 「走进画里」 stands the player inside at life size.
        if (Game.hasWorld(s) && Game.inside) Pill(c, if (Game.full) "回到小景" else "走进画里 →", SealRed) { Game.walkIn() }
        Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.006f)), verticalAlignment = Alignment.CenterVertically) {
            Pill(c, if (Game.speaking) "停止讲解" else "听讲解", SealRed) { Game.toggleVoice() }
            Pill(c, "提示（扣星）", InkSoft) { Game.hint() }
            Pill(c, "返回长卷", InkSoft) { Game.toHome() }
            Text("失误 ${visit.misses}", fontSize = c.sz(0.013f), color = InkSoft)
        }
    }
}

@Composable
fun ResultPanel(c: PhysicalLengthConverter) {
    @Suppress("UNUSED_VARIABLE") val v = Game.version
    val s = Game.section ?: return
    val visit = Game.visit ?: return
    val next = Game.progress.next()
    Paper(c, 0.52f, 0.25f) {
        Text("${s.title}　${stars(visit.stars())}", fontSize = c.sz(0.03f), color = TitleRed)
        Text("失误 ${visit.misses} 次" + (if (visit.hints > 0) "（含提示 ${visit.hints} 次）" else "") + "。三星：一次也不失误、不用提示。", fontSize = c.sz(0.014f), color = Ink)
        val t = Game.trophies
        if (t > Game.trophiesBefore) Text(
            when (t) {
                1 -> "得到铜印「游」！卷一《清明上河图》游完了。"
                2 -> "得到银印「览」！两幅名画都游完了。"
                else -> "得到金印「珍」！每一段都是三星：上元夜的虹桥灯会为你开放（长卷面板上）。"
            },
            fontSize = c.sz(0.016f), color = GoldInk,
        )
        // v0.3.2 (device 2026-11-18: both tasks done on the miniature, the result came up with no way left into the
        // painting): the walk in is here too.
        if (Game.hasWorld(s) && Game.inside) Pill(c, if (Game.full) "回到小景" else "走进画里 →（游完了也可以进去看看）", SealRed) { Game.walkIn() }
        Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.01f)), verticalAlignment = Alignment.CenterVertically) {
            if (next != null) CallToAction(c, "下一段：${next.title} →") { Game.open(next) } else CallToAction(c, "回到长卷 →") { Game.toHome() }
            Pill(c, "再游一次", SealRed) { Game.again() }
            if (next != null) Pill(c, "回到长卷", InkSoft) { Game.toHome() }
        }
    }
}

@Composable
fun NotePanel(c: PhysicalLengthConverter) {
    val n = Game.note ?: return
    PanelBox(c, 0.4f) { Text(n, fontSize = c.sz(0.016f), color = LabelInk) }
}

@Composable
fun MenuPanel(c: PhysicalLengthConverter, faceMe: () -> Unit, quit: () -> Unit, version: String) {
    @Suppress("UNUSED_VARIABLE") val v = Game.version
    if (!Game.menuOpen) {
        Pill(c, "菜单", Brass) { Game.menuOpen = true }
        return
    }
    PanelBox(c, 0.26f) {
        Pill(c, "回到长卷", Brass) { Game.menuOpen = false; Game.toHome() }
        Pill(c, "把画摆到我正前方") { faceMe() }
        Pill(c, if (Game.musicOn) "音乐：开" else "音乐：关") { Game.toggleMusic() }
        if (Game.confirmRestart) {
            Text("清除全部进度？", fontSize = c.sz(0.014f), color = LabelInk)
            Row(horizontalArrangement = Arrangement.spacedBy(c.m(0.006f))) {
                Pill(c, "确定清除", Color(0xFFE0604A)) { Game.confirmRestart = false; Game.menuOpen = false; Game.restart() }
                Pill(c, "不清除") { Game.confirmRestart = false }
            }
        } else Pill(c, "重新开始") { Game.confirmRestart = true }
        Pill(c, "退出") { quit() }
        Text("进度自动保存。版本 $version", fontSize = c.sz(0.012f), color = Dim)
        Pill(c, "收起菜单") { Game.menuOpen = false }
    }
}
