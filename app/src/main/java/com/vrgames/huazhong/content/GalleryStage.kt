package com.vrgames.huazhong.content

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.event.SceneEvents
import com.pico.spatial.core.ecs.resource.TextureCreateOption
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.content.toColor4
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialPointerEvent
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.vrgames.huazhong.core.Scrolls
import com.vrgames.huazhong.core.Vec3
import com.vrgames.huazhong.game.AutoPilot
import com.vrgames.huazhong.game.Game
import com.vrgames.huazhong.render.GalleryView
import com.vrgames.huazhong.render.Lighting
import com.vrgames.huazhong.render.Music
import com.vrgames.huazhong.render.Sfx
import com.vrgames.huazhong.render.Sound
import com.vrgames.huazhong.render.setPos
import com.vrgames.huazhong.render.shared
import com.vrgames.huazhong.render.transform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The gallery (full immersion) round the seated player. The paintings stand where the player faced
 * once tracking settled, and never follow the head. A quick pinch finds things on the painting and
 * opens sections on the home wall; a pinch held and moved carries a loose piece of the painting back
 * to its gap, or works a tool on the pigment bench.
 */
@Composable
fun GalleryStage() {
    val context = LocalContext.current
    val converter = LocalPhysicalLengthConverter.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val head = remember { HMDTrackingProvider() }
    val subscription = remember { arrayOfNulls<Cancellable>(1) }
    var view by remember { mutableStateOf<GalleryView?>(null) }
    val seat = remember { Seat() }
    val panels = remember { HashMap<String, Entity>() }
    val drag = remember { DragState() }
    val strips = remember { HashMap<String, TextureResource>() }
    val appVersion = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }

    val letGo: (String) -> Unit = { why ->
        val g = drag.grip
        if (g != null) {
            drag.grip = null
            Log.i(INPUT_TAG, "let go $g ($why)")
            val v = view
            when {
                g == "piece" -> Game.pieceLetGo()
                g.startsWith("dish:") && v != null -> {
                    // Over a band of the little mountain: paint it; anywhere else, the dish goes back.
                    val at = v.gripAt(g)
                    val region = listOf("山顶", "山腰", "山脚").minByOrNull { at?.distanceTo(v.benchSpot(it)) ?: 9.0 }
                    if (at != null && region != null && at.distanceTo(v.benchSpot(region)) < DISH_NEAR) Game.paint(g.removePrefix("dish:"), region)
                    v.moveGrip(g, Vec3.ZERO)
                }
                v != null -> v.moveGrip(g, Vec3.ZERO)
            }
        }
    }

    DisposableEffect(Unit) {
        Game.init(context)
        val hd = runCatching { head.start() }
        Log.i(TAG, "head tracking ${hd.getOrNull() ?: hd.exceptionOrNull()?.message}")
        onDispose {
            runCatching { head.stop() }
            subscription[0]?.cancel()
            Game.stopVoice()
        }
    }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val music = Music(context).also {
            it.muted = !Game.musicOn
            Game.music = it
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> music.resume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> { music.pause(); Game.stopVoice() }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> runCatching { head.stop() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> runCatching { head.start() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            music.release()
            Game.music = null
        }
    }
    LaunchedEffect(Game.faceRequest) { if (Game.faceRequest > 0) seat.again() }
    LaunchedEffect(Game.noteVersion) {
        val n = Game.note ?: return@LaunchedEffect
        kotlinx.coroutines.delay(if (n.length > 40) 10000 else 6000)
        if (Game.note == n) Game.note = null
    }
    LaunchedEffect(Game.phase, Game.menuOpen, Game.note, view) { showPanels(panels) }
    // The wall: the home strips, a section's painting, or the bench.
    LaunchedEffect(Game.wallVersion, view) {
        val v = view ?: return@LaunchedEffect
        drag.grip = null
        when (Game.phase) {
            Game.Phase.HOME, Game.Phase.CARD -> {
                v.clearWall()
                v.showBench(false)
                for (n in listOf("strip_qm", "strip_ql")) if (n !in strips) {
                    withContext(Dispatchers.IO) { runCatching { TextureResource.load("sections/$n.jpg", LoadType.FROM_ASSETS, TextureCreateOption()).shared() } }
                        .onSuccess { strips[n] = it }.onFailure { Log.w(TAG, "strip $n: ${it.message}") }
                }
                val p = Game.progress
                val next = p.next()?.id
                val status = Scrolls.ALL.associate { s -> s.id to (if (p.done(s.id)) 2 else if (p.open(s)) 1 else 0) }
                v.home(strips, status, p.best, next)
                Log.i(TAG, "home shown")
            }
            Game.Phase.IN, Game.Phase.RESULT -> {
                val s = Game.section ?: return@LaunchedEffect
                v.hideHome()
                v.clearWall()
                if (s.bench) {
                    v.showBench(true)
                } else {
                    v.showBench(false)
                    val t = withContext(Dispatchers.IO) { runCatching { TextureResource.load("sections/${s.id}.jpg", LoadType.FROM_ASSETS, TextureCreateOption()) }.getOrNull() }
                    if (t == null) {
                        Log.w(TAG, "no picture for ${s.id}")
                        return@LaunchedEffect
                    }
                    if (Game.section !== s) return@LaunchedEffect
                    v.showSection(s, t)
                    v.lanternNight(Game.nightOn)
                }
                Log.i(TAG, "wall ready ${s.id}")
                Game.wallReady()
            }
        }
    }
    LaunchedEffect(Game.debugView, view) {
        val v = view ?: return@LaunchedEffect
        val d = Game.debugView?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }
        if (d != null && d.size >= 6) {
            seat.debug = true
            v.root.setPos(Vector3(d[0], d[1], d[2]))
            v.root.transform().eulerAngles = EulerAngles(d[5], d[3], 0f)
            v.root.transform().setScaleVector(Vector3(d[4]))
        } else {
            seat.debug = false
            v.root.transform().setScaleVector(Vector3(1f))
            seat.put()
        }
    }

    SpatialView(
        Modifier
            .pointerInput(0) {
                detectSpatialTapGesture(context, TargetEntity.any()) { value ->
                    val id = view?.hitOf(value.targetEntity) ?: return@detectSpatialTapGesture
                    main.post { tap(id) }
                }
            }
            .pointerInput(1) {
                fun meters(px: Float) = with(density) { converter.dpToLength(px.toDp(), LengthUnit.Meters) }.toDouble()
                detectSpatialDragGesture(
                    context = context,
                    targetedToEntity = TargetEntity.any(),
                    onDragStart = { _, value ->
                        val v = view ?: return@detectSpatialDragGesture
                        drag.last = SystemClock.uptimeMillis()
                        val id = v.hitOf(value.targetEntity) ?: return@detectSpatialDragGesture
                        Log.i(INPUT_TAG, "drag start $id")
                        if (Game.phase != Game.Phase.IN) return@detectSpatialDragGesture
                        if (id == "piece" || id == "pestle" || id == "stick" || id.startsWith("dish:")) {
                            drag.grip = id
                            drag.off = Vec3.ZERO
                            main.post { Game.sound?.play(Sfx.PICK) }
                        }
                    },
                    onDragEnd = { main.post { letGo("end") } },
                    onDragCancel = { main.post { letGo("cancel") } },
                ) { value ->
                    val g = drag.grip ?: return@detectSpatialDragGesture
                    drag.last = SystemClock.uptimeMillis()
                    val (dx, dz) = seat.toRoot(meters(value.dragAmount.x), meters(value.dragAmount.z))
                    val d = Vec3(dx, -meters(value.dragAmount.y), dz)
                    main.post { dragBy(g, d, drag) }
                }
            }
            .pointerInput(2) {
                detectSpatialPointerEvent(context, TargetEntity.any()) { infos ->
                    if (infos.any { it.isUpEvent() }) main.post { letGo("fingers up") }
                    false
                }
            },
        attachments = {
            // Each panel draws only in its own phase (the same fix as 青瓷 and 榫卯 on 2026-11-10).
            AttachmentPanel(PANEL_CARD) { if (panelOn(PANEL_CARD)) CardPanel(converter) }
            AttachmentPanel(PANEL_HOME) { if (panelOn(PANEL_HOME)) HomePanel(converter) }
            AttachmentPanel(PANEL_TASK) { if (panelOn(PANEL_TASK)) TaskPanel(converter) }
            AttachmentPanel(PANEL_RESULT) { if (panelOn(PANEL_RESULT)) ResultPanel(converter) }
            AttachmentPanel(PANEL_NOTE) { if (panelOn(PANEL_NOTE)) NotePanel(converter) }
            AttachmentPanel(PANEL_MENU) {
                MenuPanel(converter, faceMe = { Game.faceRequest++ }, quit = { context.findActivity()?.finishAndRemoveTask() }, version = appVersion)
            }
        },
        update = { _, attachments -> view?.let { attach(it, attachments, panels) } },
        initial = { content, attachments ->
            val sun = Entity().apply {
                components.set(DirectionalLightComponent(Color(0xFFFFEBD0).toColor4(), 700f, false))
                components[TransformComponent::class.java]?.setEulerAngles(EulerAngles(-50f, 20f, 0f))
            }
            content.addEntity(sun)
            val lighting = Lighting()
            content.addEntity(lighting.source)
            withContext(Dispatchers.IO) { lighting.load() }
            lighting.apply()
            for (name in PANEL_FRAMES.values.toSet()) runCatching { Entity.loadSuspend("asset://panels/$name.glb") }.onSuccess { frames[name] = it }
            val v = GalleryView()
            runCatching { Entity.loadSuspend("asset://models/mortar.glb") }.onSuccess { v.useMortar(it) }.onFailure { Log.i(TAG, "mortar: ${it.message}") }
            seat.root = v.root
            content.addEntity(v.root)
            v.root.enabled = false
            attach(v, attachments, panels)
            lighting.receive(v.root, panels.values.toSet())
            Game.view = v
            Game.sound = Sound(v.root).also { it.load() }
            v.trophies(Game.trophies)
            view = v
            AutoPilot.ready()
            subscription[0] = content.subscribe(SceneEvents.Update::class.java) { e ->
                val dt = e.deltaTime.toDouble().coerceIn(0.0, 0.1)
                // Every frame: only the panels of the phase shown, even ones handed over since the phase changed.
                main.post { showPanels(panels) }
                val pose = runCatching { if (head.state == DataProvider.State.STARTED) head.latestData?.hmdPose else null }.getOrNull()
                if (seat.frame(pose?.position, pose?.rotation, dt)) {
                    v.eyeHeight = seat.eyeY
                    v.floor()
                    lighting.receive(v.root, panels.values.toSet())
                    v.root.enabled = true
                    // The panels were hung on the gallery while it was still hidden (until tracking settles), and the
                    // system never showed them once it was shown: every panel stayed empty, only its 3D frame showed
                    // (device and emulator, 2026-11-11). Hung again now that it is shown, they draw.
                    for (e in panels.values) {
                        val at = e.transform().position
                        val turn = e.transform().eulerAngles
                        v.root.removeChild(e)
                        v.root.addChild(e)
                        e.setPos(at)
                        e.transform().eulerAngles = turn
                    }
                    main.post { Game.changed() }
                }
                if (drag.grip != null && SystemClock.uptimeMillis() - drag.last > 2500) main.post { letGo("watchdog") }
                v.frame(dt)
            }
        },
    )
}

