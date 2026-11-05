// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID
import kotlin.system.measureNanoTime
import kotlin.test.assertEquals

/**
 * Stress test with 5,000 clips. Prints and writes timings to build/reports/clipboard-benchmark.txt.
 * It runs on the JVM under Robolectric, so the numbers are not on-device numbers.
 * It does not assert on timings, so it cannot fail because the CI machine is slow.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardBenchmarkTest {

    @Test
    fun clipboardWith5000Clips() {
        val context = RuntimeEnvironment.getApplication()
        val db = newDatabase(context, "bench-${UUID.randomUUID()}.db")
        val seedDao = newDao(db)
        val now = System.currentTimeMillis()

        val seedMs = measureNanoTime {
            for (i in 0 until CLIP_COUNT) {
                seedDao.insertNewEntry(now - i * 60_000L, i % 50 == 0, sampleText(i), null, null, null)
            }
        }.toMs()
        assertEquals(CLIP_COUNT, seedDao.count())

        // cold load: a new DAO reads all rows from SQLite and builds the in-memory list
        val loadMs = List(LOAD_RUNS) { measureNanoTime { newDao(db) }.toMs() }
        val loaded = newDao(db)
        assertEquals(CLIP_COUNT, loaded.count())

        // one new clip added to a list of 5,000
        val addMs = List(ADD_RUNS) { i ->
            measureNanoTime { loaded.addClip(now + i, false, "fresh clip $i ${UUID.randomUUID()}") }.toMs()
        }

        // in-memory substring filter over all text clips (this is what search will cost)
        val entries = loaded.getAll()
        val queries = listOf("lorem", "item4217", "no-such-text")
        val searchMs = queries.associateWith { query ->
            List(SEARCH_RUNS) {
                measureNanoTime { entries.count { it.text?.contains(query, ignoreCase = true) == true } }.toMs()
            }
        }

        val report = buildString {
            appendLine("Clipboard benchmark (JVM + Robolectric, not on-device)")
            appendLine("clips: $CLIP_COUNT")
            appendLine("seed insert, all clips (one run): ${"%.1f".format(seedMs)} ms")
            appendLine("cold load of DAO (median of $LOAD_RUNS): ${"%.2f".format(median(loadMs))} ms")
            appendLine("add one clip (median of $ADD_RUNS): ${"%.3f".format(median(addMs))} ms")
            searchMs.forEach { (query, times) ->
                appendLine("in-memory filter, query \"$query\" (median of $SEARCH_RUNS): ${"%.3f".format(median(times))} ms")
            }
        }
        println(report)
        File("build/reports").mkdirs()
        File("build/reports/clipboard-benchmark.txt").writeText(report)
    }

    private fun newDatabase(context: Context, name: String): Database {
        val ctor = Database::class.java.getDeclaredConstructor(Context::class.java, String::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(context, name)
    }

    private fun newDao(db: Database): ClipboardDao {
        val ctor = ClipboardDao::class.java.getDeclaredConstructor(Database::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(db)
    }

    private fun sampleText(i: Int): String {
        val words = listOf("lorem", "ipsum", "dolor", "sit", "amet", "consectetur", "adipiscing", "elit", "sed", "do",
            "eiusmod", "tempor", "incididunt", "ut", "labore", "et", "dolore", "magna", "aliqua", "enim")
        return "item$i " + (0 until 8).joinToString(" ") { words[(i * 7 + it * 13) % words.size] }
    }

    private fun Long.toMs() = this / 1_000_000.0

    private fun median(values: List<Double>) = values.sorted()[values.size / 2]

    private companion object {
        const val CLIP_COUNT = 5000
        const val LOAD_RUNS = 5
        const val ADD_RUNS = 50
        const val SEARCH_RUNS = 20
    }
}
