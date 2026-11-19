package cx.aphex.energysign.message

import cx.aphex.energysign.SaveSteps
import cx.aphex.energysign.replaceContent
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * A one-button jump game for the sign: a runner that stands at the left of a face, low
 * obstacles that come at it from the right, and one button that makes it jump. The game is the
 * app's own: the runner is the sign's Dreamstate icon, the obstacles and the look are ours.
 *
 * The game runs HERE, not on the sign: the sign asks, gets the few numbers of a [Frame], draws
 * it and asks again. Its requests do not come evenly, so nothing here goes by their count: the
 * game is worked out in ticks of [Rules.tickMs] of ELAPSED time since it began, and a request
 * only asks "how does it look now?". And a press counts from the moment it ARRIVED, not from
 * the moment somebody got round to it ([jump]). The same presses at the same moments are the
 * same game, however often and unevenly the sign asks and however late a press is carried out.
 *
 * No Android in here, no thread and no lock: whoever owns the game calls it from one thread and
 * hands in the time.
 */
class JumpGame(val rules: Rules = Rules(), private val random: Random = Random.Default) {

    /** What a shape lights: one number a column, from the left; bit 0 is the bottom row, bit 6 the top row. */
    class Shape(val columns: List<Int>) {
        val width: Int get() = columns.size
    }

    class Rules(
        /** A face is this wide; both faces show the same picture. */
        val faceColumns: Int = 60,
        val rows: Int = 7,
        /**
         * The runner's left column, and what it lights when it stands on the ground: the
         * Dreamstate icon, column by column as the contract note tables it (section 2).
         */
        val runnerAt: Int = 2,
        val runner: Shape = Shape(
            listOf(
                0b0000001, 0b0000011, 0b0000111, 0b0001101, 0b0001011, 0b0001110, 0b0001101,
                0b0011001, 0b0110011, 0b1100110, 0b1001100, 0b1001000, 0b0001000,
            )
        ),
        /** The obstacles, by kind, as the note tables them: a the step, b the post, c the ridge. Low and solid, so that the runner goes over them. */
        val obstacles: List<Shape> = listOf(
            Shape(listOf(0b1, 0b11, 0b1)),
            Shape(listOf(0b111, 0b111)),
            Shape(listOf(0b1, 0b11, 0b111, 0b11, 0b1)),
        ),
        /** A jump: how high its top is, in rows (higher than the display: the runner leaves it at the top edge), and how long it takes. */
        val jumpRows: Double = 9.0,
        val jumpMs: Long = 900,
        /** Columns a second: at the start, at most, and how much is added every second. */
        val startSpeed: Double = 30.0,
        val topSpeed: Double = 54.0,
        val fasterEverySecond: Double = 0.4,
        /** Between two obstacles there is room for a whole jump and this long to press again, and up to [spread] columns more. */
        val reactionMs: Long = 450,
        val spread: Int = 20,
        /** The first obstacle starts this many columns beyond the right edge. */
        val firstAfterColumns: Int = 18,
        val tickMs: Long = 10,
        /** No game is longer than this. */
        val longestMs: Long = 180_000,
        /** The runner's two pictures take turns this often while it is on the ground. */
        val strideMs: Long = 160,
    )

    class Obstacle(val kind: Int, val column: Int)

    /**
     * What the sign draws: how many rows the runner is up, which of its two pictures, the
     * obstacles on the face. For the shows also: the runner's column when it is not its place
     * in the game, the colours of the runner and of the obstacles, and an effect.
     */
    class Frame(
        val rise: Int,
        val picture: Int,
        val obstacles: List<Obstacle>,
        val runnerAt: Int? = null,
        val colours: Pair<Int, Int>? = null,
        val effect: Int = 0,
    )

    enum class End { HIT, TOO_LONG }

    private class Coming(val kind: Int, val shape: Shape, var x: Double, var passed: Boolean = false)

    /** The game at one tick: everything that changes while it is played. */
    private class Moment(
        /** How many ticks of the game have been worked out. */
        var ticks: Long = 0,
        /** The tick at which the runner left the ground; -1 when it is on the ground. */
        var jumpedAt: Long = -1,
        val coming: ArrayList<Coming> = ArrayList(),
        /** Columns still to go before the next obstacle starts, and how many have started. */
        var nextAfter: Double = 0.0,
        var started: Int = 0,
        var score: Int = 0,
        var ended: End? = null,
    ) {
        fun copy() = Moment(ticks, jumpedAt, coming.mapTo(ArrayList()) { Coming(it.kind, it.shape, it.x, it.passed) }, nextAfter, started, score, ended)
    }