/** A quick pinch on [id]. */
private fun tap(id: String) {
    Log.i(INPUT_TAG, "tap $id")
    when {
        id.startsWith("home:") -> if (Game.phase == Game.Phase.HOME) Scrolls.ALL.firstOrNull { it.id == id.removePrefix("home:") }?.let { Game.open(it) }
        id == "spot" -> Game.pinchWall(true)
        id == "wall" -> Game.pinchWall(false)
        id == "piece" -> Game.say("捏住画片不放，拖到墙上那块空白的位置再松手。")
        id == "pestle" || id == "stick" -> Game.say("捏住它不放，在碗里来回拖动。")
        id.startsWith("dish:") -> Game.say("捏住这碟颜料不放，拖到小山上要画的那一层再松手。")
    }
}

/** The held thing moved by [d] (root frame). */
private fun dragBy(g: String, d: Vec3, drag: DragState) {
    val v = Game.view ?: return
    if (g == "piece") {
        val p = v.piecePos() ?: return
        v.movePiece(p + d)
        return
    }
    val off = drag.off + d
    drag.off = Vec3(off.x.coerceIn(-0.4, 0.6), off.y.coerceIn(-0.05, 0.4), off.z.coerceIn(-0.3, 0.25))
    v.moveGrip(g, drag.off)
    if (g == "pestle" || g == "stick") {
        val at = v.gripAt(g) ?: return
        if (at.distanceTo(v.benchSpot(g)) < RUB_NEAR) Game.rub(g, d.length(), at)
    }
}

