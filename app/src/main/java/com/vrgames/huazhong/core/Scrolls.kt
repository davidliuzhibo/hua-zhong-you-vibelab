package com.vrgames.huazhong.core

/** A region of a section's picture: u from its left edge, v from its top, both 0..1. */
data class Spot(val u0: Double, val v0: Double, val u1: Double, val v1: Double) {
    val u get() = (u0 + u1) / 2
    val v get() = (v0 + v1) / 2
}

/**
 * One thing to do in a section. [say] asks it, [done] is said once it is done (the knowledge it
 * leads to).
 */
sealed class Task(val say: String, val done: String) {
    /** Find [spot] in the picture: pinch it on the wall. A pinch elsewhere on the picture is a miss. */
    class Find(say: String, done: String, val spot: Spot, val label: String) : Task(say, done)

    /** [piece] has fallen out of the picture: carry it back to its place on the wall. */
    class Restore(say: String, done: String, val piece: Spot) : Task(say, done)

    /** Pick the right one of [options]. */
    class Quiz(say: String, done: String, val options: List<String>, val answer: Int) : Task(say, done)

    /** Work [tool] to and fro over its bowl for [meters] (grinding, stirring). */
    class Rub(say: String, done: String, val tool: String, val meters: Double = 0.3) : Task(say, done)

    /** Carry each dish to its part of the mountain: [pairs] dish → region. */
    class Match(say: String, done: String, val pairs: Map<String, String>) : Task(say, done)
}

/**
 * A section of a scroll: the picture is the source scan's columns [x0]..[x1] and rows [y0]..[y1]
 * (pixels), shown on the curved wall round the player. [clip] is its narration.
 */
class Section(
    val id: String,
    val scroll: Int,
    val number: Int,
    val title: String,
    val intro: String,
    val tasks: List<Task>,
    val x0: Int = 0,
    val x1: Int = 0,
    val y0: Int = 0,
    val y1: Int = 0,
    /** The pigment workshop (卷二): a bench, not a picture. */
    val bench: Boolean = false,
) {
    val clip get() = id
}

object Scrolls {
    val TITLES = listOf("清明上河图", "千里江山图")
    val PAINTERS = listOf("北宋 · 张择端", "北宋 · 王希孟")

    private fun s(u0: Double, v0: Double, u1: Double, v1: Double) = Spot(u0, v0, u1, v1)

