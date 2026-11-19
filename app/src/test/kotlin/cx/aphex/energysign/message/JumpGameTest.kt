package cx.aphex.energysign.message

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals

/** The game by itself: no sign, no app. Time is a number the test hands in. */
class JumpGameTest {

    /** A game with [rules] that began at 0, the same obstacles every time. */
    private fun game(rules: JumpGame.Rules = JumpGame.Rules()) = JumpGame(rules, Random(7)).also { it.start(0) }

    /** How the sign asks: the moments of its requests up to [untilMs]. */
    private fun every(ms: Long, untilMs: Long): List<Long> = (ms..untilMs step ms).toList()

    /** As on the sign: every 30 to 45 ms, and now and then 80 ms late. */
    private fun unevenly(untilMs: Long): List<Long> {
        val gaps = Random(3)
        val moments = mutableListOf<Long>()
        var at = 0L
        while (at < untilMs) {
            at += gaps.nextLong(30, 46) + if (gaps.nextInt(40) == 0) 80 else 0
            moments += at
        }
        return moments
    }

    /**
     * Plays [game] at the [requests] of the sign with a player who jumps when the next obstacle
     * is [lead] columns from the runner (in time at the first speed and at the top speed).
     * Returns when the button was pressed.
     */
    private fun play(game: JumpGame, requests: List<Long>, lead: Int = 8): List<Long> {
        val presses = mutableListOf<Long>()
        for (now in requests) {
            val frame = game.frame(now) ?: break
            val front = game.rules.runnerAt + game.rules.runner.width
            if (frame.rise == 0 && frame.obstacles.any { it.column - front in 0..lead } && game.jump(now)) presses += now
        }
        return presses
    }

    /** The same game again with the button pressed at [presses], the sign asking at [requests]: its score and how it ended, at [untilMs]. */
    private fun replay(presses: List<Long>, requests: List<Long>, untilMs: Long, rules: JumpGame.Rules = JumpGame.Rules()): String {
        val game = game(rules)
        val moments = (presses.map { it to true } + requests.map { it to false }).sortedBy { it.first }
        for ((now, press) in moments) if (press) game.jump(now) else game.frame(now)
        game.frame(untilMs)
        return "score ${game.score}, ${game.ended ?: "running"}"
    }

    /** Rules for a game to look at: nothing to hit, and one [speed]. */
    private fun toLookAt(speed: Double) = JumpGame.Rules(obstacles = listOf(JumpGame.Shape(listOf(0))), startSpeed = speed, topSpeed = speed)

    /** With one kind of obstacle and one speed: for how many milliseconds of pressing (in steps of 10) the first obstacle is cleared. */
    private fun windowMs(kind: Int, speed: Double): Int {
        val standard = JumpGame.Rules()
        val rules = JumpGame.Rules(obstacles = listOf(standard.obstacles[kind]), startSpeed = speed, topSpeed = speed)
        return (0L..4_000L step 10).count { press ->
            val game = game(rules)
            game.jump(press)
            (press..6_000L step 10).any { game.frame(it) == null || game.score > 0 } && game.score > 0
        } * 10
    }

