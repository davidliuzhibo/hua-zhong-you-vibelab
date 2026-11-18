package com.vrgames.huazhong.core

import kotlin.math.max

/**
 * The pinch box round a model in a world (width, height, depth in the model's own frame, before its scale), shared by the
 * game (WorldView) and the tests.
 *
 * v0.3.1 (device 2026-11-18, 汴京郊野: 「无论是在画外看精致的小场景，还是进入画中，点击驴队，都没有反应」): the thing to
 * find had a box as small as its model — a donkey is 1.6 m, 3 cm in the miniature, too small to hold the gaze on — and a
 * willow's box was its whole crown, so a look at the road past it landed on the tree. Now the thing to find is at least
 * [MIN_SPOT] across (a train of donkeys becomes one long target), and trees keep only their trunk and lower boughs.
 */
object PinchBox {
    const val MIN_SPOT = 3.0
    const val MIN_SPOT_H = 2.4
    const val TREE = 0.3

    fun isTree(m: String) = m.startsWith("t_") || m == "ql_pine"

    /** [size] the model's prepared size (x, y, z). */
    fun of(m: String, size: DoubleArray, spot: Boolean): DoubleArray {
        val w = size[0]
        val h = size[1]
        val d = size[2]
        return when {
            spot -> doubleArrayOf(max(w * 1.2, MIN_SPOT), max(h * 1.1, MIN_SPOT_H), max(d * 1.2, MIN_SPOT))
            isTree(m) -> doubleArrayOf(w * TREE, h, d * TREE)
            else -> doubleArrayOf(w, h, d)
        }
    }
}
