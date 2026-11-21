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
            // THE FRAME on the wire: two keys, the text of the note with a treat, a sparkle, the starfield's place and the score
            // behind it, 39 bytes at most in play (two obstacles, `t-3`, `s59`, `n999`)
            row("every frame of that minute as the sign reads it: longest in bytes, and how many are not of the note's form", listOf(true, 0)) {
                val game = game()
                val form = Regex("""\{"str":"[0-7][01]([abc]-?\d{1,2}){0,4}(t-?\d{1,2})?(e1)?s[1-5]?\dn(0|[1-9]\d{0,2})","type":"G"\}""")
                val frames = (pressed.map { it to true } + every(10, minute).map { it to false }).sortedBy { it.first }.mapNotNull { (now, press) ->
                    if (press) game.jump(now)
                    game.frame(now)?.let { SignResponder.GSON.toJson(gameFrame(it)) }
                }
                listOf(frames.maxOf { it.length } in 27..39, frames.count { !form.matches(it) })
            },
            // THE RUNNER IS BLUE, by the sign's own default: in play and when it runs in no colour is sent; where a show names its colour, it begins blue (6)
            row("the first frame of the run-in, of a game, and of the new best's hop", listOf("00r-12e1s56", "00s0n0", "00r2p62e1s0")) {
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
            row("a frame with more than the sign can read is cut to what it can: four obstacles, every number in its range", "71a10b20c30a-99r99p70e2s10n0") {
                val obstacles = listOf(0 to 150, 1 to -120, 7 to 3, 0 to 10, 1 to 20, 2 to 30, 0 to -99, 1 to 50).map { JumpGame.Obstacle(it.first, it.second) }
                gameFrame(JumpGame.Frame(rise = 12, picture = 5, obstacles = obstacles, runnerAt = 300, colours = 9 to -1, effect = 2, stars = 130, score = -3)).str
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

    /**
     * THE SCORE (es-96d): small digits at the top right of the face, `n` and the score on the
     * wire (the contract note's version 6). Every frame of play carries it, and so does the
     * picture of the hit after a game; the run-in and the new best's hop do not. A new best
     * says HIGH SCORE in the big font.
     */
    @Test
    fun `the score is in every frame of play and in the picture of the hit, and a new best says HIGH SCORE`() {
        val shows = GameShows(runnerColour = 1, crashColour = 2, scoreColour = 3)
        fun withScore(score: Int, obstacles: List<JumpGame.Obstacle> = emptyList(), stars: Int? = null) =
            gameFrame(JumpGame.Frame(0, 0, obstacles, stars = stars, score = score)).str
        /** The score item of a frame as the sign reads it; null: none. */
        fun n(frame: JumpGame.Frame?): String? = frame?.let { Regex("n\\d*").find(gameFrame(it).str)?.value }
        /** What a show is, beat by beat: frames, or a message's kind and text (and for the big font its colour, its hold and whether it is nudged). */
        fun says(beats: List<Beat>): List<String> = beats.map { beat ->
            when (val message = (beat as? Beat.Once)?.message) {
                null -> "frames"
                is Message.ColorMessage.ChonkySlide ->
                    "big '${message.str}', colour ${message.colorCycle}, ${message.delayMs} ms${if (message.shouldScrollToLastLetter) ", nudged" else ""}"
                else -> "${message.javaClass.simpleName} '${message.str}'"
            }
        }
        val last = JumpGame.Frame(0, 0, listOf(JumpGame.Obstacle(1, 9)), stars = 17)
        val rows = listOf(
            row("as written, without leading zeros, 999 at the most: 0, 9, 10, 99, 100, 999, 1,000, 12,345",
                listOf("00n0", "00n9", "00n10", "00n99", "00n100", "00n999", "00n999", "00n999")) {
                listOf(0, 9, 10, 99, 100, 999, 1_000, 12_345).map { withScore(it) }
            },
            row("beside obstacles and the starfield", listOf("00a-4c51s59n7", "00b30s0n120")) {
                listOf(withScore(7, listOf(JumpGame.Obstacle(0, -4), JumpGame.Obstacle(2, 51)), stars = 59), withScore(120, listOf(JumpGame.Obstacle(1, 30)), stars = 0))
            },
            row("the frames of a game carry the score it has: at its start, and once its first post is cleared", listOf("n0", "n1")) {
                val game = game(JumpGame.Rules(obstacles = listOf(JumpGame.Rules().obstacles[1])))
                val first = n(game.frame(0))
                game.jump(2_110)
                listOf(first, n(game.frame(3_000)))
            },
            row("the picture of the hit at 0 and 650 ms, after a game of 12 that is not a new best and after one that is", List(4) { "n12" }) {
                listOf(shows.over(12, 30, last), shows.newBest(12, last)).flatMap { show -> listOf(0L, 650).map { n((show.first() as Beat.Frames).at(it)) } }
            },
            row("no score in the run-in (at 0 and 499 ms) nor in the new best's hop (at 0 and 1,999 ms)", List(4) { null }) {
                val runIn = shows.intro().last() as Beat.Frames
                val hop = shows.newBest(12, last)[4] as Beat.Frames
                listOf(runIn.at(0), runIn.at(499), hop.at(0), hop.at(1_999)).map { n(it) }
            },
            // HIGH SCORE is wider than a face: the sign holds its start, slides it to its end as it does a chonky ad, and holds that a second
            row("a new best: the hit, the explosion, HIGH SCORE in the big font, the score in it, the hop, the stars",
                listOf("frames", "Explosion 'X'", "big 'HIGH SCORE', colour 3, 1000 ms", "big '  12  ', colour 3, 1500 ms", "frames", "Starfield ''")) {
                says(shows.newBest(12, last))
            },
            row("not a new best: as it was, GAME OVER, the score and BEST",
                listOf("frames", "Explosion 'X'", "CustomFlashyAnnouncement 'GAME${Message.VT}OVER'", "OneByOneMessage 'SCORE 12'", "OneByOneMessage 'BEST 30'", "Starfield ''")) {
                says(shows.over(12, 30, last))
            },
        )

        assertEquals(emptyList(), wrong(rows))
    }

    /**
     * THE TREAT (es-osx): now and then a box to eat at the top of the face, `t` and its left column
     * on the wire (the contract note's version 8). A jump that meets it on the way up eats it, +3.
     * About one in three is a dilemma: the jump that eats it loses to the obstacle behind it.
     */
    @Test
    fun `a treat comes now and then, a jump that meets it on the way up eats it for 3, and a third are a dilemma`() {
        val nothingToHit = listOf(JumpGame.Shape(listOf(0)))
        /**
         * A game of [rules], with the button pressed at [presses]: its frames at every tick up to [untilMs] of it. A
         * [secondGame] is played after a whole first game on the same [JumpGame], nobody pressing in that.
         */
        fun frames(rules: JumpGame.Rules, untilMs: Long, presses: List<Long> = emptyList(), seed: Int = 7, secondGame: Boolean = false): List<JumpGame.Frame> {
            val game = JumpGame(rules, Random(seed))
            val from = if (secondGame) rules.longestMs + 1_000 else 0L
            if (secondGame) {
                game.start(0)
                game.frame(rules.longestMs)
            }
            game.start(from)
            val left = presses.sorted().toMutableList()
            return every(10, untilMs).mapNotNull { now ->
                while (left.isNotEmpty() && left.first() <= now) game.jump(from + left.removeAt(0))
                game.frame(from + now)
            }
        }

        /** Each treat of a game, as the run of its frames. */
        fun treats(game: List<JumpGame.Frame>): List<List<JumpGame.Frame>> {
            val runs = mutableListOf<MutableList<JumpGame.Frame>>()
            game.zipWithNext().forEach { (a, b) -> if (b.treat != null) (if (a.treat == null) runs.add(mutableListOf(b)) else runs.last().add(b)) }
            return runs
        }

        /** The same game with only [presses], worked out to [untilMs]: its score then. */
        fun scoreAt(rules: JumpGame.Rules, presses: List<Long>, untilMs: Long): Int {
            val game = JumpGame(rules, Random(7)).also { it.start(0) }
            presses.forEach { game.jump(it) }
            game.frame(untilMs)
            return game.score
        }

        /** At a fixed [speed] and with nothing to hit, one game a press: the treat's columns at the presses that eat it, and for how long one does. */
        fun eatenWhenAt(speed: Double): String {
            val rules = JumpGame.Rules(obstacles = nothingToHit, startSpeed = speed, topSpeed = speed, treatEvery = 3..3)
            // (the world does not depend on the presses: where the first treat is at each tick, from a game nobody plays)
            val world = frames(rules, 20_000)
            val first = world.indexOfFirst { it.treat != null }
            val span = world.drop(first).takeWhile { it.treat != null }
            val eating = span.withIndex().filter { (i, _) ->
                val press = (first + i + 1) * 10L
                frames(rules, press + 1_000, listOf(press)).any { it.effect == 1 }
            }.map { it.value.treat!! }
            return "${eating.min()} to ${eating.max()}, ${eating.size * 10} ms"
        }

        /**
         * With one [kind] of obstacle, the speed starting at [speed] and growing as in a game, the first treat after the
         * third obstacle, placed as a [dilemma] or free. The player jumps the first three obstacles when they are 8
         * columns from the runner. Then: how many presses eat the treat; how many plans that eat it (that press, and
         * for a dilemma any one more after landing) get past the fourth obstacle; and whether leaving it does.
         */
        fun againstTheObstacle(kind: Int, speed: Double, dilemma: Boolean): String {
            val rules = JumpGame.Rules(
                obstacles = listOf(JumpGame.Rules().obstacles[kind]), startSpeed = speed, treatEvery = 3..3, dilemmaShare = if (dilemma) 1.0 else 0.0,
            )
            val presses = play(JumpGame(rules, Random(7)).also { it.start(0) }, every(10, 30_000))
            if (presses.size < 4) return "${"abc"[kind]} from ${speed.toInt()}: the player is hit before the fourth obstacle"
            val firstThree = presses.take(3)
            // (past the fourth obstacle: the first three, the treat's 3 and the fourth)
            val past = 3 + rules.treatScore + 1
            val eating = (firstThree.last() + 900..presses[3] + 600 step 10).filter { press ->
                val game = JumpGame(rules, Random(7)).also { it.start(0) }
                (firstThree + press).forEach { game.jump(it) }
                (press..press + 1_000 step 10).any { game.frame(it)?.effect == 1 }
            }
            val survive = eating.count { press ->
                if (scoreAt(rules, firstThree + press, press + 2_500) >= past) return@count true
                dilemma && (press + 900..press + 2_500 step 10).any { again -> scoreAt(rules, firstThree + press + again, again + 1_500) >= past }
            }
            val said = "${"abc"[kind]} from ${speed.toInt()}: ${if (eating.isEmpty()) "never eaten" else "eaten"}"
            if (!dilemma) return "$said, every jump that eats it clears it: ${survive == eating.size}"
            // (leaving it: the player's own jump over the fourth obstacle, which by the rule cannot eat it)
            val left = scoreAt(rules, presses.take(4), presses[3] + 1_000) == 4
            return "$said, eaten and past it: $survive, left: ${if (left) "cleared" else "not cleared"}"
        }

        /** Three games of three minutes with nothing to hit and nobody pressing (the third after another on the same game). */
        val unplayed = listOf(7, 8, 9).map { frames(JumpGame.Rules(obstacles = nothingToHit), 180_000, seed = it, secondGame = it == 9) }
        /** Three games of three minutes of the real game, played by the player who jumps every obstacle 8 columns from the runner. */
        val played = listOf(7, 8, 9).map { seed ->
            val presses = play(JumpGame(JumpGame.Rules(), Random(seed)).also { it.start(0) }, every(10, 180_000))
            frames(JumpGame.Rules(), 180_000, presses, seed)
        }
        val shows = GameShows(0, 0, 0)

        val rows = listOf(
            // WHEN: after 10 to 15 obstacles, the first and every next. A treat came after the obstacles the runner has passed
            // when the treat is past the runner's left column (the obstacle after it is 4 or more columns behind it)
            row("the obstacles before the first treat and between two, in three games: all 10 to 15, and some of each", listOf(true, true)) {
                val counts = unplayed.map { game -> treats(game).map { frames -> frames.first { it.treat!! <= 1 }.score!! } }
                // (a game without a treat counts as one whose first came after none)
                val apart = counts.flatMap { listOf(it.firstOrNull() ?: 0) + it.zipWithNext { a, b -> b - a } }
                listOf(apart.all { it in 10..15 }, apart.size >= 15 && apart.distinct().size >= 4)
            },
            // WHERE: a free one 4 columns in front of the next obstacle (or 5, by a part of a tick), a dilemma one round(0.77 x
            // speed - 6.5): 17 at 30, 35 at 54. About one in three is a dilemma
            row("each treat's place in front of the next obstacle in those games (4 or 5; or 17 to 36), and the dilemmas' share", listOf(true, true)) {
                val before = unplayed.flatMap { game ->
                    treats(game).map { frames ->
                        val frame = frames.first { it.treat!! <= 20 }
                        frame.obstacles.map { it.column - frame.treat!! }.filter { it > 0 }.minOrNull() ?: 99
                    }
                }
                val dilemmas = before.count { it >= 12 }
                listOf(before.all { it in 4..5 || it in 17..36 }, dilemmas * 1.0 / before.size in 0.2..0.47)
            },
            // STANDING NEVER EATS: nobody presses, no treat is eaten in three whole games
            row("nobody presses: no score goes up by more than 1, and no sparkle", listOf(true, true)) {
                listOf(unplayed.all { game -> game.zipWithNext().all { (a, b) -> b.score!! - a.score!! in 0..1 } }, unplayed.none { game -> game.any { it.effect == 1 } })
            },
            // THE EAT RULE: met first while the runner rises (rise 1 and up, before the top of its jump): pressed earlier, the
            // runner comes down on it; later, it met the runner standing and a jump into it after that does not eat it
            row("the treat's columns at the presses that eat it, at 30, 42 and 54 columns a second", listOf("14 to 18, 130 ms", "14 to 20, 140 ms", "15 to 22, 150 ms")) {
                listOf(eatenWhenAt(30.0), eatenWhenAt(42.0), eatenWhenAt(54.0))
            },
            // +3 ONCE, AT ONCE: the frame of the tick it is eaten carries the new score and no treat; the sparkle for 400 ms
            row("eaten at 30 a second when at column 16: the score's step at that frame, a treat in the frames after, the sparkle's frames, +3s in the game",
                listOf(3, false, 40, 1)) {
                val rules = JumpGame.Rules(obstacles = nothingToHit, startSpeed = 30.0, topSpeed = 30.0, treatEvery = 3..3)
                val press = (frames(rules, 20_000).indexOfFirst { it.treat == 16 } + 1) * 10L
                val game = frames(rules, 20_000, listOf(press))
                val eaten = game.indexOfFirst { it.effect == 1 }
                listOf(
                    game[eaten].score!! - game[eaten - 1].score!!, game.subList(eaten, eaten + 100).any { it.treat != null },
                    game.drop(eaten).takeWhile { it.effect == 1 }.size, game.zipWithNext().count { (a, b) -> b.score!! - a.score!! == 3 },
                )
            },
            // THE COLUMNS: never right of column 46 (its left column 44 at most), it appears there and only goes left until it
            // is off the face and gone (-3 at the least), and never in a column an obstacle has. In three real games, played
            row("in three real games of three minutes: frames that break the treat's columns, and how many treats were seen", listOf(emptyList<String>(), true)) {
                val broken = played.flatMap { game ->
                    game.zipWithNext().mapNotNull { (a, b) ->
                        val t = b.treat ?: return@mapNotNull null
                        val box = t..t + 2
                        when {
                            t > 44 -> "right of 46: $t"
                            t < -3 -> "sent off the face: $t"
                            a.treat == null && t != 44 -> "appears at $t"
                            a.treat != null && t > a.treat -> "goes right: ${a.treat} to $t"
                            b.obstacles.any { o -> (o.column until o.column + JumpGame.Rules().obstacles[o.kind].width).any { it in box } } -> "in an obstacle's column: ${gameFrame(b).str}"
                            else -> null
                        }
                    }
                }
                listOf(broken, played.sumOf { treats(it).size } >= 12)
            },
            // A DILEMMA REALLY IS ONE: no press that eats it gets past the obstacle behind it, nor does it with one more press
            // after landing; leaving it, the obstacle is cleared. For each kind, from the first speed to the top
            row("dilemmas", (0..2).flatMap { kind -> listOf(30, 36, 42, 48, 54).map { "${"abc"[kind]} from $it: eaten, eaten and past it: 0, left: cleared" } }) {
                (0..2).flatMap { kind -> listOf(30.0, 36.0, 42.0, 48.0, 54.0).map { againstTheObstacle(kind, it, dilemma = true) } }
            },
            // AND A FREE ONE CAN BE HAD: every jump that eats it clears the obstacle right behind it
            row("free ones", (0..2).flatMap { kind -> listOf(30, 42, 54).map { "${"abc"[kind]} from $it: eaten, every jump that eats it clears it: true" } }) {
                (0..2).flatMap { kind -> listOf(30.0, 42.0, 54.0).map { againstTheObstacle(kind, it, dilemma = false) } }
            },
            // THE FRAME'S TEXT: `t` and the left column after the obstacles; out of the note's range, none
            row("a frame with a treat, at 44, at -3, at -99, at 100 and at -100", listOf("70a12t30s41n17", "00t44", "00t-3", "00t-99", "00", "00")) {
                listOf(gameFrame(JumpGame.Frame(7, 0, listOf(JumpGame.Obstacle(0, 12)), stars = 41, score = 17, treat = 30)).str) +
                    listOf(44, -3, -99, 100, -100).map { gameFrame(JumpGame.Frame(0, 0, emptyList(), treat = it)).str }
            },
            row("the picture of a hit keeps the treat that was on the face", "00t20p22e2s3n5") {
                gameFrame((shows.over(5, 9, JumpGame.Frame(0, 0, emptyList(), stars = 3, treat = 20)).first() as Beat.Frames).at(0)).str
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