    @Test
    fun `the jump, the obstacles, the speed and the collisions are a matter of elapsed time alone`() {
        val minute = 60_000L
        val pressed = play(game(), every(10, minute))
        val rows = listOf(
            // the arc: up past the top edge and down again in 900 ms (7 rows up, nothing of the runner is on the display)
            row("a jump at 0, the rise at 0, 100, 225, 450, 675, 800 and 900 ms", listOf(0, 3, 6, 7, 6, 3, 0)) {
                val game = game()
                game.jump(0)
                listOf(0L, 100, 225, 450, 675, 800, 900).map { game.frame(it)?.rise }
            },
            row("a press in the air is no second jump: the runner is down at 900 ms all the same", listOf(true, false, 0, true)) {
                val game = game()
                listOf(game.jump(0), game.jump(400), game.frame(900)?.rise, game.jump(900))
            },
            row("the runner's two pictures take turns on the ground, and it holds one in the air", listOf(0, 1, 0, 0, 0)) {
                val game = game()
                val onTheGround = listOf(0L, 160, 320).map { game.frame(it)?.picture }
                game.jump(330)
                onTheGround + listOf(400L, 600).map { game.frame(it)?.picture }
            },
            // A PRESS COUNTS FROM WHEN IT ARRIVED (the gate's case): with a post first, the game without a press ends at 2,140 ms
            row("a post first, a press that arrived at 2,110 ms and is carried out when the game has been worked out to 2,160", listOf(null, true, 1)) {
                val game = game(JumpGame.Rules(obstacles = listOf(JumpGame.Rules().obstacles[1])))
                val withoutThePress = game.frame(2_160)
                val jumped = game.jump(2_110)
                game.frame(3_000)
                listOf(withoutThePress, jumped, game.score)
            },
            row("a press that arrived after the hit is no jump, and the game stays ended", listOf(false, JumpGame.End.HIT, 0)) {
                val game = game(JumpGame.Rules(obstacles = listOf(JumpGame.Rules().obstacles[1])))
                game.frame(2_160)
                listOf(game.jump(2_150), game.ended, game.score)
            },
            row("the presses of a minute of play, each carried out up to 300 ms late, between the sign's requests: the same game", replay(pressed, every(40, minute), minute)) {
                val late = Random(5)
                val game = game()
                val carriedOut = pressed.map { it + late.nextLong(0, 301) to it }
                val moments = (carriedOut.map { it.first to it.second } + every(40, minute).map { it to null }).sortedBy { it.first }
                for ((now, press) in moments) if (press != null) game.jump(press) else game.frame(now)
                game.frame(minute)
                "score ${game.score}, ${game.ended ?: "running"}"
            },
            // a player who presses in time is never hit, in a minute in which the speed reaches its top
            row("a minute of play, the sign asking every 10 ms", "running") { replay(pressed, every(10, minute), minute).substringAfter(", ") },
            row("it scored", true) { replay(pressed, every(10, minute), minute) != "score 0, running" },
            // THE SAME PRESSES ARE THE SAME GAME, however the sign asks
            row("the same presses, the sign asking every 45 ms", replay(pressed, every(10, minute), minute)) { replay(pressed, every(45, minute), minute) },
            row("the same presses, the sign asking unevenly, 80 ms late now and then", replay(pressed, every(10, minute), minute)) { replay(pressed, unevenly(minute), minute) },
            row("the same presses, the sign asking four times a second", replay(pressed, every(10, minute), minute)) { replay(pressed, every(250, minute), minute) },
            row("the same presses, the sign asking once, at the end", replay(pressed, every(10, minute), minute)) { replay(pressed, emptyList(), minute) },
            // every kind can be cleared, slow and fast, and not by pressing at any moment
            row("every kind of obstacle leaves at least 150 ms to press in, at the first speed and at the top speed", List(6) { true }) {
                (0..2).flatMap { kind -> listOf(30.0, 54.0).map { speed -> windowMs(kind, speed) >= 150 } }
            },
            row("and no kind is cleared by a press at just any moment", List(3) { true }) {
                (0..2).map { kind -> windowMs(kind, 30.0) < 1_000 }
            },
            // the speed: obstacles cross the face faster late in a game than early
            row("the columns an obstacle moves in 100 ms: early, after a minute, after two (the speed has a top)", listOf(3, 5, 5)) {
                fun moved(game: JumpGame, from: Long): Int? {
                    // at the first moment from [from] on at which an obstacle is well on the face
                    val at = (from..from + 5_000 step 10).firstOrNull { now -> game.frame(now)?.obstacles?.any { it.column in 20..50 } == true } ?: return null
                    val before = game.frame(at)!!.obstacles.first { it.column in 20..50 }.column
                    return before - game.frame(at + 100)!!.obstacles.first { it.column in 10..before }.column
                }
                val late = game(JumpGame.Rules(obstacles = listOf(JumpGame.Shape(listOf(0))))) // nothing to hit: a game to look at
                listOf(moved(game(), 700), moved(late, 61_000), moved(late, 120_000))
            },
            // room between two obstacles for a whole jump and for pressing again, at the speed of the moment
            // THE SHAPES of the contract note: the runner's nose is high, so an obstacle is under it before it hits
            row("nobody presses: the column the step, the post and the ridge are last seen at before they hit", listOf(11, 13, 11)) {
                (0..2).map { kind ->
                    val game = game(JumpGame.Rules(obstacles = listOf(JumpGame.Rules().obstacles[kind])))
                    every(10, 5_000).mapNotNull { game.frame(it)?.obstacles?.firstOrNull()?.column }.last()
                }
            },
            // THE STARFIELD behind the play: its place is a quarter of what the ground has moved, by the clock alone, and wraps at 60
            row("the starfield's place at a ground speed of 30 columns a second, after 0, 1, 2 and 4 seconds", listOf(0, 7, 15, 30)) {
                val game = game(toLookAt(speed = 30.0))
                listOf(0L, 1_000, 2_000, 4_000).map { game.frame(it)?.stars }
            },
            row("and at 54 columns a second after 2 seconds", 27) { game(toLookAt(speed = 54.0)).frame(2_000)?.stars },
            row("it wraps: at 30 columns a second after 7.9, 8 and 9 seconds", listOf(59, 0, 7)) {
                val game = game(toLookAt(speed = 30.0))
                listOf(7_900L, 8_000, 9_000).map { game.frame(it)?.stars }
            },
            row("it gets faster as the game does: the columns it moves in the first four seconds, and in four seconds two minutes in", listOf(30, 54)) {
                val game = game(JumpGame.Rules(obstacles = listOf(JumpGame.Shape(listOf(0)))))
                listOf(0L, 120_000).map { from ->
                    val before = game.frame(from)?.stars
                    val after = game.frame(from + 4_000)?.stars
                    if (before == null || after == null) null else Math.floorMod(after - before, 60)
                }
            },
            // (every way of asking ends with the request AT 30 s and has none after it: the same tick is looked at. The ground
            // has moved 1,080.06 columns by then, a quarter of that is 270.015: column 30, and one tick earlier it is still 29)
            row("its place half a minute into a game is the same however the sign asks: every 10 ms, every 45, unevenly, once", List(4) { 30 }) {
                listOf(every(10, 30_000), every(45, 30_000), unevenly(30_000), emptyList()).map { requests ->
                    val game = game(JumpGame.Rules(obstacles = listOf(JumpGame.Shape(listOf(0)))))
                    requests.filter { it < 30_000 }.forEach { game.frame(it) }
                    game.frame(30_000)?.stars
                }
            },
            // THE FRAME on the wire: two keys, the text of the note with the starfield's place behind it, 35 bytes at most in play
            row("every frame of that minute as the sign reads it: longest in bytes, and how many are not of the note's form", listOf(true, 0)) {
                val game = game()
                val form = Regex("""\{"str":"[0-7][01]([abc]-?\d{1,2}){0,4}s[1-5]?\d","type":"G"\}""")
                val frames = (pressed.map { it to true } + every(10, minute).map { it to false }).sortedBy { it.first }.mapNotNull { (now, press) ->
                    if (press) game.jump(now)
                    game.frame(now)?.let { SignResponder.GSON.toJson(gameFrame(it)) }
                }
                listOf(frames.maxOf { it.length } in 25..35, frames.count { !form.matches(it) })
            },
            // THE RUNNER IS BLUE, by the sign's own default: in play and when it runs in no colour is sent; where a show names its colour, it begins blue (6)
            row("the first frame of the run-in, of a game, and of the new best's hop", listOf("00r-12e1s56", "00s0", "00r2p62e1s0")) {
                val shows = GameShows(0, 0, 0)
                listOf((shows.intro().last() as Beat.Frames).at(0), game().frame(0)!!, (shows.newBest(1, null)[4] as Beat.Frames).at(0)).map { gameFrame(it).str }
            },
            // THE STARFIELD IN THE SHOWS: it slides into the game's first place while the runner runs in, stands still at a hit, moves on in the hop
            row("the starfield in the run-in (at 0, 250 and 499 ms), in the picture of a hit at 17 (at 0 and 650 ms), in the hop (at 1 and 2 s)",
                listOf(56, 58, 59, 17, 17, 13, 27)) {
                val shows = GameShows(0, 0, 0)
                val runIn = shows.intro().last() as Beat.Frames
                val hit = shows.over(0, 0, JumpGame.Frame(0, 0, emptyList(), stars = 17)).first() as Beat.Frames
                val hop = shows.newBest(1, null)[4] as Beat.Frames
                listOf(runIn.at(0), runIn.at(250), runIn.at(499), hit.at(0), hit.at(650), hop.at(1_000), hop.at(2_000)).map { it.stars }
            },
            row("a frame with more than the sign can read is cut to what it can: four obstacles, every number in its range", "71a10b20c30a-99r99p70e2s10") {
                val obstacles = listOf(0 to 150, 1 to -120, 7 to 3, 0 to 10, 1 to 20, 2 to 30, 0 to -99, 1 to 50).map { JumpGame.Obstacle(it.first, it.second) }
                gameFrame(JumpGame.Frame(rise = 12, picture = 5, obstacles = obstacles, runnerAt = 300, colours = 9 to -1, effect = 2, stars = 130)).str
            },
            row("the least room between two obstacles on a face in that minute, in columns: a jump and time to press again", true) {
                val game = game()
                var least = Int.MAX_VALUE
                val moments = (pressed.map { it to true } + every(10, minute).map { it to false }).sortedBy { it.first }
                for ((now, press) in moments) {
                    if (press) game.jump(now)
                    val columns = game.frame(now)?.obstacles?.map { it.column }.orEmpty()
                    columns.zipWithNext().forEach { (a, b) -> least = minOf(least, b - a) }
                }
                least in 53..65 // (two are on a face only when they are that close; the least there can be is 30 x 1.35 + 13)
            },
        )

        assertEquals(emptyList(), wrong(rows))
    }

