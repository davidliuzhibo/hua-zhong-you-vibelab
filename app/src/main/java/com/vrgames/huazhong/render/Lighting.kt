package com.vrgames.huazhong.render

import com.pico.spatial.core.ecs.EnvironmentLightingSettingsComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ImageBasedLightComponent
import com.pico.spatial.core.ecs.ImageBasedLightReceiverComponent
import com.pico.spatial.core.ecs.ImageBasedLightSource
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.resource.TextureCreateOption
import com.pico.spatial.core.ecs.resource.TextureEncoding
import com.pico.spatial.core.ecs.resource.TextureResource

/**
 * Image-based light (a warm room, from 钟表店's ibl_old.ktx). Full immersion has no room light, so the
 * panels' wooden frames (PBR models) would be near black without it.
 */
class Lighting {
    val source = Entity()
    private var tex: TextureResource? = null

    /** Loads the light's texture (off the main thread). */
    fun load() {
        tex = runCatching {
            TextureResource.load("textures/ibl_old.ktx", LoadType.FROM_ASSETS, TextureCreateOption().apply { textureEncoding = TextureEncoding.LINEAR }).shared()
        }.onFailure { android.util.Log.w("HuaLight", "ibl: ${it.message}") }.getOrNull()
    }

    /** Turns the light on (main thread: components may only be set there). */
    fun apply() {
        tex?.let { source.components.set(ImageBasedLightComponent(ImageBasedLightSource.Single(it), 9f)) }
    }

    /** Lights every model under [e]. */
    /**
     * Lights [e] and what hangs under it by the room's image, except [skip] (and what hangs under those): the panels.
     * Given the receiver (with environment lighting 0), a panel drew nothing, only its frame showed (device
     * 2026-11-11: three blank panels).
     */
    fun receive(e: Entity, skip: Set<Entity> = emptySet()) {
        if (e in skip) return
        if (source.components[ImageBasedLightComponent::class.java] == null) return
        if (e.components[ModelComponent::class.java] != null && e.components[ImageBasedLightReceiverComponent::class.java] == null) {
            e.components.set(ImageBasedLightReceiverComponent(source))
            e.components.set(EnvironmentLightingSettingsComponent(0f))
        }
        e.getChildren().forEach { receive(it, skip) }
    }
}
