package com.vrgames.huazhong.render

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ObjectAudioComponent
import com.pico.spatial.core.ecs.audio.AudioPlayerController
import com.pico.spatial.core.ecs.resource.AudioResource
import com.vrgames.huazhong.core.Vec3

/** Synthesized by tools/sfx_synth.py (see docs/素材来源.md). */
enum class Sfx(val file: String, val volume: Float) {
    OPEN("open", 0.8f),
    PICK("pick", 0.7f),
    RIGHT("right", 0.8f),
    WRONG("wrong", 0.6f),
    DONE("done", 0.9f),
    SEAL("seal", 1f),
    GRIND("grind", 0.7f),
    SPLASH("splash", 0.6f),
    PAINT("paint", 0.7f),
}

/**
 * Spatial sound from the scene: each effect has one long-lived emitter with a prepared player,
 * moved to where the sound happens and restarted (creating players per event drops frames).
 * Main thread only.
 */
class Sound(private val parent: Entity) {
    private val emitters = HashMap<Sfx, Entity>()
    private val controllers = HashMap<Sfx, AudioPlayerController>()
    var enabled = true

    /** Loads every clip; a clip that fails is skipped (logged). */
    fun load() = loadFrom(0)

    /**
     * One clip per turn of the main loop (v0.2): with the new clips, loading them all in one go held
     * the main thread long enough for an "app not responding" on the emulator.
     */
    private fun loadFrom(i: Int) {
        val s = Sfx.entries.getOrNull(i) ?: return
        run {
            runCatching {
                val res = AudioResource.load(s.file, "asset://audio/${s.file}.wav", LoadType.FROM_ASSETS)
                val e = Entity()
                e.components.set(ObjectAudioComponent(s.volume))
                parent.addChild(e)
                val c = e.prepareAudio(res)
                if (c.valid) {
                    emitters[s] = e
                    controllers[s] = c
                }
            }.onFailure { android.util.Log.w(TAG, "sfx ${s.file}: ${it.message}") }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post { loadFrom(i + 1) }
    }

    /** Plays [s] at [at] (in [parent]'s frame; by default the middle of the parent). */
    fun play(s: Sfx, at: Vec3 = Vec3(0.0, 0.1, 0.0)) {
        if (!enabled) return
        val c = controllers[s] ?: return
        emitters[s]?.setPos(at)
        runCatching {
            if (c.isPlaying()) c.stop()
            c.seekTo(0)
            c.play()
        }
    }

    companion object {
        private const val TAG = "GameSound"
    }
}