    private var began = false
    private var startedAt = 0L

    /** The game as far as it has been worked out. */
    private var at = Moment()

    /**
     * The game as it was when the button was last pressed (or when it began): a press that
     * arrived before the moment the game has been worked out to is put in from here.
     */
    private var lastPress = Moment()

    /** The kind and the extra room of every obstacle so far, by its number: working a stretch out again gives the same obstacles. */
    private val drawn = ArrayList<IntArray>()

    val score: Int get() = at.score

    /** Why the last game ended; null while one runs and before the first. */
    val ended: End? get() = at.ended

    val running: Boolean get() = began && at.ended == null

    /** When the game ended, on the clock that is handed in; only for a game that has. */
    val endedAt: Long get() = startedAt + at.ticks * rules.tickMs

    /** A new game begins at [now]. */
    fun start(now: Long) {
        began = true
        startedAt = now
        drawn.clear()
        at = Moment(nextAfter = rules.firstAfterColumns.toDouble())
        lastPress = at.copy()
    }

    /**
     * The button, pressed at [pressedAt]: THE PRESS COUNTS FROM THEN, however much later this is
     * called and whatever the game has been worked out to (or shown as) in between. When the
     * game is past that moment already, it is taken back to the press before and worked out
     * again with this one in it; a game that had ended after [pressedAt] goes on when the
     * press saves it. True when the runner left the ground for it.
     */
    fun jump(pressedAt: Long): Boolean {
        if (!began || pressedAt < startedAt) return false // (a press of before the game, in its intro, is none of the game's)
        // (a press never counts from before the press before it)
        val tick = ((pressedAt - startedAt) / rules.tickMs).coerceAtLeast(lastPress.ticks)
        val asItWas = at
        if (tick < at.ticks) at = lastPress.copy()
        while (at.ended == null && at.ticks < tick) tick()
        if (at.ended != null || at.jumpedAt >= 0) {
            // no jump (the game had ended by then, or the runner was in the air): nothing has changed
            if (asItWas.ticks > at.ticks) at = asItWas
            return false
        }
        at.jumpedAt = at.ticks
        lastPress = at.copy()
        return true
    }

    /** The game as it is at [now]; null when none runs (it has ended by [now], or never began). */
    fun frame(now: Long): Frame? {
        workOutTo(now)
        return if (running) picture() else null
    }

    /** The picture of the tick the game is at: for a game that has ended, the picture of its end. */
    fun picture(): Frame {
        val stride = if (at.jumpedAt >= 0) 0 else ((at.ticks * rules.tickMs / rules.strideMs) % 2).toInt()
        val onTheFace = at.coming.filter { it.x < rules.faceColumns }.map { Obstacle(it.kind, Math.floor(it.x).toInt()) }
        return Frame(rise().coerceAtMost(rules.rows), stride, onTheFace)
    }

    /**
     * Works the game out up to [now], one tick after the other. However long ago the last call
     * was, this is short: a game in which nobody presses ends at its next obstacle.
     */
    private fun workOutTo(now: Long) {
        while (running && (at.ticks + 1) * rules.tickMs <= now - startedAt) tick()
    }

    private fun tick() {
        val now = at
        now.ticks++
        val elapsedMs = now.ticks * rules.tickMs
        if (now.jumpedAt >= 0 && (now.ticks - now.jumpedAt) * rules.tickMs >= rules.jumpMs) now.jumpedAt = -1 // landed

        val speed = minOf(rules.topSpeed, rules.startSpeed + rules.fasterEverySecond * elapsedMs / 1000.0)
        val moved = speed * rules.tickMs / 1000.0
        val runnerEnd = rules.runnerAt + rules.runner.width
        val each = now.coming.iterator()
        while (each.hasNext()) {
            val obstacle = each.next()
            obstacle.x -= moved
            if (!obstacle.passed && obstacle.x + obstacle.shape.width <= rules.runnerAt) {
                obstacle.passed = true
                now.score++
            }
            if (obstacle.x + obstacle.shape.width <= 0) each.remove()
        }
        now.nextAfter -= moved
        if (now.nextAfter <= 0) {
            while (drawn.size <= now.started) drawn += intArrayOf(random.nextInt(rules.obstacles.size), random.nextInt(rules.spread + 1))
            val (kind, extra) = drawn[now.started++]
            now.coming += Coming(kind, rules.obstacles[kind], rules.faceColumns.toDouble())
            // room for a whole jump and for pressing again, at the speed of the moment
            now.nextAfter = speed * (rules.jumpMs + rules.reactionMs) / 1000.0 + rules.runner.width + extra
        }

        val up = rise()
        val hit = now.coming.any { obstacle ->
            val left = Math.floor(obstacle.x).toInt()
            left < runnerEnd && left + obstacle.shape.width > rules.runnerAt && obstacle.shape.columns.withIndex().any { (i, lit) ->
                val column = left + i - rules.runnerAt
                column in 0 until rules.runner.width && (rules.runner.columns[column] shl up) and lit != 0
            }
        }
        when {
            hit -> now.ended = End.HIT
            elapsedMs >= rules.longestMs -> now.ended = End.TOO_LONG
        }
    }

