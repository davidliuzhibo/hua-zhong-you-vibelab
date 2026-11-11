package com.vrgames.huazhong

import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.vrgames.huazhong.content.GalleryStage

/** 画中游: a full-immersion gallery where two Song paintings stand round the seated player. */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            PicoTheme { GalleryStage() }
        }
    }
