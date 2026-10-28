package ua.mandat.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A map of links: matters and people as points, a line where a person takes part in a matter.
 * Laid out by a simple force simulation (linked points pull together, all points push apart),
 * started from fixed positions so the same data always gives the same picture. Counted on the
 * phone, in the background.
 */
object Graph {
    /** A point: "m<id>" for a matter, "p<id>" for a person; [degree] is how many lines meet there. */
    data class Node(val key: String, val label: String, val isMatter: Boolean, val degree: Int, var x: Float = 0f, var y: Float = 0f)

    data class Layout(val nodes: List<Node>, val edges: List<Pair<Int, Int>>)

    /**
     * Matters with the people linked to them; people linked to a single matter only are dropped
     * when the map would be too large, so the people who connect matters stay visible.
     */
    fun build(matters: List<Pair<Long, String>>, people: Map<Long, String>, links: List<Pair<Long, Long>>, maxNodes: Int = 140): Layout {
        val matterIds = matters.map { it.first }.toSet()
        val usable = links.filter { it.first in matterIds && it.second in people }.distinct()
        val perPerson = usable.groupBy { it.second }
        var personIds = perPerson.keys.sortedByDescending { perPerson[it]!!.size }
        if (matters.size + personIds.size > maxNodes) personIds = personIds.filter { perPerson[it]!!.size > 1 }
        personIds = personIds.take(max(0, maxNodes - matters.size))
        val kept = personIds.toSet()
        val edgesIds = usable.filter { it.second in kept }
        val degree = HashMap<String, Int>()
        edgesIds.forEach { (m, p) -> degree["m$m"] = (degree["m$m"] ?: 0) + 1; degree["p$p"] = (degree["p$p"] ?: 0) + 1 }
        val nodes = matters.map { (id, t) -> Node("m$id", t, true, degree["m$id"] ?: 0) } +
            personIds.map { Node("p$it", people[it]!!, false, degree["p$it"] ?: 0) }
        val index = nodes.withIndex().associate { it.value.key to it.index }
        val edges = edgesIds.map { (m, p) -> index.getValue("m$m") to index.getValue("p$p") }
        layout(nodes, edges)
        return Layout(nodes, edges)
    }

    /** Fruchterman–Reingold in a unit square, then fitted to 0..1. */
    private fun layout(nodes: List<Node>, edges: List<Pair<Int, Int>>, steps: Int = 220) {
        val n = nodes.size
        if (n == 0) return
        // Fixed start on a spiral: no randomness, the same picture each time
        nodes.forEachIndexed { i, node ->
            val a = i * 2.39996f
            val r = 0.5f * sqrt((i + 1f) / n)
            node.x = 0.5f + r * kotlin.math.cos(a); node.y = 0.5f + r * kotlin.math.sin(a)
        }
        val k = sqrt(1f / n)
        val dx = FloatArray(n); val dy = FloatArray(n)
        var temp = 0.1f
        repeat(steps) {
            dx.fill(0f); dy.fill(0f)
            for (i in 0 until n) for (j in i + 1 until n) {
                val ddx = nodes[i].x - nodes[j].x; val ddy = nodes[i].y - nodes[j].y
                val d = max(sqrt(ddx * ddx + ddy * ddy), 0.001f)
                val f = k * k / d
                dx[i] += ddx / d * f; dy[i] += ddy / d * f
                dx[j] -= ddx / d * f; dy[j] -= ddy / d * f
            }
            for ((a, b) in edges) {
                val ddx = nodes[a].x - nodes[b].x; val ddy = nodes[a].y - nodes[b].y
                val d = max(sqrt(ddx * ddx + ddy * ddy), 0.001f)
                val f = d * d / k
                dx[a] -= ddx / d * f; dy[a] -= ddy / d * f
                dx[b] += ddx / d * f; dy[b] += ddy / d * f
            }
            for (i in 0 until n) {
                // A gentle pull to the middle keeps lone points from drifting away
                dx[i] += (0.5f - nodes[i].x) * 0.05f; dy[i] += (0.5f - nodes[i].y) * 0.05f
                val d = max(sqrt(dx[i] * dx[i] + dy[i] * dy[i]), 0.0001f)
                nodes[i].x += dx[i] / d * min(d, temp); nodes[i].y += dy[i] / d * min(d, temp)
            }
            temp *= 0.98f
        }
        val minX = nodes.minOf { it.x }; val maxX = nodes.maxOf { it.x }
        val minY = nodes.minOf { it.y }; val maxY = nodes.maxOf { it.y }
        val w = max(maxX - minX, 0.0001f); val h = max(maxY - minY, 0.0001f)
        nodes.forEach { it.x = (it.x - minX) / w; it.y = (it.y - minY) / h }
    }
}