    val QINGMING = listOf(
        Section(
            "qm1", 0, 1, "汴京郊野",
            "画卷从城外的郊野开始。早春时节，柳树才冒出新芽，一队毛驴驮着木炭，正往城里赶。",
            listOf(
                Task.Find("找一找：驮着木炭、正往城里走的毛驴队在哪里？捏一下它们。", "找到了！五头毛驴驮着木炭，赶驴的人跟在后面。冬天刚过，城里还要烧炭取暖。", SPOTS.getValue("qm1"), "驮炭的毛驴"),
                Task.Quiz("手卷应该从哪一头开始看？", "对！中国的手卷从右往左，一边展开一边看，就像跟着画里的人一路走进城。", listOf("从右往左", "从左往右", "从中间往两边"), 0),
            ),
            x0 = 31200, x1 = 37000,
        ),
        Section(
            "qm2", 0, 2, "汴河漕运",
            "汴河是北宋的命脉。江南的粮食装上大船，顺着汴河运进京城，这就是「漕运」。",
            listOf(
                Task.Find("找一找：靠在岸边、正在卸货的大货船在哪里？捏一下它。", "找到了！搬运工把一袋袋粮食从船上扛进岸上的货栈。", SPOTS.getValue("qm2"), "卸货的大船"),
                Task.Restore("画里有一艘篷船掉出来了：捏住它，放回河上原来的位置。", "放好了！这种篷船既能载客又能装货，是汴河上常见的小船。", PIECES.getValue("qm2")),
            ),
            x0 = 25200, x1 = 31200,
        ),
        Section(
            "qm3", 0, 3, "虹桥",
            "这就是全画最热闹的地方——虹桥。它是一座没有桥墩的木拱桥，像彩虹一样横跨汴河。",
            listOf(
                Task.Find("一艘大船就要撞上桥了！找到正在放倒桅杆、准备穿过桥洞的大船，捏一下它。", "找到了！船夫们手忙脚乱地放倒桅杆，桥上的人也探出身子喊话、扔绳子帮忙。", SPOTS.getValue("qm3"), "放桅杆的大船"),
                Task.Quiz("虹桥是用什么造的？", "对！虹桥用一根根木头交叉搭成拱，没有一根桥柱，大船能从桥下直接通过。", listOf("木头", "石头", "铁"), 0),
            ),
            x0 = 17400, x1 = 25200,
        ),
        Section(
            "qm4", 0, 4, "城外街道",
            "过了虹桥，离城越来越近。路上有骑马的、坐轿的、推车的，店铺一家挨着一家。",
            listOf(
                Task.Find("找一找：路上几头牛拉着的大车在哪里？捏一下它。", "找到了！宋代的大车靠牛拉，走得慢，却能装很多货。", SPOTS.getValue("qm4"), "牛拉的大车"),
                Task.Restore("路边一家酒店的彩楼掉下来了：捏住它，放回原来的位置。", "放好了！这是「十千脚店」门前用竹木扎的彩楼，叫「欢门」，是宋代酒店的招牌。", PIECES.getValue("qm4")),
            ),
            x0 = 11800, x1 = 17400,
        ),
        Section(
            "qm5", 0, 5, "城门",
            "高大的城楼出现了。一支骆驼队正慢慢穿过城门，驼铃叮当。",
            listOf(
                Task.Find("找一找：正在穿过城门的骆驼队在哪里？捏一下它。", "找到了！骆驼队从西北远道而来，带着货物进城做买卖。", SPOTS.getValue("qm5"), "骆驼队"),
                Task.Quiz("骆驼队最可能是从哪里来的？", "对！骆驼耐渴，是走沙漠商路的好帮手，丝绸之路上的商队常用它驮货。", listOf("西北的沙漠商路", "江南水乡", "东边的大海"), 0),
            ),
            x0 = 5200, x1 = 11800,
        ),
        Section(
            "qm6", 0, 6, "孙羊正店",
            "进了城，街市更热闹了：酒楼、香铺、医馆，还有说书的、看相的。最大的酒楼叫「孙羊正店」。",
            listOf(
                Task.Find("找一找：门前扎着高高彩楼的大酒楼「孙羊正店」在哪里？捏一下它。", "找到了！门口用竹木扎成的彩楼叫「欢门」，是宋代大酒楼的招牌。", SPOTS.getValue("qm6"), "孙羊正店"),
                Task.Quiz("宋朝的京城，晚上能不能出来逛街？", "对！北宋取消了宵禁，汴京的夜市一直开到三更，五更早市又开了。", listOf("能，夜市很热闹", "不能，晚上要关城门、禁止上街"), 0),
            ),
            x0 = 1300, x1 = 5200,
        ),
    )