private class DragState {
    var grip: String? = null
    var off = Vec3.ZERO
    var last = 0L
}

/** Whether panel [id] is shown now. */
private fun panelOn(id: String): Boolean = when (id) {
    PANEL_CARD -> Game.phase == Game.Phase.CARD
    PANEL_HOME -> Game.phase == Game.Phase.HOME
    PANEL_TASK -> Game.phase == Game.Phase.IN
    PANEL_RESULT -> Game.phase == Game.Phase.RESULT
    PANEL_NOTE -> Game.note != null
    else -> true
}

private fun showPanels(panels: HashMap<String, Entity>) {
    for ((id, e) in panels) {
        val on = panelOn(id)
        if (e.enabled != on) e.enabled = on
    }
}

private val PANEL_FRAMES = mapOf(PANEL_CARD to "panel_title", PANEL_RESULT to "panel_title", PANEL_HOME to "panel_page", PANEL_TASK to "panel_scroll")
private val frames = HashMap<String, Entity>()

private fun attach(v: GalleryView, a: SpatialViewAttachments, panels: HashMap<String, Entity>) {
    fun put(id: String, pos: Vector3, pitch: Float, yaw: Float = 0f) {
        val e = a.entity(id) ?: return
        val was = panels[id]
        if (was === e) return
        // The attachments can hand back a new entity for the same panel: the old one must go. Left in place (with its
        // frame) and no longer shown or hidden by anything, it stood empty in front of the new one: the device showed
        // three blank frames before the player and nothing could be done (2026-11-11).
        was?.let { it.enabled = false; it.getParent()?.removeChild(it) }
        panels[id] = e
        v.root.addChild(e)
        e.setPos(pos)
        PANEL_FRAMES[id]?.let { name -> frames[name]?.clone(Entity.CloneOptions(true, true))?.let { f -> f.setPos(Vector3(0f, 0f, -0.006f)); e.addChild(f) } }
        e.transform().eulerAngles = EulerAngles(pitch, yaw, 0f)
    }
    // The eye frame: the title and result before the player, a little low; the task scroll at the
    // left, turned in; notes at the right; the menu tab low at the left.
    put(PANEL_CARD, Vector3(0f, -0.26f, -0.85f), -12f)
    put(PANEL_RESULT, Vector3(0f, -0.24f, -0.82f), -12f)
    put(PANEL_HOME, Vector3(0f, -0.5f, -0.78f), -30f)
    put(PANEL_TASK, Vector3(-0.6f, -0.26f, -0.55f), -18f, 45f)
    put(PANEL_NOTE, Vector3(0.58f, -0.24f, -0.58f), -15f, -45f)
    put(PANEL_MENU, Vector3(-0.6f, -0.58f, -0.4f), -35f, 50f)
}

