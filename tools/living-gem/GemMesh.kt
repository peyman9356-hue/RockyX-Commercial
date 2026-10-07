package com.rockyx.livinggem

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class GemMesh(
    nodeCount: Int = 32,
    seed: Int = 19
) {
    val count: Int = nodeCount
    val theta = FloatArray(nodeCount)
    val lat = FloatArray(nodeCount)
    val radius = FloatArray(nodeCount)
    val phase = FloatArray(nodeCount)
    val edgeA: IntArray
    val edgeB: IntArray

    init {
        val rng = java.util.Random(seed.toLong())
        for (i in 0 until nodeCount) {
            theta[i] = (2f * PI.toFloat() * i / nodeCount) +
                (rng.nextFloat() - 0.5f) * 0.22f
            lat[i] = -0.92f + 1.84f * ((i % 8) / 7f)
            lat[i] += (rng.nextFloat() - 0.5f) * 0.13f
            radius[i] = 0.90f + rng.nextFloat() * 0.14f
            phase[i] = rng.nextFloat() * 2f * PI.toFloat()
        }

        val pairs = ArrayList<Long>()
        val used = HashSet<Long>()
        for (i in 0 until nodeCount) {
            val neighbors = (0 until nodeCount)
                .asSequence()
                .filter { it != i }
                .map { j ->
                    val dx = theta[i] - theta[j]
                    val dy = lat[i] - lat[j]
                    j to (dx * dx + dy * dy)
                }
                .sortedBy { it.second }
                .take(3)
                .map { it.first }
                .toList()

            for (j in neighbors) {
                val a = minOf(i, j)
                val b = maxOf(i, j)
                val key = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
                if (used.add(key)) pairs.add(key)
            }
        }

        edgeA = IntArray(pairs.size)
        edgeB = IntArray(pairs.size)
        pairs.forEachIndexed { idx, key ->
            edgeA[idx] = (key shr 32).toInt()
            edgeB[idx] = key.toInt()
        }
    }
}