    val QIANLI = listOf(
        Section(
            "ql1", 1, 1, "少年王希孟",
            "《千里江山图》长将近十二米，画它的王希孟只有十八岁。他在宫里的画院学画，宋徽宗亲自指点，他用了半年就画成了。",
            listOf(
                Task.Find("找一找：江边树丛里有一座小亭子，捏一下它。", "找到了！画里的人家、亭子、小桥都画得很小，更显得江山辽阔。", SPOTS.getValue("ql1"), "江边的亭子"),
                Task.Quiz("画这幅画的时候，王希孟多大？", "对，十八岁。画后有蔡京写的题跋：「希孟年十八岁」。这也是他留下的唯一一幅画。", listOf("十八岁", "三十八岁", "六十岁"), 0),
            ),
            x0 = 31500, x1 = 39300, y0 = 280, y1 = 1600,
        ),
        Section(
            "ql2", 1, 2, "长桥",
            "江上有一座长长的木桥，桥中间还有一座亭子。山是青的、绿的，这种画法叫「青绿山水」。",
            listOf(
                Task.Find("找一找：江上那座中间有亭子的长桥，捏一下它。", "找到了！这座长桥连着两岸，桥心的亭子可以歇脚、看江景。", SPOTS.getValue("ql2"), "长桥"),
                Task.Quiz("山上那些鲜亮的青色和绿色，是用什么画的？", "对！是磨碎的矿石：蓝铜矿磨成石青，孔雀石磨成石绿。矿物颜料不容易褪色，九百年后还这样鲜亮。", listOf("磨碎的矿石", "草叶的汁", "墨"), 0),
            ),
            x0 = 24500, x1 = 32300, y0 = 280, y1 = 1600,
        ),
        Section(
            "ql3", 1, 3, "山居",
            "山坳里藏着村舍、水磨和瀑布，有人在赶路，有人在打鱼。千里江山，处处有人家。",
            listOf(
                Task.Find("找一找：从山上挂下来的瀑布在哪里？捏一下它。", "找到了！瀑布从山间落下，汇进江里。", SPOTS.getValue("ql3"), "瀑布"),
                Task.Restore("一座青山掉出了画面：捏住它，放回原来的位置。", "放好了！山顶最浓的青色和山腰的绿色，层层叠叠，这就是青绿山水。", PIECES.getValue("ql3")),
            ),
            x0 = 12000, x1 = 19800, y0 = 280, y1 = 1600,
        ),
        Section(
            "ql4", 1, 4, "研色",
            "青绿山水的颜色要自己做：把矿石研成粉，用水漂出深浅不同的几层，再一层层画上去。",
            listOf(
                Task.Rub("先研石绿：捏住研杵，在研钵里来回研，把孔雀石研成细粉。", "研好了，这是石绿。", "pestle"),
                Task.Rub("再把研好的粉倒进清水碗里：捏住小棒在碗里来回搅一搅，让它慢慢沉下去。", "沉好了！粗的颗粒沉在最下面颜色最浓，细的浮在上面颜色最浅，分成三层。", "stick"),
                Task.Quiz("画山顶最浓的青色，要用哪一层？", "对！最下面的「头青」颗粒最粗、颜色最浓，用来压在山顶；上面的二青、三青颜色依次变浅。", listOf("最下面的头青", "中间的二青", "最上面的三青"), 0),
                Task.Match("最后给这座小山上色：把三碟颜料分别拖到山脚、山腰、山顶。", "画好了！赭石打底，石绿铺山腰，石青压山顶——这就是《千里江山图》的颜色。", mapOf("赭石" to "山脚", "石绿" to "山腰", "石青" to "山顶")),
            ),
            bench = true,
        ),
    )

    val ALL get() = QINGMING + QIANLI
    fun of(scroll: Int) = if (scroll == 0) QINGMING else QIANLI
    fun byId(id: String) = ALL.first { it.id == id }

    /** Where each section's thing to find is. */
    private val SPOTS: Map<String, Spot> get() = SPOT_TABLE
    private val PIECES: Map<String, Spot> get() = PIECE_TABLE
}

/** Read off the gridded previews of tools/make_sections.py (u across from the left, v down). */
internal val SPOT_TABLE = mapOf(
    "qm1" to Spot(0.86, 0.35, 0.98, 0.52),
    "qm2" to Spot(0.0, 0.33, 0.28, 0.6),
    "qm3" to Spot(0.26, 0.15, 0.42, 0.46),
    "qm4" to Spot(0.17, 0.6, 0.33, 0.96),
    "qm5" to Spot(0.08, 0.5, 0.2, 0.76),
    "qm6" to Spot(0.79, 0.04, 1.0, 0.6),
    "ql1" to Spot(0.47, 0.72, 0.53, 0.92),
    "ql2" to Spot(0.51, 0.68, 0.62, 0.86),
    "ql3" to Spot(0.455, 0.5, 0.52, 0.98),
)
internal val PIECE_TABLE = mapOf(
    "qm2" to Spot(0.34, 0.53, 0.5, 0.92),
    "qm4" to Spot(0.885, 0.18, 0.995, 0.74),
    "ql3" to Spot(0.12, 0.28, 0.2, 0.62),
)

/**
 * A visit to one section: the task reached and the misses. Stars: no miss three, up to two misses
 * two, more one; a hint counts as a miss.
 */
class Visit(val section: Section) {
    var at = 0; private set
    var misses = 0; private set
    var hints = 0; private set
    val task get() = section.tasks.getOrNull(at)
    val done get() = at >= section.tasks.size

    /** The rubbing so far (0..1) and the matched dishes, for [Task.Rub] and [Task.Match]. */
    var rubbed = 0.0; private set
    val matched = HashMap<String, String>()