/**
 * Where the player sits: once tracking has settled for a second, the gallery is put at the eyes,
 * facing the mean heading of that second, and stays there (it never follows the head); the menu's
 * 摆到正前方 does it again.
 */
private class Seat {
    var root: Entity? = null
    var debug = false
    var facing = 0f; private set
    var eyeY = 1.2f; private set
    private var at = Vector3(0f, 1.2f, 0f)
    private var placed = false
    private val headings = ArrayList<Float>()
    private var since = 0.0
    private var clock = 0.0

    fun again() {
        placed = false
        headings.clear()
    }

    /** Returns true on the frame the gallery is first put in place (or put again). */
    fun frame(eye: Vector3?, rot: Quat?, dt: Double): Boolean {
        clock += dt
        if (placed || eye == null || rot == null) return false
        val f = rot.rotateVector(Vector3(0f, 0f, -1f))
        if (hypot(f.x, f.z) < 0.2f) return false
        val yaw = Math.toDegrees(atan2(-f.x, -f.z).toDouble()).toFloat()
        // Skip a first reading that differs a lot (tracking settling); then a second of steady readings.
        if (headings.isNotEmpty() && kotlin.math.abs(yaw - headings.last()) > 3f) headings.clear()
        if (headings.isEmpty()) since = clock
        headings += yaw
        if (clock - since < 1.0) return false
        val sn = headings.sumOf { sin(Math.toRadians(it.toDouble())) }
        val cs = headings.sumOf { cos(Math.toRadians(it.toDouble())) }
        facing = Math.toDegrees(atan2(sn, cs)).toFloat()
        at = eye
        eyeY = eye.y.coerceIn(0.9f, 1.8f)
        placed = true
        Log.i(TAG, "placed: facing $facing eye $eye")
        put()
        return true
    }

    fun put() {
        if (debug) return
        val v = root ?: return
        v.setPos(at)
        v.transform().eulerAngles = EulerAngles(0f, facing, 0f)
    }

    /** A hand movement in the room as (x, z) in the gallery's frame. */
    fun toRoot(dx: Double, dz: Double): Pair<Double, Double> {
        val a = Math.toRadians(-facing.toDouble())
        return (dx * cos(a) + dz * sin(a)) to (-dx * sin(a) + dz * cos(a))
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

private const val RUB_NEAR = 0.1
private const val DISH_NEAR = 0.12
private const val TAG = "GalleryStage"
/** Every pinch the stage sees (kept in release builds, for on-device reports). */
private const val INPUT_TAG = "GalleryInput"
