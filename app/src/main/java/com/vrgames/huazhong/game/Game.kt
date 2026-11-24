package com.vrgames.huazhong.game

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vrgames.huazhong.core.Progress
import com.vrgames.huazhong.core.Scrolls
import com.vrgames.huazhong.core.Section
import com.vrgames.huazhong.core.Task
import com.vrgames.huazhong.core.Vec3
import com.vrgames.huazhong.core.Visit
import com.vrgames.huazhong.render.GalleryView
import com.vrgames.huazhong.render.Music
import com.vrgames.huazhong.render.Sfx
import com.vrgames.huazhong.render.Sound

/**
 * State the stage and its panels share. CARD is the title; HOME the wall with both scrolls; IN a
 * section, its tasks one after another; RESULT its stars. Moves come from pinches on the wall, the
 * floating piece, the bench's tools and the panels' buttons.
 */
object Game {
    private const val PREFS = "huazhong"
    private const val TAG = "HuaGame"

    enum class Phase { CARD, HOME, IN, RESULT }

    private val main = Handler(Looper.getMainLooper())
    private var prefs: android.content.SharedPreferences? = null
    private var app: Context? = null
    var progress = Progress(); private set
    var phase by mutableStateOf(Phase.CARD)
    var section by mutableStateOf<Section?>(null); private set
    var visit: Visit? = null; private set
    var version by mutableIntStateOf(0); private set
    /** Bumped when the stage must load a section's picture (or the home strips). */
    var wallVersion by mutableIntStateOf(0); private set
    var note by mutableStateOf<String?>(null)
    var noteVersion by mutableIntStateOf(0); private set
    var lastDone by mutableStateOf<String?>(null)
    var menuOpen by mutableStateOf(false)
    var confirmRestart by mutableStateOf(false)
    var musicOn by mutableStateOf(true); private set
    var trophiesBefore = 0
    var nightOn by mutableStateOf(false); private set
    var music: Music? = null
    var sound: Sound? = null
    var view: GalleryView? = null
    var world: com.vrgames.huazhong.render.WorldView? = null
    /** The menu's 「退出」 (set by the stage): closes the app's task. */
    var quit: (() -> Unit)? = null
    /** Inside the painting (入画, v0.2): the section's world is round the player and its tasks are done there. */
    var inside by mutableStateOf(false); private set
    /**
     * v0.3 (2026-11-18): while [inside], the world is either the miniature on the table before the player (the tasks are
     * done there) or, after 「走进画里」, life size round them ([full]).
     */
    var full by mutableStateOf(false); private set
    private var worldTasks = false
    /** Bumped to make the stage go in or out of the painting. */
    var worldVersion by mutableIntStateOf(0); private set
    /** Whether section [s] can be stepped into. */
    fun hasWorld(s: Section?) = s != null && !s.bench && com.vrgames.huazhong.core.Worlds.of(s.id) != null

    fun init(context: Context) {
        app = context.applicationContext
        if (prefs != null) return
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { p ->
            musicOn = p.getBoolean("music", true)
            progress = Progress.load(p.getString("progress", null))
        }
        AutoPilot.register(context)
    }

    val trophies get() = progress.let { if (it.gold) 3 else if (it.silver) 2 else if (it.bronze) 1 else 0 }

    fun say(text: String?) {
        note = text
        noteVersion++
    }

    fun changed() {
        version++
    }

    // ---- Narration: one clip per section, and the title's ----
    private var player: android.media.MediaPlayer? = null
    var speaking by mutableStateOf(false); private set

    fun speak(clip: String) {
        stopVoice()
        val a = app ?: return
        runCatching {
            val fd = a.assets.openFd("narration/$clip.ogg")
            player = android.media.MediaPlayer().apply {
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                fd.close()
                setOnCompletionListener { stopVoice() }
                prepare()
                start()
            }
            speaking = true
            music?.duck(true)
        }.onFailure { Log.w(TAG, "narration $clip: ${it.message}") }
    }

    fun toggleVoice() = if (speaking) stopVoice() else section?.let { speak(it.clip) } ?: speak("intro")