    sealed interface Answer {
        data object Right : Answer
        data object Wrong : Answer
        /** Not a step of this task (no miss). */
        data object Ignored : Answer
    }

    fun find(spotHit: Boolean): Answer {
        if (task !is Task.Find) return Answer.Ignored
        return if (spotHit) next() else miss()
    }

    fun restore(nearHome: Boolean): Answer {
        if (task !is Task.Restore) return Answer.Ignored
        return if (nearHome) next() else Answer.Ignored
    }

    fun quiz(i: Int): Answer {
        val t = task as? Task.Quiz ?: return Answer.Ignored
        return if (i == t.answer) next() else miss()
    }

    /** Rubbing [meters] more; Right once it is enough. */
    fun rub(meters: Double): Answer {
        val t = task as? Task.Rub ?: return Answer.Ignored
        rubbed = (rubbed + meters / t.meters).coerceAtMost(1.0)
        return if (rubbed >= 1.0) next() else Answer.Ignored
    }

    /** Dish [dish] put on [region]: right or a miss; Right with all matched ends the task. */
    fun match(dish: String, region: String): Answer {
        val t = task as? Task.Match ?: return Answer.Ignored
        if (t.pairs[dish] != region) return miss()
        matched[dish] = region
        if (matched.size == t.pairs.size) next()
        return Answer.Right
    }

    fun hint(): String {
        hints++
        misses++
        return when (val t = task) {
            is Task.Find -> "提示：要找的${t.label}在画面的${where(t.spot)}。"
            is Task.Restore -> "提示：它原来在画面的${where(t.piece)}，那里有一块空白。"
            is Task.Quiz -> "提示：答案是「${t.options[t.answer]}」。"
            is Task.Rub -> "提示：捏住工具不放，在碗里来回拖动。"
            is Task.Match -> "提示：" + t.pairs.entries.joinToString("，") { "${it.key}画${it.value}" } + "。"
            null -> ""
        }
    }

    private fun next(): Answer {
        at++
        rubbed = 0.0
        matched.clear()
        return Answer.Right
    }

    private fun miss(): Answer {
        misses++
        return Answer.Wrong
    }

    fun stars() = if (!done) 0 else if (misses == 0) 3 else if (misses <= 2) 2 else 1

    companion object {
        fun where(s: Spot): String {
            val h = when { s.u < 0.34 -> "左边"; s.u > 0.66 -> "右边"; else -> "中间" }
            val v = when { s.v < 0.34 -> "上部"; s.v > 0.66 -> "下部"; else -> "中部" }
            return h + v
        }
    }
}

/** Best stars per section, and the trophies. */
class Progress {
    val best = LinkedHashMap<String, Int>()
    fun record(id: String, s: Int) { if (s > (best[id] ?: 0)) best[id] = s }
    fun done(id: String) = (best[id] ?: 0) > 0
    fun scrollDone(scroll: Int) = Scrolls.of(scroll).all { done(it.id) }
    val stars get() = Scrolls.ALL.sumOf { best[it.id] ?: 0 }
    val maxStars get() = Scrolls.ALL.size * 3
    /** Bronze: 卷一 seen through; silver: both scrolls; gold: every section with three stars. */
    val bronze get() = scrollDone(0)
    val silver get() = scrollDone(0) && scrollDone(1)
    val gold get() = silver && stars == maxStars

    /** Sections open in order within each scroll; both scrolls are open from the start. */
    fun open(s: Section) = Scrolls.of(s.scroll).let { list -> list.indexOf(s) <= list.indexOfFirst { !done(it.id) }.let { if (it < 0) list.size else it } }

    fun next(): Section? = Scrolls.QINGMING.firstOrNull { !done(it.id) } ?: Scrolls.QIANLI.firstOrNull { !done(it.id) }

    fun save() = best.entries.joinToString(",") { "${it.key}:${it.value}" }

    companion object {
        fun load(s: String?) = Progress().apply {
            s?.split(",")?.forEach { e -> e.split(":").takeIf { it.size == 2 }?.let { (k, v) -> if (Scrolls.ALL.any { it.id == k }) v.toIntOrNull()?.let { best[k] = it.coerceIn(0, 3) } } }
        }
    }
}