    /** How many whole rows the runner is above the ground at the tick that was worked out last. */
    private fun rise(): Int {
        if (at.jumpedAt < 0) return 0
        val part = (at.ticks - at.jumpedAt) * rules.tickMs / rules.jumpMs.toDouble()
        return Math.floor(4 * rules.jumpRows * part * (1 - part) + 1e-9).toInt()
    }
}

/**
 * A frame as the message the sign reads. THE ONE PLACE that knows the wire format of a frame:
 * the contract note of the firmware's half (game-contract.md, version 1). The text is the rise
 * (one digit), the picture (one digit), then the obstacles (kind letter and left column), the
 * runner's column when it is not the usual one, the colours and the effect when they are not
 * the usual ones (the sign's own: a blue runner, red obstacles, so a game in play sends none). Whatever a frame holds, what is written here is a frame the sign can read:
 * the sign draws nothing of a frame with a fifth obstacle or a number out of range.
 */
fun gameFrame(frame: JumpGame.Frame): Message.GameFrame {
    val text = StringBuilder().append(frame.rise.coerceIn(0, 7)).append(frame.picture.coerceIn(0, 1))
    frame.obstacles.filter { it.kind in 0..2 && it.column in -99..99 }.take(4).forEach { text.append('a' + it.kind).append(it.column) }
    frame.runnerAt?.let { text.append('r').append(it.coerceIn(-99, 99)) }
    frame.colours?.let { (runner, obstacles) -> text.append('p').append(runner.coerceIn(0, 7)).append(obstacles.coerceIn(0, 7)) }
    if (frame.effect in 1..2) text.append('e').append(frame.effect)
    return Message.GameFrame(text.toString())
}

/** One step of a show: a message of a kind the sign has, shown once; or game frames for [ms], each made for the time since the step began. */
sealed class Beat {
    class Once(val message: Message) : Beat()
    class Frames(val ms: Long, val at: (Long) -> JumpGame.Frame) : Beat()
}

/**
 * The three shows around a game: the intro, game over with the score, and a bigger one for a
 * new best. Each is a row of messages of kinds the sign has and of game frames used as
 * pictures (the runner running in, blinking where it was hit, hopping through the colours).
 * The frames' effects are decoration only: a sign that draws them as none shows the same show.
 * The colours of the messages come from whoever makes this (the app's resources).
 */
class GameShows(private val runnerColour: Int, private val crashColour: Int, private val scoreColour: Int) {
    private fun flash(text: String) = Beat.Once(Message.FlashingAnnouncement.CustomFlashyAnnouncement(text))

    fun intro(): List<Beat> = listOf(
        Beat.Once(Message.ColorMessage.IconInvaders.DREAMSTATE(runnerColour)),
        Beat.Once(Message.ColorMessage.ChonkySlide("READY?", scoreColour, delayMs = 600)),
        flash("JUMP!"),
        // the runner runs in from the left to its place, in half a second, with stars behind it
        Beat.Frames(500) { t -> JumpGame.Frame(0, (t / 100 % 2).toInt(), emptyList(), runnerAt = (-12 + 14 * t / 500).toInt(), effect = 1) },
    )

    /** The picture of the hit ([last], the last frame of the game) stands for a moment, the runner blinking red. */
    private fun hit(last: JumpGame.Frame?) = Beat.Frames(700) { t ->
        JumpGame.Frame(last?.rise ?: 0, 0, last?.obstacles.orEmpty(), colours = (if (t / 100 % 2 == 0L) 2 else 0) to 2, effect = if (t < 100) 2 else 0)
    }

    fun over(score: Int, best: Int, last: JumpGame.Frame?): List<Beat> = listOf(
        hit(last),
        Beat.Once(Message.ColorMessage.IconInvaders.Explosion(crashColour)),
        flash("GAME${Message.VT}OVER"),
        Beat.Once(Message.ColorMessage.OneByOneMessage("SCORE $score", scoreColour)),
        Beat.Once(Message.ColorMessage.OneByOneMessage("BEST $best", runnerColour)),
        Beat.Once(Message.Starfield()),
    )

