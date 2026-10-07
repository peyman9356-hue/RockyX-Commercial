package com.rockyx.livinggem

import kotlin.math.PI

/**
 * Deterministic brilliant-cut / biconvex diamond topology.
 *
 * The dog occupies the visual core. These vertices describe only the surrounding
 * gem field; no training-domain state is stored here.
 */
class GemMesh {
    private val ringCounts = intArrayOf(1, 8, 8, 8, 8, 8, 1)
    private val ringLats = floatArrayOf(-1.45f, -0.92f, -0.46f, 0f, 0.46f, 0.92f, 1.45f)
    private val ringRadius = floatArrayOf(0.10f, 0.58f, 0.86f, 1.00f, 0.86f, 0.58f, 0.10f)

    val count: Int
    val theta: FloatArray
    val lat: FloatArray
    val radius: FloatArray
    val phase: FloatArray
    val edgeA: IntArray
    val edgeB: IntArray
    val faceA: IntArray
    val faceB: IntArray
    val faceC: IntArray

    init {
        var total = 0
        for (n in ringCounts) total += n
        count = total

        theta = FloatArray(count)
        lat = FloatArray(count)
        radius = FloatArray(count)
        phase = FloatArray(count)

        val starts = IntArray(ringCounts.size)
        var cursor = 0
        for (r in ringCounts.indices) {
            starts[r] = cursor
            val n = ringCounts[r]
            for (i in 0 until n) {
                theta[cursor] = if (n == 1) 0f else 2f * PI.toFloat() * i / n
                lat[cursor] = ringLats[r]
                radius[cursor] = ringRadius[r]
                phase[cursor] = ((cursor * 37) % 97) / 97f * 2f * PI.toFloat()
                cursor++
            }
        }

        val edges = LinkedHashSet<Long>()
        val faces = ArrayList<Triple<Int, Int, Int>>()

        fun edge(a: Int, b: Int) {
            val lo = minOf(a, b)
            val hi = maxOf(a, b)
            edges.add((lo.toLong() shl 32) or (hi.toLong() and 0xffffffffL))
        }

        for (r in 0 until ringCounts.lastIndex) {
            val aStart = starts[r]
            val bStart = starts[r + 1]
            val aCount = ringCounts[r]
            val bCount = ringCounts[r + 1]

            if (aCount == 1) {
                val pole = aStart
                for (j in 0 until bCount) {
                    val b = bStart + j
                    val c = bStart + ((j + 1) % bCount)
                    edge(pole, b)
                    edge(b, c)
                    faces.add(Triple(pole, b, c))
                }
            } else if (bCount == 1) {
                val pole = bStart
                for (i in 0 until aCount) {
                    val a = aStart + i
                    val b = aStart + ((i + 1) % aCount)
                    edge(a, pole)
                    edge(a, b)
                    faces.add(Triple(a, pole, b))
                }
            } else {
                for (i in 0 until aCount) {
                    val a0 = aStart + i
                    val a1 = aStart + ((i + 1) % aCount)
                    val b0 = bStart + i
                    val b1 = bStart + ((i + 1) % bCount)
                    edge(a0, a1)
                    edge(a0, b0)
                    edge(a1, b1)
                    edge(b0, b1)
                    faces.add(Triple(a0, b0, a1))
                    faces.add(Triple(a1, b0, b1))
                }
            }
        }

        edgeA = IntArray(edges.size)
        edgeB = IntArray(edges.size)
        edges.forEachIndexed { idx, key ->
            edgeA[idx] = (key shr 32).toInt()
            edgeB[idx] = key.toInt()
        }

        faceA = IntArray(faces.size)
        faceB = IntArray(faces.size)
        faceC = IntArray(faces.size)
        faces.forEachIndexed { idx, face ->
            faceA[idx] = face.first
            faceB[idx] = face.second
            faceC[idx] = face.third
        }
    }
}
