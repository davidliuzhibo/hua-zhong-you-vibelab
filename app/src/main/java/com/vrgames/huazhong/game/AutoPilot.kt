package com.vrgames.huazhong.game

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.vrgames.huazhong.core.Scrolls
import com.vrgames.huazhong.core.Task

/**
 * Test hook, debug builds only (logcat tag HuaTest):
 *
 *   adb shell am broadcast -a com.vrgames.huazhong.TEST -p com.vrgames.huazhong --es cmd walk
 *
 * cmd: home · open --es id <section> · solve (the open section, every task right) · walk (every
 * section of both scrolls in turn) · grant --es id <stars> · night · view --es id
 * "x,y,z,yaw,scale,pitch"|reset · reset · stop
 */
object AutoPilot {
    const val TAG = "HuaTest"
    private val main = Handler(Looper.getMainLooper())
    private var walking = false

    fun register(context: Context) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = command(i.getStringExtra("cmd") ?: "", i.getStringExtra("id"))
        }
        ContextCompat.registerReceiver(context.applicationContext, receiver, IntentFilter("com.vrgames.huazhong.TEST"), ContextCompat.RECEIVER_EXPORTED)
        Log.i(TAG, "ready")
    }

    fun ready() = Log.i(TAG, "scene ready")

    private fun command(cmd: String, id: String?) {
        Log.i(TAG, "cmd $cmd ${id.orEmpty()}")
        when (cmd) {
            "home" -> Game.toHome()
            "open" -> Scrolls.ALL.firstOrNull { it.id == id }?.let { Game.open(it) }
            "solve" -> { main.removeCallbacksAndMessages(null); main.postDelayed(::solve, 500) }
            "walk" -> { main.removeCallbacksAndMessages(null); walking = true; main.postDelayed(::walkStep, 500) }
            "grant" -> Game.grant(id?.toIntOrNull() ?: 3)
            "night" -> Game.lanternNight()
            "view" -> Game.debugView = id?.takeIf { it != "reset" }
            "reset" -> { main.removeCallbacksAndMessages(null); walking = false; Game.restart() }
            "stop" -> { main.removeCallbacksAndMessages(null); walking = false }
            else -> Log.w(TAG, "unknown command $cmd")
        }
    }

    /** Does the task in hand the right way, every 1.5 s, until the section is done. */
    private fun solve() {
        val v = Game.visit ?: return
        if (Game.phase != Game.Phase.IN) return
        val view = Game.view
        when (val t = v.task ?: return) {
            is Task.Find -> Game.pinchWall(true)
            is Task.Restore -> view?.pieceTarget()?.let { view.movePiece(it); Game.pieceLetGo() }
            is Task.Quiz -> Game.answer(t.answer)
            is Task.Rub -> Game.rub(t.tool, 0.12, null)
            is Task.Match -> t.pairs.entries.firstOrNull { it.key !in v.matched }?.let { Game.paint(it.key, it.value) }
        }
        main.postDelayed(::solve, 1500)
    }

    private fun walkStep() {
        if (!walking) return
        val s = Game.progress.next()
        if (s == null) {
            val p = Game.progress
            Log.i(TAG, "walk done stars=${p.stars}/${p.maxStars} bronze=${p.bronze} silver=${p.silver} gold=${p.gold}")
            walking = false
            return
        }
        Game.open(s)
        main.postDelayed({ solve(); waitResult(s.id) }, 4000)
    }

    private fun waitResult(id: String) {
        if (Game.phase == Game.Phase.RESULT && Game.section?.id == id) {
            Log.i(TAG, "result $id stars=${Game.visit?.stars()} misses=${Game.visit?.misses}")
            main.postDelayed(::walkStep, 2000)
        } else main.postDelayed({ waitResult(id) }, 700)
    }
}
