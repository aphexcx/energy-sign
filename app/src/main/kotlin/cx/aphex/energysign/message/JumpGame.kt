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
 * only asks "how does it look now?". The same presses at the same moments are the same game,
 * however often and however unevenly the sign asks.
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

    var score: Int = 0
        private set

    /** Why the last game ended; null while one runs and before the first. */
    var ended: End? = null
        private set

    var running: Boolean = false
        private set

    private class Coming(val kind: Int, val shape: Shape, var x: Double, var passed: Boolean = false)

    private var startedAt = 0L
    /** How many ticks of the game have been worked out. */
    private var ticks = 0L
    /** The tick at which the runner left the ground; -1 when it is on the ground. */
    private var jumpedAt = -1L
    private val coming = ArrayList<Coming>()
    /** Columns still to go before the next obstacle starts. */
    private var nextAfter = 0.0

    /** A new game begins at [now]. */
    fun start(now: Long) {
        running = true
        ended = null
        score = 0
        startedAt = now
        ticks = 0
        jumpedAt = -1
        coming.clear()
        nextAfter = rules.firstAfterColumns.toDouble()
    }

    /** The button, at [now]. True when the runner left the ground for it. */
    fun jump(now: Long): Boolean {
        workOutTo(now)
        if (!running || jumpedAt >= 0) return false
        jumpedAt = ticks
        return true
    }

    /** The game as it is at [now]; null when none runs (it has ended by [now], or never began). */
    fun frame(now: Long): Frame? {
        workOutTo(now)
        if (!running) return null
        val stride = if (jumpedAt >= 0) 0 else ((ticks * rules.tickMs / rules.strideMs) % 2).toInt()
        val onTheFace = coming.filter { it.x < rules.faceColumns }.map { Obstacle(it.kind, Math.floor(it.x).toInt()) }
        return Frame(rise().coerceAtMost(rules.rows), stride, onTheFace)
    }

    /**
     * Works the game out up to [now], one tick after the other. However long ago the last call
     * was, this is short: a game in which nobody presses ends at its next obstacle.
     */
    private fun workOutTo(now: Long) {
        while (running && (ticks + 1) * rules.tickMs <= now - startedAt) tick()
    }

    private fun tick() {
        ticks++
        val elapsedMs = ticks * rules.tickMs
        if (jumpedAt >= 0 && (ticks - jumpedAt) * rules.tickMs >= rules.jumpMs) jumpedAt = -1 // landed

        val speed = minOf(rules.topSpeed, rules.startSpeed + rules.fasterEverySecond * elapsedMs / 1000.0)
        val moved = speed * rules.tickMs / 1000.0
        val runnerEnd = rules.runnerAt + rules.runner.width
        val each = coming.iterator()
        while (each.hasNext()) {
            val obstacle = each.next()
            obstacle.x -= moved
            if (!obstacle.passed && obstacle.x + obstacle.shape.width <= rules.runnerAt) {
                obstacle.passed = true
                score++
            }
            if (obstacle.x + obstacle.shape.width <= 0) each.remove()
        }
        nextAfter -= moved
        if (nextAfter <= 0) {
            val kind = random.nextInt(rules.obstacles.size)
            coming += Coming(kind, rules.obstacles[kind], rules.faceColumns.toDouble())
            // room for a whole jump and for pressing again, at the speed of the moment
            nextAfter = speed * (rules.jumpMs + rules.reactionMs) / 1000.0 + rules.runner.width + random.nextInt(rules.spread + 1)
        }

        val up = rise()
        val hit = coming.any { obstacle ->
            val left = Math.floor(obstacle.x).toInt()
            left < runnerEnd && left + obstacle.shape.width > rules.runnerAt && obstacle.shape.columns.withIndex().any { (i, lit) ->
                val column = left + i - rules.runnerAt
                column in 0 until rules.runner.width && (rules.runner.columns[column] shl up) and lit != 0
            }
        }
        when {
            hit -> end(End.HIT)
            elapsedMs >= rules.longestMs -> end(End.TOO_LONG)
        }
    }

    /** How many whole rows the runner is above the ground at the tick that was worked out last. */
    private fun rise(): Int {
        if (jumpedAt < 0) return 0
        val part = (ticks - jumpedAt) * rules.tickMs / rules.jumpMs.toDouble()
        return Math.floor(4 * rules.jumpRows * part * (1 - part) + 1e-9).toInt()
    }

    private fun end(why: End) {
        running = false
        ended = why
    }
}

/**
 * A frame as the message the sign reads. THE ONE PLACE that knows the wire format of a frame:
 * the contract note of the firmware's half (game-contract.md, version 1). The text is the rise
 * (one digit), the picture (one digit), then the obstacles (kind letter and left column), the
 * runner's column when it is not the usual one, the colours and the effect when they are not
 * the usual ones. Whatever a frame holds, what is written here is a frame the sign can read:
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
        // two seconds of the runner hopping across the face through all the colours, with stars
        Beat.Frames(2_000) { t ->
            val hop = Math.abs(Math.sin(t / 140.0)) * 3
            JumpGame.Frame(hop.toInt(), 0, emptyList(), runnerAt = (2 + 45 * t / 2_000).toInt(), colours = (1 + t / 120 % 7).toInt() to 2, effect = 1)
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
 * One thread calls this (the one that answers the sign) and hands in the time.
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

    /** The last frame of the game that is played, for the picture of the hit. */
    private var last: JumpGame.Frame? = null

    /** When the press arrived that the next frame answers; -1 when none is waiting for its frame. */
    private var pressedAt = -1L
    private var saidItIsOff = false

    var on: Boolean = on
        private set

    /** Whether a game is being played right now (not its intro, not a closing show). */
    val playing: Boolean get() = stage == Stage.PLAY

    /** Whether anything of a game is on: its intro, the game, or a closing show. */
    val busy: Boolean get() = stage != Stage.NONE

    /** The switch. Off ends whatever of a game there is. */
    fun switch(to: Boolean) {
        on = to
        saidItIsOff = false
        if (!to) end("it was switched off")
        say("Game: switched ${if (to) "on" else "off"}.")
    }

    /** There is no best any more: the next score above none is one. */
    fun forgetBest() {
        best = 0
        bestIs(0)
        say("Game: the best is reset to none.")
    }

    /**
     * A game is asked for. It begins with its intro, at the sign's next request, unless one is
     * on already (its intro or the game itself: nothing starts twice). True when one begins.
     */
    fun start(): Boolean {
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

    /** The button, pressed at [arrivedAt] and carried out [now]: a jump while a game is played, nothing otherwise. */
    fun jump(now: Long, arrivedAt: Long = now) {
        if (stage == Stage.PLAY && game.jump(now)) pressedAt = arrivedAt
    }

    /** Somebody else takes the sign (the chooser, a typist), or the game is switched off: no game, no show, no score. */
    fun end(why: String) {
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
                last = frame
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
                        last = null
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
        if (isBest) {
            best = score
            bestIs(score)
        }
        show(if (isBest) shows.newBest(score, last) else shows.over(score, best, last))
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
