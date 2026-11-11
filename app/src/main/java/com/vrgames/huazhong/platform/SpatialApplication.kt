package com.vrgames.huazhong.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.vrgames.huazhong.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