    fun newBest(score: Int, last: JumpGame.Frame?): List<Beat> = listOf(
        hit(last),
        Beat.Once(Message.ColorMessage.IconInvaders.Explosion(crashColour)),
        flash("NEW${Message.VT}BEST!"),
        // the score in the big font with its colour wave, in the middle of the six places
        Beat.Once(Message.ColorMessage.ChonkySlide(score.toString().let { it.padStart((6 + it.length) / 2).padEnd(6) }, scoreColour, delayMs = 1500)),
        // two seconds of the runner hopping across the face through all the colours, with stars.
        // It sets off in its own colour, blue (6: the sign's colour for the runner when none is sent).
        Beat.Frames(2_000) { t ->
            val hop = Math.abs(Math.sin(t / 140.0)) * 3
            JumpGame.Frame(hop.toInt(), 0, emptyList(), runnerAt = (2 + 45 * t / 2_000).toInt(), colours = (1 + (5 + t / 120) % 7).toInt() to 2, effect = 1)
        },
        Beat.Once(Message.Starfield(stars = 600)),
    )
}

/** What the game keeps across a restart, in the app's files: whether it is switched on, and the best score. Loading never writes. */
class GameStore(private val dir: File, private val warn: (String) -> Unit = {}) {
    fun loadOn(): Boolean = read(SWITCH_FILE_NAME) != "off"

    fun loadBest(): Int = read(BEST_FILE_NAME)?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    @Throws(IOException::class)
    fun saveOn(on: Boolean) = write(SWITCH_FILE_NAME, if (on) "on" else "off")

    @Throws(IOException::class)
    fun saveBest(best: Int) = write(BEST_FILE_NAME, best.toString())

    private fun read(name: String): String? =
        try {
            File(dir, name).takeIf { it.exists() }?.readText()?.trim()
        } catch (e: Throwable) {
            null
        }

    private fun write(name: String, text: String) =
        File(dir, name).replaceContent(SaveSteps(), warn) { it.write(text.toByteArray()) }

    companion object {
        const val SWITCH_FILE_NAME = "game.txt"
        const val BEST_FILE_NAME = "game-best.txt"
    }
}

/**
 * The game as the sign gets it: nothing; the intro; the game, frame after frame; a closing show.
 * It ANSWERS the sign's requests itself ([answer]) and takes nothing from any queue: whatever
 * else is waiting for the sign waits, and is shown after the game as if there had been none.
 *
 * One thread calls this (the one that answers the sign) and hands in the time: for a request of
 * the sign the time of the request, for everything else THE TIME THE EVENT ARRIVED, which may
 * be well before the call. Every way in first brings the game to that moment ([settle]), so a
 * game that ended by the clock while nobody asked has its end before the event is looked at.
 */