    @Test
    fun `every game ends by itself`() {
        val rows: List<Pair<String, () -> Pair<Any?, Any?>>> = listOf(
            "nobody presses: the game ends at its first obstacle, within three seconds" to {
                val game = game()
                val over = every(10, 10_000).firstOrNull { game.frame(it) == null }
                listOf(game.ended, game.score, over != null && over < 3_000) to listOf(JumpGame.End.HIT, 0, true)
            },
            "a player who never misses is stopped at the longest game" to {
                val game = game()
                play(game, every(10, 200_000))
                listOf(game.ended, game.frame(200_000)) to listOf(JumpGame.End.TOO_LONG, null)
            },
            "the sign stops asking in the middle and asks again ten minutes later: the game ended back then" to {
                val game = game()
                play(game, every(10, 5_000))
                val scoreThen = game.score
                listOf(game.frame(605_000), game.ended, game.score - scoreThen <= 1) to listOf(null, JumpGame.End.HIT, true)
            },
            "after an hour without a request the game is one that ended at its first obstacle, not one worked out to the longest game" to {
                val game = game()
                game.frame(100)
                listOf(game.frame(3_600_000), game.ended, game.score) to listOf(null, JumpGame.End.HIT, 0)
            },
            "a game that ended does not go on: no frame, and the button is nobody's" to {
                val game = game()
                game.frame(10_000)
                listOf(game.running, game.frame(10_010), game.jump(10_020)) to listOf(false, null, false)
            },
            "before the first game there is none" to {
                val game = JumpGame()
                listOf(game.running, game.frame(0), game.jump(0), game.ended) to listOf(false, null, false, null)
            },
            "a new game after one that ended starts from nothing" to {
                val game = game()
                play(game, every(10, 20_000))
                game.frame(100_000)
                game.start(100_000)
                listOf(game.running, game.score, game.ended, game.frame(100_010)?.obstacles?.size) to listOf(true, 0, null, 0)
            },
        )

        assertEquals(emptyList(), wrong(rows))
    }

    /** One row of a table: what it is, what it should give, and what it gives. */
    private fun row(name: String, expected: Any?, got: () -> Any?): Pair<String, () -> Pair<Any?, Any?>> = name to { got() to expected }

    /** The rows that did not give what they should, as lines. */
    private fun wrong(rows: List<Pair<String, () -> Pair<Any?, Any?>>>): List<String> =
        rows.mapNotNull { (name, row) ->
            val (got, expected) = row()
            if (got == expected) null else "$name: $got, not $expected"
        }
}
