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
import com.vrgames.huazhong.core.Task
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
    /** The painted rooms round the gallery (v0.2): the study, and the painting academy round the pigment bench. */
    val rooms = remember { HashMap<String, TextureResource>() }
    var worldView by remember { mutableStateOf<com.vrgames.huazhong.render.WorldView?>(null) }
    val lightingRef = remember { arrayOfNulls<Lighting>(1) }
    // The walk-in worlds' own light (v0.3), apart from the gallery's room light.
    val worldLight = remember { Lighting() }
    val appVersion = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }

    val letGo: (String) -> Unit = { why ->
        val g = drag.grip
        if (g != null) {
            drag.grip = null
            Log.i(INPUT_TAG, "let go $g ($why)")
            val v = view
            when {
                g == "piece" || g == "wpiece" -> Game.pieceLetGo()
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
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> { music.resume(); Game.pauseAmbient(false) }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> { music.pause(); Game.stopVoice(); Game.pauseAmbient(true) }
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
        // The room round it: the painting academy at the pigment bench, the study everywhere else.
        val room = if (Game.section?.bench == true) com.vrgames.huazhong.core.Worlds.STUDIO else com.vrgames.huazhong.core.Worlds.HALL
        val rt = rooms[room] ?: withContext(Dispatchers.IO) {
            runCatching { context.assets.open("scenes/pano_$room.jpg").use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
        }?.let { TextureResource.create(it).shared() }?.also { rooms[room] = it }
        // v0.3: and its sharp horizon band (two halves), as in the walk-in worlds.
        val bands = (0..1).map { h ->
            rooms["$room$h"] ?: withContext(Dispatchers.IO) {
                runCatching { context.assets.open("scenes/band_${room}_${if (h == 0) "a" else "b"}.jpg").use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
            }?.let { TextureResource.create(it).shared() }?.also { rooms["$room$h"] = it }
        }
        if (rt != null) v.setBackdrop(room, rt, bands.filterNotNull().takeIf { it.size == 2 })
        when (Game.phase) {
            Game.Phase.HOME, Game.Phase.CARD -> {
                v.clearWall()
                v.showBench(false)
                if (!Game.inside) setPlacement(BASE_PLACE, v, panels)
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
                    // v0.5.2: the panels clear of the bench's things (the result panel stood behind the little mountain).
                    if (!Game.inside) setPlacement(BENCH_PLACE, v, panels)
                } else {
                    v.showBench(false)
                    if (!Game.inside) setPlacement(BASE_PLACE, v, panels)
                    val t = withContext(Dispatchers.IO) { runCatching { TextureResource.load("sections/${s.id}.jpg", LoadType.FROM_ASSETS, TextureCreateOption()) }.getOrNull() }
                    if (t == null) {
                        Log.w(TAG, "no picture for ${s.id}")
                        return@LaunchedEffect
                    }
                    if (Game.section !== s) return@LaunchedEffect
                    v.showSection(s, t, false)
                    v.lanternNight(Game.nightOn)
                }
                Log.i(TAG, "wall ready ${s.id}")
                Game.wallReady()
            }
        }
    }
    // In and out of the painting (入画): the world is built for the section, shown, and the tasks begin.
    LaunchedEffect(Game.worldVersion, worldView) {
        val w = worldView ?: return@LaunchedEffect
        val v = view ?: return@LaunchedEffect
        drag.grip = null
        val s = Game.section
        if (Game.inside && s != null) {
            val world = com.vrgames.huazhong.core.Worlds.of(s.id) ?: return@LaunchedEffect
            if (w.world !== world || w.night != Game.nightOn || !w.built) {
                w.eyeHeight = seat.eyeY
                val lightName = if (Game.nightOn && world.id == "qm3") "qm3n" else world.pano
                withContext(Dispatchers.IO) { worldLight.loadWorld(lightName) }
                worldLight.applyWorld(lightName, WORLD_LIGHT)
                w.build(world, Game.nightOn)
                worldLight.receive(w.root)
                worldLight.receive(w.near)
            }
            if (!Game.inside || Game.section !== s) return@LaunchedEffect
            // v0.3: the miniature on the table (the gallery stays round it), or life size (the gallery put away). The
            // world's sky is loaded on the first walk in.
            if (Game.full) w.loadSky()
            if (!Game.inside || Game.section !== s) return@LaunchedEffect
            w.place(Game.full, seat.eyeY)
            // v0.5.1 (emulator 2026-11-27: hard dark shadows lay across the life-size streets, still there a minute
            // later): a new light component set over the old one did not switch its shadows off. The light's own switch.
            sunRef?.let { e ->
                val l = e.components[DirectionalLightComponent::class.java]
                if (l != null) runCatching { l.castsShadowEnabled = !Game.full }
                else e.components.set(DirectionalLightComponent(Color(0xFFFFEBD0).toColor4(), 700f, !Game.full))
                Log.i(TAG, "sun shadows ${e.components[DirectionalLightComponent::class.java]?.castsShadowEnabled} (full=${Game.full})")
            }
            v.inside(Game.full)
            // v0.5.2 (device 2026-11-28: a miniature bridge through 虹桥's result panel): with the miniature on the table the
            // panels stand where nothing of it is in their way (core/MiniPanels, worked out once per world); life size,
            // in their usual places.
            val place = if (Game.full) BASE_PLACE else withContext(Dispatchers.Default) { miniPlaceOf(world, w) }
            setPlacement(place, v, panels)
            // show() re-applies what each mode hides (hiding under a hidden root did not hold).
            w.show(true)
            Game.worldReady()
            Game.walkedIn()
            w.logPerf("shown")
            // The walkers' legs: their walk cycles come in now, a model at a time (not during the build: ANR).
            w.loadWalks()
            if (Game.inside && Game.section === s) w.logPerf("walks in")
        } else {
            w.show(false)
            w.clear()
            v.inside(false)
            setPlacement(if (s?.bench == true && Game.phase != Game.Phase.HOME && Game.phase != Game.Phase.CARD) BENCH_PLACE else BASE_PLACE, v, panels)
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
                    // Inside a painting its own things first (the spot to find, everything else, the fallen piece).
                    val wid = if (Game.inside) worldView?.hitOf(value.targetEntity) else null
                    if (wid != null) { main.post { tapWorld(wid) }; return@detectSpatialTapGesture }
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
                        if (Game.inside && worldView?.hitOf(value.targetEntity) == "piece" && Game.phase == Game.Phase.IN) {
                            drag.grip = "wpiece"
                            drag.off = Vec3.ZERO
                            main.post { Game.sound?.play(Sfx.PICK) }
                            return@detectSpatialDragGesture
                        }
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
                // The menu's 「退出」, also what the test hook's `quit` presses (v0.4.0).
                Game.quit = { context.findActivity()?.finishAndRemoveTask() }
                MenuPanel(converter, faceMe = { Game.faceRequest++ }, quit = { Game.quit?.invoke() }, version = appVersion)
            }
        },
        update = { _, attachments -> view?.let { attach(it, attachments, panels) } },
        initial = { content, attachments ->
            val sun = Entity().apply {
                // v0.3: casting shadows, so the miniature's houses, trees and walkers stand on their board. v0.3.1: only in
                // the miniature (light models there); at life size the shadow pass over the full models cost too much.
                components.set(DirectionalLightComponent(Color(0xFFFFEBD0).toColor4(), 700f, true))
                sunRef = this
                components[TransformComponent::class.java]?.setEulerAngles(EulerAngles(-50f, 20f, 0f))
            }
            content.addEntity(sun)
            val lighting = Lighting()
            content.addEntity(lighting.source)
            content.addEntity(worldLight.source)
            withContext(Dispatchers.IO) { lighting.load() }
            lighting.apply()
            for (name in PANEL_FRAMES.values.toSet()) runCatching { Entity.loadSuspend("asset://panels/$name.glb") }.onSuccess { frames[name] = it }
            val v = GalleryView()
            lightingRef[0] = lighting
            val wv = com.vrgames.huazhong.render.WorldView(context, com.vrgames.huazhong.render.WorldView.loadSizes(context))
            v.root.addChild(wv.root)
            v.root.addChild(wv.near)
            Game.world = wv
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
            worldView = wv
            AutoPilot.ready()
            subscription[0] = content.subscribe(SceneEvents.Update::class.java) { e ->
                val dt = e.deltaTime.toDouble().coerceIn(0.0, 0.1)
                // v0.4.0: the frame rate on the device, every 5 s (HuaPerf), with what the world draws.
                com.vrgames.huazhong.render.PerfLog.frame(e.deltaTime.toDouble()) {
                    if (Game.inside && wv.built) "${wv.world?.id} ${if (wv.mini) "mini" else "full"} drawn ${wv.drawn()}" else "gallery"
                }
                // Every frame: only the panels of the phase shown, even ones handed over since the phase changed.
                main.post { showPanels(panels) }
                val pose = runCatching { if (head.state == DataProvider.State.STARTED) head.latestData?.hmdPose else null }.getOrNull()
                if (seat.frame(pose?.position, pose?.rotation, dt)) {
                    v.eyeHeight = seat.eyeY
                    wv.eyeHeight = seat.eyeY
                    wv.root.setPos(Vector3(0f, -seat.eyeY, 0f))
                    v.floor()
                    v.inside(Game.inside)
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
                // Pinch boxes in the world only while a task there needs them (v0.3.3).
                val t = Game.visit?.task
                wv.setPickable(Game.phase == Game.Phase.IN && (t is Task.Find || t is Task.Restore))
                wv.frame(dt)
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
        // With the miniature on the table the painting on the wall is only to look at (v0.3).
        id == "wall" -> if (!Game.inside) Game.pinchWall(false)
        id == "piece" -> Game.say("捏住画片不放，拖到墙上那块空白的位置再松手。")
        id == "pestle" || id == "stick" -> Game.say("捏住它不放，在碗里来回拖动。")
        id.startsWith("dish:") -> Game.say("捏住这碟颜料不放，拖到小山上要画的那一层再松手。")
    }
}

/** A quick pinch inside a painting. */
private fun tapWorld(id: String) {
    Log.i(INPUT_TAG, "tap world $id")
    when (id) {
        "spot" -> Game.pinchWall(true)
        "scene" -> Game.pinchWall(false)
        "piece" -> Game.say(if (Game.full) "捏住小模型不放，移到远处发光的位置上（从你眼前看过去对齐），再松手。" else "捏住小模型不放，放到桌上小景里发光的位置，再松手。")
    }
}

/** The held thing moved by [d] (root frame). */
private fun dragBy(g: String, d: Vec3, drag: DragState) {
    if (g == "wpiece") {
        // The world's root sits at the feet: the same axes as the gallery's.
        val w = Game.world ?: return
        val p = w.piecePos() ?: return
        w.movePiece(p + d * if (Game.full) 1.4 else 1.0)
        return
    }
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
private fun panelOn(id: String): Boolean = !Game.debugClean && when (id) {
    PANEL_CARD -> Game.phase == Game.Phase.CARD
    PANEL_HOME -> Game.phase == Game.Phase.HOME
    // v0.3.2 (device 2026-11-18): after the result the task card stays on the left, with its 「走进画里」.
    PANEL_TASK -> Game.phase == Game.Phase.IN || (Game.phase == Game.Phase.RESULT && Game.inside)
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
/** The sun: its shadows only in the miniature (v0.3.1). */
private var sunRef: Entity? = null

private fun attach(v: GalleryView, a: SpatialViewAttachments, panels: HashMap<String, Entity>) {
    fun put(id: String) {
        val e = a.entity(id) ?: return
        val was = panels[id]
        if (was === e) return
        // The attachments can hand back a new entity for the same panel: the old one must go. Left in place (with its
        // frame) and no longer shown or hidden by anything, it stood empty in front of the new one: the device showed
        // three blank frames before the player and nothing could be done (2026-11-11).
        was?.let { it.enabled = false; it.getParent()?.removeChild(it) }
        panels[id] = e
        v.root.addChild(e)
        PANEL_FRAMES[id]?.let { name -> frames[name]?.clone(Entity.CloneOptions(true, true))?.let { f -> f.setPos(Vector3(0f, 0f, -0.006f)); e.addChild(f) } }
        placePanel(id, e)
    }
    // The eye frame (core/MiniPanels.PANELS): the title and result before the player, a little low; the task scroll at
    // the left, turned in; notes at the right; the menu tab low at the left.
    for (id in listOf(PANEL_CARD, PANEL_RESULT, PANEL_HOME, PANEL_TASK, PANEL_NOTE, PANEL_MENU)) put(id)
}

/** Where each panel stands now (its usual place, or clear of the miniature on the table). */
private val BASE_PLACE: Map<String, com.vrgames.huazhong.core.MiniPanels.Panel> = com.vrgames.huazhong.core.MiniPanels.PANELS.associateBy { it.id }
private var placement: Map<String, com.vrgames.huazhong.core.MiniPanels.Panel> = BASE_PLACE
private val miniPlaces = HashMap<String, Map<String, com.vrgames.huazhong.core.MiniPanels.Panel>>()
private var shapesCache: Map<String, com.vrgames.huazhong.core.MiniPanels.Shape>? = null
/** v0.5.2: with the studio bench before the player (研色画室). */
private val BENCH_PLACE: Map<String, com.vrgames.huazhong.core.MiniPanels.Panel> by lazy {
    com.vrgames.huazhong.core.MiniPanels.benchPlacement().also { p ->
        Log.i(TAG, "panels over the bench: " + p.values.filter { q -> BASE_PLACE[q.id] != q }.joinToString { q -> "${q.id} y %.2f z %.2f".format(q.y, q.z) }.ifEmpty { "all in their places" })
    }
}

private fun miniPlaceOf(world: com.vrgames.huazhong.core.World, w: com.vrgames.huazhong.render.WorldView): Map<String, com.vrgames.huazhong.core.MiniPanels.Panel> {
    // v0.5.2: 上元夜 (虹桥 at night) has its lanterns on the miniature too.
    val key = world.id + if (w.night) "n" else ""
    return synchronized(miniPlaces) { miniPlaces[key] } ?: run {
        val t0 = android.os.SystemClock.uptimeMillis()
        val sizes = w.sizes.mapValues { (_, v) -> doubleArrayOf(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()) }
        val shapes = synchronized(miniPlaces) { shapesCache ?: com.vrgames.huazhong.render.WorldView.loadShapes(w.context).also { shapesCache = it } }
        val p = com.vrgames.huazhong.core.MiniPanels.placement(world, sizes, w.night, shapes)
        val moved = p.values.filter { q -> BASE_PLACE[q.id] != q }.joinToString { q -> "${q.id} y %.2f z %.2f".format(q.y, q.z) }
        Log.i(TAG, "panels over $key's miniature: ${moved.ifEmpty { "all in their places" }} (${android.os.SystemClock.uptimeMillis() - t0} ms)")
        synchronized(miniPlaces) { miniPlaces[key] = p }
        p
    }
}

private fun placePanel(id: String, e: Entity) {
    val p = placement[id] ?: return
    e.setPos(Vector3(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()))
    e.transform().eulerAngles = EulerAngles(p.pitch.toFloat(), p.yaw.toFloat(), 0f)
}

private fun setPlacement(p: Map<String, com.vrgames.huazhong.core.MiniPanels.Panel>, v: GalleryView, panels: HashMap<String, Entity>) {
    placement = p
    for ((id, e) in panels) placePanel(id, e)
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

/** Strength of a walk-in world's panorama light (钓鱼 uses 9 with the same gain). */
private const val WORLD_LIGHT = 9f
