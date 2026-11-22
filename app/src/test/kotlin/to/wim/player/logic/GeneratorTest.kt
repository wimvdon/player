package to.wim.player.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GeneratorTest {
    /** [artists] artists with [perArtist] tracks each, moods and familiarity spread out evenly. */
    private fun pool(artists: Int, perArtist: Int, seedArtists: Int = 0): List<Candidate> {
        val random = Random(1)
        return (0 until artists).flatMap { a ->
            (0 until perArtist).map { t ->
                Candidate(
                    artist = "Artist $a",
                    title = "Track $a-$t",
                    mood = random.nextDouble(),
                    familiarity = random.nextDouble(),
                    fromSeedArtist = a < seedArtists,
                )
            }
        }
    }

    private fun request(mood: Double = 0.5, familiarity: Double = 0.5, length: Int = 50, ordering: Ordering = Ordering.MOOD_CURVE) =
        GeneratorRequest(mood, familiarity, length, ordering)

    @Test
    fun `returns the requested length when the pool is big enough`() {
        val result = Generator.generate(pool(60, 10), request(length = 100), Random(2))
        assertEquals(100, result.size)
        assertEquals(100, result.map { it.key }.distinct().size)
    }

    @Test
    fun `at most three tracks per artist`() {
        val result = Generator.generate(pool(10, 20), request(length = 100), Random(3))
        val perArtist = result.groupingBy { it.artistKey }.eachCount()
        assertTrue(perArtist.values.all { it <= Generator.MAX_PER_ARTIST })
        assertEquals(30, result.size)
    }

    @Test
    fun `never the same artist twice in a row`() {
        for (seed in 0 until 20) {
            val ordering = Ordering.entries[seed % Ordering.entries.size]
            val result = Generator.generate(pool(25, 6), request(length = 60, ordering = ordering), Random(seed))
            result.zipWithNext().forEach { (a, b) -> assertNotEquals("seed $seed", a.artistKey, b.artistKey) }
        }
    }

    @Test
    fun `picks tracks close to the sliders`() {
        val upbeatKnown = request(mood = 1.0, familiarity = 0.0, length = 40)
        val result = Generator.select(pool(80, 5), upbeatKnown, Random(4))
        assertTrue(result.map { it.mood }.average() > 0.7)
        assertTrue(result.map { it.familiarity }.average() < 0.3)
    }

    @Test
    fun `at least half from artists the user did not pick`() {
        // Seed artists are the best matches here, so without the rule they'd fill the set.
        val seeds = (0 until 30).flatMap { a -> (0 until 3).map { Candidate("Seed $a", "S $a $it", 0.5, 0.5, true) } }
        val others = (0 until 60).map { Candidate("Other $it", "O $it", 0.9, 0.9, false) }
        val result = Generator.select(seeds + others, request(length = 40), Random(5))
        assertEquals(40, result.size)
        assertTrue(result.count { !it.fromSeedArtist } >= 20)
    }

    @Test
    fun `seed share holds even when other artists run out`() {
        val seeds = (0 until 30).flatMap { a -> (0 until 3).map { Candidate("Seed $a", "S $a $it", 0.5, 0.5, true) } }
        val others = (0 until 5).map { Candidate("Other $it", "O $it", 0.5, 0.5, false) }
        val result = Generator.select(seeds + others, request(length = 40), Random(6))
        val fromOthers = result.count { !it.fromSeedArtist }
        assertEquals(5, fromOthers)
        assertTrue(result.count { it.fromSeedArtist } <= fromOthers)
    }

    @Test
    fun `fully known lifts the seed limit`() {
        val seeds = (0 until 30).flatMap { a -> (0 until 3).map { Candidate("Seed $a", "S $a $it", 0.5, 0.0, true) } }
        val others = (0 until 60).map { Candidate("Other $it", "O $it", 0.9, 0.9, false) }
        val result = Generator.select(seeds + others, request(familiarity = 0.0, length = 40), Random(7))
        assertTrue(result.all { it.fromSeedArtist })
    }

    @Test
    fun `regenerating gives a different set`() {
        val candidates = pool(100, 10)
        val first = Generator.select(candidates, request(length = 50), Random(8)).map { it.key }.toSet()
        val second = Generator.select(candidates, request(length = 50), Random(9)).map { it.key }.toSet()
        assertNotEquals(first, second)
    }

    @Test
    fun `mood curve starts and ends calm and peaks in the middle`() {
        val tracks = (0 until 9).map { Candidate("A$it", "T$it", it / 8.0, 0.5, false) }
        val curve = Generator.moodCurve(tracks.shuffled(Random(10)))
        val moods = curve.map { it.mood }
        val peak = moods.indexOf(moods.max())
        assertTrue(peak in 3..5)
        assertTrue(moods.first() < moods[peak] && moods.last() < moods[peak])
        // Rising up to the peak, falling after it.
        assertTrue(moods.subList(0, peak + 1).zipWithNext().all { (a, b) -> a <= b })
        assertTrue(moods.subList(peak, moods.size).zipWithNext().all { (a, b) -> a >= b })
    }

    @Test
    fun `best match keeps the closest first`() {
        val result = Generator.generate(pool(60, 4), request(length = 30, ordering = Ordering.BEST_MATCH), Random(11))
        val request = request()
        assertTrue(Generator.distance(result.first(), request) < Generator.distance(result.last(), request) + Generator.JITTER + 0.2)
    }

    @Test
    fun `separating artists keeps every track`() {
        val list = listOf("A", "A", "A", "B", "C").mapIndexed { i, a -> Candidate(a, "T$i", 0.5, 0.5, false) }
        val result = Generator.separateArtists(list)
        assertEquals(list.toSet(), result.toSet())
        result.zipWithNext().forEach { (a, b) -> assertNotEquals(a.artistKey, b.artistKey) }
    }

    @Test
    fun `duplicates in the pool are counted once`() {
        val dup = listOf(Candidate("A", "Song", 0.5, 0.5, false), Candidate("a", "Song - Remastered", 0.5, 0.5, false))
        assertEquals(1, Generator.select(dup, request(length = 10), Random(12)).size)
    }

    @Test
    fun `length text is checked`() {
        assertEquals(250, Generator.parseLength("250"))
        assertEquals(10, Generator.parseLength(" 10 "))
        assertNull(Generator.parseLength("9"))
        assertNull(Generator.parseLength("501"))
        assertNull(Generator.parseLength(""))
    }
}