    fun stopVoice() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        if (speaking) {
            speaking = false
            music?.duck(false)
        }
    }

    // ---- Flow ----

    fun toHome() {
        stopVoice()
        leaveWorld()
        phase = Phase.HOME
        section = null
        visit = null
        nightOn = false
        wallVersion++
        version++
    }

    fun toCard() {
        stopVoice()
        phase = Phase.CARD
        version++
    }

    /** Opens section [s] (if open): its picture goes up, its story is told, its first task asked. */
    fun open(s: Section) {
        if (!progress.open(s)) {
            say("先游完这一卷前面的段落。")
            sound?.play(Sfx.WRONG)
            return
        }
        stopVoice()
        leaveWorld()
        section = s
        visit = Visit(s)
        lastDone = null
        nightOn = false
        phase = Phase.IN
        wallVersion++
        sound?.play(Sfx.OPEN)
        main.postDelayed({ if (section === s && phase == Phase.IN) speak(s.clip) }, 900)
        Log.i(TAG, "open ${s.id}")
        version++
    }

    fun next() = progress.next()?.let { open(it) } ?: run { toHome(); say("两卷都游完了！在长卷上可以重游任何一段，把星星拿满。") }

    fun again() = section?.let { open(it) }

    /** Called by the stage once the section's picture is on the wall: sets up the task (or asks to step in). */
    fun wallReady() {
        val s = section ?: return
        if (hasWorld(s) && !inside) {
            // v0.3: the painting's world comes up at once as a miniature on the table (上元夜游 straight in at life size).
            enter()
            return
        }
        setUpTask()
    }

    /** 入画: step into the section's painting. */
    fun enter() {
        val s = section ?: return
        if (phase != Phase.IN || inside || !hasWorld(s)) return
        inside = true
        full = nightOn
        worldTasks = false
        sound?.play(Sfx.OPEN)
        say(if (nightOn) "入画中……" else "画里的景物正在桌上铺开……")
        worldVersion++
        version++
        Log.i(TAG, "enter ${s.id}")
    }

    /** The stage has built (or re-placed) the world: the tasks begin, the first time. */
    fun worldReady() {
        if (!inside) return
        world?.let { w -> section?.let { s -> ambient(com.vrgames.huazhong.core.Worlds.of(s.id)?.sound?.let { if (nightOn) "night" else it }) } }
        if (!worldTasks) {
            worldTasks = true
            setUpTask()
        }
    }

    /** The stage has put the world at life size (after its sky came in, the first time). */
    fun walkedIn() {
        if (full) say("走进画里了：转头看看四周。任务也可以在这里做；「回到小景」回到桌上。")
    }

    /** 「走进画里」 / 「回到小景」: life size round the player, or back to the miniature on the table. */
    fun walkIn(on: Boolean = !full) {
        if (!inside || full == on) return
        full = on
        sound?.play(Sfx.OPEN)
        say(if (on) "正在走进画里……（第一次要等十几秒，远处的山水正在铺开）" else "回到桌上的小景。")
        worldVersion++
        version++
        Log.i(TAG, "walk in $on")
    }

    /** Out of the painting (leaving the section). */
    private fun leaveWorld() {
        if (!inside) return
        inside = false
        full = false
        ambient(null)
        worldVersion++
    }

    // ---- The world's ambient sound (river, street, wind...): a loop while inside ----
    private var amb: android.media.MediaPlayer? = null

    private fun ambient(name: String?) {
        amb?.let { runCatching { it.stop() }; runCatching { it.release() } }
        amb = null
        val a = app ?: return
        name ?: return
        runCatching {
            val fd = a.assets.openFd("audio/amb_$name.ogg")
            amb = android.media.MediaPlayer().apply {
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                fd.close()
                isLooping = true
                setVolume(0.55f, 0.55f)
                prepare()
                start()
            }
        }.onFailure { Log.i(TAG, "ambient $name: ${it.message}") }
    }

    fun pauseAmbient(on: Boolean) = amb?.let { runCatching { if (on) it.pause() else it.start() } }

    private fun setUpTask() {
        val v = visit ?: return
        val view = view ?: return
        when (val t = v.task) {
            is Task.Find -> if (!inside) view.findBox(t.spot)
            is Task.Restore -> if (inside) world?.loosePiece() else view.loosePiece(t.piece)
            else -> Unit
        }
        view.showBench(section?.bench == true)
        say(v.task?.say)
        version++
    }

    private fun answered(a: Visit.Answer, t: Task) {
        val v = visit ?: return
        when (a) {
            Visit.Answer.Right -> {
                sound?.play(Sfx.RIGHT)
                lastDone = t.done
                if (t is Task.Find) { if (inside) world?.found(t.label) else view?.popOut(t.spot, t.label) }
                if (t is Task.Restore) { if (inside) world?.restored() else view?.restored() }
                if (v.done) finish() else main.postDelayed({ if (visit === v) setUpTask() }, if (t is Task.Find) 1800 else 600)
            }
            Visit.Answer.Wrong -> {
                sound?.play(Sfx.WRONG)
                say(
                    when (t) {
                        is Task.Find -> "不是这里，再仔细看看。（${t.label}）"
                        is Task.Quiz -> "不对，再想想。"
                        is Task.Match -> "这碟颜色不该画在这里。想想《千里江山图》的山：山脚、山腰、山顶各是什么颜色？"
                        else -> "再试试。"
                    },
                )
            }
            Visit.Answer.Ignored -> Unit
        }
        version++
    }

    // ---- The player's moves ----

    /** A pinch on the wall: on the thing to find ([onSpot]) or elsewhere on the painting. */
    fun pinchWall(onSpot: Boolean, at: Vec3? = null) {
        val v = visit ?: return
        if (phase != Phase.IN) return
        // On the painting, outside it: every pinch on it steps in.
        if (!inside && hasWorld(section)) { enter(); return }
        val t = v.task as? Task.Find ?: return
        val a = v.find(onSpot)
        if (a == Visit.Answer.Wrong) view?.wrongAt(at)
        answered(a, t)
    }

    /** The piece let go: back in the painting if it covers its gap. */
    fun pieceLetGo() {
        val v = visit ?: return
        val t = v.task as? Task.Restore ?: return
        val ok = if (inside) world?.pieceAimed() == true else view?.pieceOnHome() == true
        if (!ok && inside && !full) {
            say("还没放到位：把它拿到桌上小景里发光的位置，放下再松手。")
            sound?.play(Sfx.PICK)
            return
        }
        if (!ok && inside) {
            say("还没对准：从你眼前看过去，把小模型移到远处发光的位置上再松手。")
            sound?.play(Sfx.PICK)
            return
        }
        if (!ok) {
            say("还没对准：把它拿到墙上那块空白的位置，对齐了再松手。")
            sound?.play(Sfx.PICK)
            return
        }
        answered(v.restore(true), t)
    }

    fun answer(i: Int) {
        val v = visit ?: return
        val t = v.task as? Task.Quiz ?: return
        answered(v.quiz(i), t)
    }

    /** The tool [id] on the bench moved [meters] near its bowl. */
    fun rub(id: String, meters: Double, at: Vec3?) {
        val v = visit ?: return
        val t = v.task as? Task.Rub ?: return
        if (t.tool != id) return
        val before = v.rubbed
        val a = v.rub(meters)
        if (id == "pestle") view?.ground(v.rubbed.coerceAtMost(1.0), at)
        if ((before * 5).toInt() != (v.rubbed * 5).toInt()) sound?.play(if (id == "pestle") Sfx.GRIND else Sfx.SPLASH)
        if (a == Visit.Answer.Right && id == "stick") view?.settled()
        answered(a, t)
        version++
    }

    /** Dish [dish] let go over [region] of the little mountain. */
    fun paint(dish: String, region: String) {
        val v = visit ?: return
        val t = v.task as? Task.Match ?: return
        val a = v.match(dish, region)
        if (a == Visit.Answer.Right) {
            view?.painted(dish, region)
            sound?.play(Sfx.PAINT)
            if (v.task === t) { say("画好了「$region」。再拖下一碟。"); version++; return }
        }
        answered(a, t)
    }

    fun hint() {
        val v = visit ?: return
        if (phase != Phase.IN) return
        say(v.hint())
        when (val t = v.task) {
            is Task.Find -> if (inside) world?.hintFind() else view?.hintAt(t.spot)
            is Task.Restore -> if (inside) world?.hintPiece() else view?.hintAt(t.piece)
            else -> Unit
        }
        version++
    }

    private fun finish() {
        val v = visit ?: return
        val s = section ?: return
        trophiesBefore = trophies
        progress.record(s.id, v.stars())
        save()
        view?.trophies(trophies)
        sound?.play(Sfx.DONE)
        Log.i(TAG, "done ${s.id} stars=${v.stars()} misses=${v.misses} hints=${v.hints}")
        main.postDelayed({
            if (visit === v) {
                phase = Phase.RESULT
                if (trophies > trophiesBefore) sound?.play(Sfx.SEAL)
                version++
            }
        }, 2500)
        version++
    }

    /** The gold reward: the Rainbow Bridge at night (opens 虹桥 and lights the lanterns). */
    fun lanternNight() {
        if (!progress.gold) {
            say("拿到金奖杯（每一段都三星），就能看上元夜的虹桥灯会。")
            return
        }
        open(Scrolls.byId("qm3"))
        nightOn = true
        version++
    }

    private fun save() {
        prefs?.edit()?.putString("progress", progress.save())?.apply()
    }

    fun restart() {
        progress = Progress()
        save()
        view?.trophies(0)
        toCard()
    }

    fun toggleMusic() {
        musicOn = !musicOn
        music?.muted = !musicOn
        prefs?.edit()?.putBoolean("music", musicOn)?.apply()
    }

    /** Debug: every section seen at [stars]. */
    fun grant(stars: Int) {
        for (s in Scrolls.ALL) progress.best[s.id] = stars
        save()
        view?.trophies(trophies)
        wallVersion++
        version++
    }

    // Debug hooks (AutoPilot).
    var debugOpen by mutableStateOf<String?>(null)
    var debugView by mutableStateOf<String?>(null)
    /** Debug only (store shots): every panel hidden, so the painting's world is seen on its own. */
    var debugClean by mutableStateOf(false)
    var faceRequest by mutableIntStateOf(0)
}