class GameOnTheSign(
    private val shows: GameShows,
    private var best: Int = 0,
    on: Boolean = true,
    private val say: (String) -> Unit = {},
    /** The best has changed (a new best, or none any more): for whoever keeps it. */
    private val bestIs: (Int) -> Unit = {},
    private val game: JumpGame = JumpGame(),
) {
    private enum class Stage { NONE, INTRO, PLAY, CLOSING }

    private var stage = Stage.NONE

    /** What is left of the intro or of the closing show; when its first step began, if that is one of frames; the message the sign was given last. */
    private val show = ArrayDeque<Beat>()
    private var beatBegan = -1L
    private var given: Message? = null

    /** When the press arrived that the next frame answers; -1 when none is waiting for its frame. */
    private var pressedAt = -1L
    private var saidItIsOff = false

    var on: Boolean = on
        private set

    /** Whether a game is being played right now (not its intro, not a closing show). */
    val playing: Boolean get() = stage == Stage.PLAY

    /** Whether anything of a game is on: its intro, the game, or a closing show. */
    val busy: Boolean get() = stage != Stage.NONE

    /** The game as it is at [at]: one that was played and had ended by then has its closing show now (and its best). */
    private fun settle(at: Long) {
        if (stage == Stage.PLAY && game.frame(at) == null) closing()
    }

    /**
     * Whether the game was closed for an end that came AFTER [at]: the sign was answered for a
     * later moment before an event that arrived at [at] is carried out. For that event the
     * game is still being played, and what the closing did to the best is not done yet.
     */
    private fun closedAfter(at: Long) = stage == Stage.CLOSING && at < game.endedAt

    /** The best as it was before the game was closed. */
    private var bestBefore = 0

    private fun takeBackTheBest() {
        if (best == bestBefore) return
        best = bestBefore
        bestIs(best)
    }

    /** The switch. Off ends whatever of a game there is. */
    fun switch(to: Boolean, at: Long) {
        on = to
        saidItIsOff = false
        if (!to) end("it was switched off", at)
        say("Game: switched ${if (to) "on" else "off"}.")
    }

    /** There is no best any more: the next score above none is one. */
    fun forgetBest(at: Long) {
        settle(at)
        // (a game that ended after this arrived is the first score after the reset)
        best = if (closedAfter(at)) game.score else 0
        bestBefore = 0
        bestIs(best)
        say("Game: the best is reset to none.")
    }

    /**
     * A game is asked for. It begins with its intro, at the sign's next request, unless one is
     * on already (its intro or the game itself: nothing starts twice). True when one begins.
     */
    fun start(at: Long): Boolean {
        settle(at)
        if (closedAfter(at)) return false // (a game was being played when this arrived: nothing starts twice)
        if (!on) {
            if (!saidItIsOff) say("Game: it is switched off (!game on); nothing is started.")
            saidItIsOff = true
            return false
        }
        if (stage == Stage.INTRO || stage == Stage.PLAY) return false
        show(shows.intro()) // a closing show that was still on makes way
        stage = Stage.INTRO
        say("Game: start.")
        return true
    }

    private fun show(beats: List<Beat>) {
        show.clear()
        show += beats
        beatBegan = -1
        given = null
    }

    /**
     * The button, pressed at [at]: a jump while a game is played, nothing otherwise. The press
     * counts from [at] ([JumpGame.jump]). Also when the sign was answered for a later moment
     * in between and the game was closed there for a hit this press prevents: the game goes on.
     */
    fun jump(at: Long) {
        val closedTooSoon = closedAfter(at)
        if (stage != Stage.PLAY && !closedTooSoon) return
        if (!game.jump(at)) return
        if (closedTooSoon) {
            // Played on from the press: its end, its score and its best are those it comes to now.
            takeBackTheBest()
            stage = Stage.PLAY
            show(emptyList())
            say("Game: goes on, a press had arrived before the hit.")
        }
        pressedAt = at
    }

    /** Somebody else takes the sign (the chooser, a typist) at [at], or the game is switched off: no game, no show. A game that had ended before [at] keeps its score. */
    fun end(why: String, at: Long) {
        settle(at)
        if (closedAfter(at)) takeBackTheBest() // (it was ended by this, before its hit: no score)
        if (stage == Stage.NONE) return
        stage = Stage.NONE
        show(emptyList())
        pressedAt = -1
        say("Game: ended, $why.")
    }

    /** What the sign gets for a request at [now]; null when the game has nothing for it. */
    fun answer(now: Long): Message? {
        if (stage == Stage.PLAY) {
            game.frame(now)?.let { frame ->
                if (pressedAt >= 0) say("Game: jump, ${now - pressedAt} ms from the press to its frame.")
                pressedAt = -1
                return gameFrame(frame)
            }
            closing()
        }
        while (stage != Stage.NONE) {
            when (val beat = show.firstOrNull()) {
                is Beat.Once -> {
                    show.removeFirst()
                    given = beat.message
                    return beat.message
                }

                is Beat.Frames -> {
                    if (beatBegan < 0) beatBegan = now
                    if (now - beatBegan < beat.ms) return gameFrame(beat.at(now - beatBegan))
                    show.removeFirst()
                    beatBegan = -1
                }

                null -> {
                    given = null
                    if (stage == Stage.INTRO) {
                        stage = Stage.PLAY
                        game.start(now)
                        return answer(now)
                    }
                    stage = Stage.NONE
                }
            }
        }
        return null
    }

    /** The game that was played has ended: its closing show begins. */
    private fun closing() {
        val score = game.score
        val isBest = score > best
        say("Game: over, score $score (${if (isBest) "a new best, it was $best" else "best $best"}), ${game.ended}.")
        bestBefore = best
        if (isBest) {
            best = score
            bestIs(score)
        }
        // (the picture of the hit is the game's at the tick of the hit, not what the sign last asked for)
        show(if (isBest) shows.newBest(score, game.picture()) else shows.over(score, best, game.picture()))
        stage = Stage.CLOSING
        pressedAt = -1
    }

    /**
     * [message] was given to the sign and could not be written. True when it was the game's:
     * a message of a show is the next of the show again; a frame is of its moment and is not
     * kept (the next request gets the frame of then).
     */
    fun notWritten(message: Message): Boolean {
        if (message is Message.GameFrame) return true
        if (message !== given) return false
        show.addFirst(Beat.Once(message))
        given = null
        return true
    }
}
