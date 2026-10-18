package org.openutm.djiflight.core

import java.io.File
import java.util.concurrent.TimeUnit

/** Reads Blender's observation table through `docker exec` (this workspace's Colima setup). Null if unavailable. */
object BlenderDb {
    data class Count(val total: Int, val withHeight: Int, val ground: Int = 0, val airborne: Int = 0, val maxGapS: Double = 0.0)

    /**
     * Blender's Celery worker writes one task per state (about 60 states/s here) and may still be
     * busy with an earlier test's batch, so poll until [expected] rows are there or [timeoutMs] passes.
     */
    fun observationCount(declarationId: String, expected: Int, timeoutMs: Long = 30_000): Count? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Count? = null
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1_000)
            val c = query(declarationId) ?: return null
            if (c.total >= expected) return c
            last = c
        }
        return last
    }

    private fun query(declarationId: String): Count? {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "stack/openutm.sh").exists() }
            ?: return null
        val sql = "WITH t AS (SELECT ((metadata::jsonb)->'current_state'->'timestamp'->>'value')::timestamptz ts, " +
            "(metadata::jsonb)->'current_state'->>'operational_status' st, (metadata::jsonb)->'current_state'->'position'->>'height' h " +
            "FROM flight_feed_operations_flightobservation WHERE (metadata::jsonb)->'flight_details'->>'id' = '$declarationId'), " +
            "g AS (SELECT st, h, extract(epoch from ts - lag(ts) OVER (ORDER BY ts)) gap FROM t) " +
            "SELECT count(*), count(*) FILTER (WHERE h IS NOT NULL AND h <> 'null'), count(*) FILTER (WHERE st = 'Ground'), " +
            "count(*) FILTER (WHERE st = 'Airborne'), coalesce(max(gap), 0) FROM g"
        return runCatching {
            val pb = ProcessBuilder("docker", "exec", "-i", "db-blender", "sh", "-c", "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At")
            pb.environment()["DOCKER_CONFIG"] = "${root.path}/.docker"
            pb.environment()["DOCKER_HOST"] = "unix://${System.getProperty("user.home")}/.colima/openutm/docker.sock"
            pb.environment()["PATH"] = "/opt/homebrew/bin:/usr/local/bin:" + System.getenv("PATH")
            val proc = pb.start()
            proc.outputStream.use { it.write(sql.toByteArray()) }
            if (!proc.waitFor(20, TimeUnit.SECONDS) || proc.exitValue() != 0) return null
            val f = proc.inputStream.bufferedReader().readText().trim().split("|")
            Count(f[0].toInt(), f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toDouble())
        }.getOrNull()
    }
}
