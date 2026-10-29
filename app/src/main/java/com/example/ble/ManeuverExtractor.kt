package com.example.ble

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import com.example.model.TurnTypeConstant
import java.util.Locale

/**
 * Core maneuver actions defined in `Google_Maps_Navigation_Commands_and_Maneuvers.xlsx` (`Command_Catalog`).
 */
enum class ManeuverAction {
    CONTINUE_STRAIGHT,
    TURN,
    KEEP,
    FORK,
    RAMP,
    HIGHWAY_EXIT,
    MERGE,
    ROUNDABOUT,
    U_TURN,
    ARRIVE,
    REROUTE,
    FERRY,
    UNKNOWN
}

/**
 * Normalized maneuver lateral/heading direction.
 */
enum class Direction {
    LEFT,
    RIGHT,
    STRAIGHT,
    NONE
}

/**
 * Turn curvature / geometry modifier.
 */
enum class TurnModifier {
    SLIGHT,
    NORMAL,
    SHARP,
    U_TURN,
    NONE
}

/**
 * Parsed trip summary from `Notification.EXTRA_SUB_TEXT` (typically split by middle-dot `·`, e.g. `"12 min · 4.2 km · 10:45 PM"`).
 */
data class TripSummary(
    val duration: String,
    val remainingDistance: String,
    val eta: String,
    val durationHours: Int = 0,
    val durationMinutes: Int = 0
)

/**
 * Canonical specification entry from Sheet 1 (`Command_Catalog`, Cmd ID 1..27).
 */
data class CanonicalManeuverSpec(
    val cmdId: Int,
    val category: String,
    val sampleMapsText: String,
    val arrowIconDescription: String,
    val action: ManeuverAction,
    val direction: Direction,
    val modifier: TurnModifier,
    val parametersTarget: String,
    val downstreamBehavior: String
)

/**
 * Unified turn-by-turn command model produced by [ManeuverExtractor].
 */
data class TurnCommand(
    val action: ManeuverAction,
    val direction: Direction,
    val modifier: TurnModifier,
    val roundaboutExit: Int? = null,
    val exitNumber: String? = null,
    val distanceRemaining: String = "",
    val rawInstruction: String = "",
    val tripSummary: TripSummary? = null,
    val commandId: Int? = null,
    val targetStreet: String = "",
    val downstreamBehavior: String = ""
) {
    /**
     * Maps this [TurnCommand] to the corresponding TVS iQube BLE cluster [TurnTypeConstant] (`0x00..0x48`).
     */
    fun toTurnTypeConstant(): TurnTypeConstant {
        return when (action) {
            ManeuverAction.ARRIVE -> TurnTypeConstant.DESTINATION_REACHED
            ManeuverAction.U_TURN -> {
                if (direction == Direction.RIGHT) TurnTypeConstant.RIGHT_U_TURN else TurnTypeConstant.U_TURN
            }
            ManeuverAction.ROUNDABOUT -> {
                when (roundaboutExit) {
                    1 -> TurnTypeConstant.ROUNDABOUT_EXIT_1
                    2 -> TurnTypeConstant.ROUNDABOUT_EXIT_2
                    3 -> TurnTypeConstant.ROUNDABOUT_EXIT_3
                    5 -> TurnTypeConstant.ROUNDABOUT_LEFT
                    6 -> TurnTypeConstant.ROUNDABOUT_EXIT_6_RIGHT_2
                    7 -> TurnTypeConstant.ROUNDABOUT_EXIT_7_RIGHT_1
                    else -> if (direction == Direction.LEFT) TurnTypeConstant.ROUNDABOUT_LEFT else TurnTypeConstant.ROUNDABOUT
                }
            }
            ManeuverAction.TURN -> {
                when (direction) {
                    Direction.LEFT -> when (modifier) {
                        TurnModifier.SHARP -> TurnTypeConstant.TURN_SHARPLY_LEFT
                        TurnModifier.SLIGHT -> TurnTypeConstant.TURN_SLIGHTLY_LEFT
                        TurnModifier.U_TURN -> TurnTypeConstant.U_TURN
                        else -> TurnTypeConstant.TURN_LEFT
                    }
                    Direction.RIGHT -> when (modifier) {
                        TurnModifier.SHARP -> TurnTypeConstant.TURN_SHARPLY_RIGHT
                        TurnModifier.SLIGHT -> TurnTypeConstant.TURN_SLIGHTLY_RIGHT
                        TurnModifier.U_TURN -> TurnTypeConstant.RIGHT_U_TURN
                        else -> TurnTypeConstant.TURN_RIGHT
                    }
                    else -> TurnTypeConstant.CONTINUE_STRAIGHT
                }
            }
            ManeuverAction.KEEP -> {
                if (direction == Direction.LEFT) TurnTypeConstant.KEEP_LEFT else TurnTypeConstant.KEEP_RIGHT
            }
            ManeuverAction.FORK -> {
                if (direction == Direction.LEFT) TurnTypeConstant.FORK_LEFT else TurnTypeConstant.FORK_RIGHT
            }
            ManeuverAction.RAMP -> {
                if (direction == Direction.LEFT) TurnTypeConstant.FORK_LEFT else TurnTypeConstant.FORK_RIGHT
            }
            ManeuverAction.HIGHWAY_EXIT -> {
                if (direction == Direction.LEFT) TurnTypeConstant.HIGHWAY_EXIT_LEFT else TurnTypeConstant.FORK_RIGHT
            }
            ManeuverAction.MERGE -> {
                TurnTypeConstant.HIGHWAY_ENTER_LEFT
            }
            ManeuverAction.FERRY -> {
                TurnTypeConstant.TAKE_FERRY
            }
            ManeuverAction.CONTINUE_STRAIGHT -> {
                TurnTypeConstant.CONTINUE_STRAIGHT
            }
            ManeuverAction.REROUTE,
            ManeuverAction.UNKNOWN -> {
                TurnTypeConstant.CONTINUE_STRAIGHT
            }
        }
    }
}

/**
 * Two-stage Google Maps turn-by-turn maneuver extraction pipeline implementing the exact
 * `Google_Maps_Navigation_Commands_and_Maneuvers.xlsx` specification:
 * - Sheet 1: `Command_Catalog` (27 canonical commands across 8 core actions)
 * - Sheet 2: `Notification_Schema` (`EXTRA_TITLE`, `EXTRA_TEXT`, `EXTRA_SUB_TEXT`, `EXTRA_LARGE_ICON`)
 * - Sheet 3: `Parsing_Rules` (Stage 1 prioritized text regexes + Stage 2 24x24 pixel-density visual fallback)
 */
object ManeuverExtractor {

    /**
     * Complete 27-command catalog from `Command_Catalog`.
     */
    val COMMAND_CATALOG: List<CanonicalManeuverSpec> = listOf(
        CanonicalManeuverSpec(1, "Basic Turns", "Turn left onto MG Road / Left on Main St", "90° arrow pointing left", ManeuverAction.TURN, Direction.LEFT, TurnModifier.NORMAL, "Street name", "Display Left arrow; Audio prompt 'Turn left'; Haptic 2 short pulses"),
        CanonicalManeuverSpec(2, "Basic Turns", "Turn right onto 100 Feet Rd / Right on 5th Ave", "90° arrow pointing right", ManeuverAction.TURN, Direction.RIGHT, TurnModifier.NORMAL, "Street name", "Display Right arrow; Audio prompt 'Turn right'; Haptic 2 short pulses"),
        CanonicalManeuverSpec(3, "Basic Turns", "Continue straight onto Outer Ring Rd / Straight", "Vertical arrow pointing straight up", ManeuverAction.CONTINUE_STRAIGHT, Direction.STRAIGHT, TurnModifier.NONE, "Street name", "Display Straight arrow; Confirm route progress; Silent update"),
        CanonicalManeuverSpec(4, "Curved Turns", "Slight left onto Flyover / Bear left", "45° angled arrow pointing top-left", ManeuverAction.TURN, Direction.LEFT, TurnModifier.SLIGHT, "Street / Ramp name", "Display Slight-Left arrow; Audio 'Keep slight left'; Gentle single pulse"),
        CanonicalManeuverSpec(5, "Curved Turns", "Slight right toward IT Park / Bear right", "45° angled arrow pointing top-right", ManeuverAction.TURN, Direction.RIGHT, TurnModifier.SLIGHT, "Street / Ramp name", "Display Slight-Right arrow; Audio 'Keep slight right'; Gentle single pulse"),
        CanonicalManeuverSpec(6, "Curved Turns", "Sharp left onto Old Post Office Rd", "Hairpin arrow curving hard left (>120°)", ManeuverAction.TURN, Direction.LEFT, TurnModifier.SHARP, "Street name", "Display Sharp-Left warning; Audio 'Sharp left turn ahead'; Strong double pulse"),
        CanonicalManeuverSpec(7, "Curved Turns", "Sharp right onto Service Rd", "Hairpin arrow curving hard right (>120°)", ManeuverAction.TURN, Direction.RIGHT, TurnModifier.SHARP, "Street name", "Display Sharp-Right warning; Audio 'Sharp right turn ahead'; Strong double pulse"),
        CanonicalManeuverSpec(8, "U-Turns", "Make a U-turn / Make a U-turn onto NH-44", "180° loop arrow curving counter-clockwise", ManeuverAction.U_TURN, Direction.LEFT, TurnModifier.U_TURN, "Target street", "Display U-Turn icon; Audio 'Make a U-turn when safe'; Urgent pattern"),
        CanonicalManeuverSpec(9, "U-Turns", "Make a U-turn at the traffic light", "180° loop arrow curving clockwise", ManeuverAction.U_TURN, Direction.RIGHT, TurnModifier.U_TURN, "Target street", "Display U-Turn icon; Audio 'Make a U-turn when safe'; Urgent pattern"),
        CanonicalManeuverSpec(10, "Lanes & Forks", "Keep left to stay on Express Highway", "Two road paths diverging, arrow on left path", ManeuverAction.KEEP, Direction.LEFT, TurnModifier.NONE, "Route continuation", "Highlight left lane guide; Audio 'Keep left'; Single haptic tick"),
        CanonicalManeuverSpec(11, "Lanes & Forks", "Keep right toward City Center", "Two road paths diverging, arrow on right path", ManeuverAction.KEEP, Direction.RIGHT, TurnModifier.NONE, "Route continuation", "Highlight right lane guide; Audio 'Keep right'; Single haptic tick"),
        CanonicalManeuverSpec(12, "Lanes & Forks", "At the fork, stay left toward Airport", "Y-junction fork with left branch highlighted", ManeuverAction.FORK, Direction.LEFT, TurnModifier.NONE, "Destination split", "Show fork split; Audio 'Stay left at the fork'; Alert driver"),
        CanonicalManeuverSpec(13, "Lanes & Forks", "At the fork, keep right for Downtown", "Y-junction fork with right branch highlighted", ManeuverAction.FORK, Direction.RIGHT, TurnModifier.NONE, "Destination split", "Show fork split; Audio 'Stay right at the fork'; Alert driver"),
        CanonicalManeuverSpec(14, "Highway & Ramps", "Take the ramp onto I-95 North / Take entry ramp", "Angled curved ramp entering highway", ManeuverAction.RAMP, Direction.RIGHT, TurnModifier.NONE, "Highway identifier", "Display Ramp icon; Audio 'Take the on-ramp'; Speed adaptation alert"),
        CanonicalManeuverSpec(15, "Highway & Ramps", "Take exit 14B toward Airport Rd", "Diverging ramp leaving highway with exit sign", ManeuverAction.HIGHWAY_EXIT, Direction.RIGHT, TurnModifier.NONE, "Exit Number: '14B'", "Display Exit Badge [14B]; Countdown distance; Audio prompt"),
        CanonicalManeuverSpec(16, "Highway & Ramps", "Take the exit on the left toward Ring Rd", "Diverging ramp on left side", ManeuverAction.HIGHWAY_EXIT, Direction.LEFT, TurnModifier.NONE, "Target highway", "Display Exit Left indicator; Warning for unusual left exit"),
        CanonicalManeuverSpec(17, "Highway & Ramps", "Merge onto Central Expressway / Merge", "Lane merging into main traffic flow", ManeuverAction.MERGE, Direction.RIGHT, TurnModifier.NONE, "Highway name", "Display Merge indicator; Audio 'Merge onto highway'; Traffic caution"),
        CanonicalManeuverSpec(18, "Roundabouts", "At the roundabout, take the 1st exit onto Elm St", "Circular roundabout icon with 1st exit arrow", ManeuverAction.ROUNDABOUT, Direction.NONE, TurnModifier.NONE, "Exit Index: 1", "Display Roundabout [1]; Audio 'Take the first exit'; Prepare turning"),
        CanonicalManeuverSpec(19, "Roundabouts", "At the roundabout, take the 2nd exit onto Broadway", "Circular roundabout icon with 2nd exit arrow", ManeuverAction.ROUNDABOUT, Direction.NONE, TurnModifier.NONE, "Exit Index: 2", "Display Roundabout [2]; Audio 'Take the second exit'; Prepare turning"),
        CanonicalManeuverSpec(20, "Roundabouts", "At the roundabout, take the 3rd exit onto Grand Ave", "Circular roundabout icon with 3rd exit arrow", ManeuverAction.ROUNDABOUT, Direction.NONE, TurnModifier.NONE, "Exit Index: 3", "Display Roundabout [3]; Audio 'Take the third exit'; Prepare turning"),
        CanonicalManeuverSpec(21, "Roundabouts", "At the roundabout, take the 4th exit / 5th exit", "Circular roundabout icon with 4th/5th exit arrow", ManeuverAction.ROUNDABOUT, Direction.NONE, TurnModifier.NONE, "Exit Index: 4+", "Display Roundabout [4+]; Count exits carefully"),
        CanonicalManeuverSpec(22, "State & Arrival", "Head north on 1st Main Rd / Head east", "Compass heading arrow or straight line", ManeuverAction.CONTINUE_STRAIGHT, Direction.STRAIGHT, TurnModifier.NONE, "Initial Direction", "Initialize navigation session; Calibrate compass/heading"),
        CanonicalManeuverSpec(23, "State & Arrival", "You have arrived / You've arrived at your destination", "Checkered flag or destination pin icon", ManeuverAction.ARRIVE, Direction.NONE, TurnModifier.NONE, "Destination point", "Display Arrival screen; Play victory/finish chime; Complete trip log"),
        CanonicalManeuverSpec(24, "State & Arrival", "Your destination is on the left", "Pin icon with left-side arrival badge", ManeuverAction.ARRIVE, Direction.LEFT, TurnModifier.NONE, "Side of street: Left", "Highlight destination on left; Audio 'Destination is on the left'"),
        CanonicalManeuverSpec(25, "State & Arrival", "Your destination is on the right", "Pin icon with right-side arrival badge", ManeuverAction.ARRIVE, Direction.RIGHT, TurnModifier.NONE, "Side of street: Right", "Highlight destination on right; Audio 'Destination is on the right'"),
        CanonicalManeuverSpec(26, "State & Arrival", "Rerouting... / Finding best route...", "Recalculating icon / rotating circle", ManeuverAction.REROUTE, Direction.NONE, TurnModifier.NONE, "Route computation", "Pause turn guidance; Display 'Rerouting' status; Inhibit turn audio"),
        CanonicalManeuverSpec(27, "Special Transit", "Take the ferry to South Island / Board ferry", "Boat / Ferry transit icon", ManeuverAction.FERRY, Direction.NONE, TurnModifier.NONE, "Ferry route name", "Display Ferry boarding icon; Hold updates until disembarkation")
    )

    // --- Stage 1 Prioritized Regex Patterns (Sheet 3: Parsing_Rules) ---
    val REGEX_ARRIVAL = Regex("""(?i)(?:arrived|destination\s+(?:is|will\s+be)\s+on\s+the\s+(left|right)|you\s+have\s+arrived|you've\s+arrived)""")
    val REGEX_REROUTE = Regex("""(?i)(?:rerouting|finding\s+best\s+route)""")
    val REGEX_ROUNDABOUT = Regex("""(?i)roundabout.*?(?:take\s+the\s+)?(\d+)(?:st|nd|rd|th)?\s+exit""")
    val REGEX_ROUNDABOUT_GENERIC = Regex("""(?i)\b(?:roundabout|rotary|traffic\s+circle)\b""")
    val REGEX_U_TURN = Regex("""(?i)(?:make\s+a\s+u-turn|\b(?:right\s+u-turn|u-turn\s+right|u-turn|u\s+turn)\b)""")
    val REGEX_HIGHWAY_EXIT_LEFT = Regex("""(?i)(?:take\s+the\s+)?exit\s+on\s+the\s+left|\bleft\s+exit\b""")
    val REGEX_HIGHWAY_EXIT = Regex("""(?i)(?:take\s+)?exit\s+([A-Za-z0-9\-]+)""")
    val REGEX_SHARP_TURN = Regex("""(?i)sharp(?:ly)?\s+(left|right)""")
    val REGEX_SLIGHT_TURN = Regex("""(?i)(?:slight(?:ly)?\s+(left|right)|bear\s+(left|right))""")
    val REGEX_STANDARD_TURN = Regex("""(?i)(?:(?:turn\s+)?(left|right)\s+(?:onto|on|toward|towards|at)\b|\bturn\s+(left|right)\b|\bmake\s+a\s+(left|right)\b|\btake\s+(?:the\s+)?(left|right)\b)""")
    val REGEX_KEEP_FORK = Regex("""(?i)(?:(at\s+the\s+fork(?:,\s*(?:stay|keep))?|fork)\s+(left|right)|(keep|stay)\s+(left|right))""")
    val REGEX_RAMP = Regex("""(?i)take\s+(?:the\s+)?(?:entry\s+|on-)?ramp\b""")
    val REGEX_MERGE = Regex("""(?i)\bmerge(?:\s+onto)?\b""")
    val REGEX_FERRY = Regex("""(?i)\b(?:take\s+the\s+ferry|board\s+ferry|ferry)\b""")
    val REGEX_HEAD_COMPASS = Regex("""(?i)\bhead\s+(?:north|south|east|west|northeast|northwest|southeast|southwest)\b""")
    val REGEX_CONTINUE_STRAIGHT = Regex("""(?i)\b(?:continue(?:\s+straight)?|straight)\b""")

    private val NON_EXIT_NUMBER_WORDS = setOf(
        "on", "the", "left", "right", "to", "toward", "towards", "onto", "navigation", "ferry", "now"
    )

    fun specForId(cmdId: Int): CanonicalManeuverSpec? =
        COMMAND_CATALOG.firstOrNull { it.cmdId == cmdId }

    /**
     * Extracts a unified [TurnCommand] directly from an Android [Notification] using:
     * - `Notification.EXTRA_TITLE` -> `distanceRemaining`
     * - `Notification.EXTRA_TEXT` -> `rawInstruction`
     * - `Notification.EXTRA_SUB_TEXT` -> `tripSummary` (split by middle-dot `·`)
     * - `notification.getLargeIcon()` / `EXTRA_LARGE_ICON` -> Stage 2 24x24 pixel-density fallback
     */
    fun extractFromNotification(
        context: Context,
        notification: Notification,
        fallbackDirection: TurnTypeConstant? = null
    ): TurnCommand {
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?.ifBlank { extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() }
            ?.replace('\u00A0', ' ')?.trim().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?.ifBlank { extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() }
            ?.replace('\u00A0', ' ')?.trim().orEmpty()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?.ifBlank { extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString() }
            ?.replace('\u00A0', ' ')?.trim().orEmpty()

        val largeIconBitmap = loadLargeIconBitmap(context, notification)
        val snapshot = GoogleMapsNotificationListenerService.extractExtrasSnapshot(context, notification, extras)

        return extract(
            title = title,
            text = text,
            subText = subText,
            largeIconBitmap = largeIconBitmap,
            extraStrings = snapshot.allExtraStrings,
            iconResourceName = snapshot.iconResourceName,
            fallbackTurnType = snapshot.iconShapeDirection ?: fallbackDirection
        )
    }

    /**
     * Pure, unit-testable two-stage maneuver extraction pipeline.
     *
     * - **Stage 1 (Text Regex)**: Checks `text` (and `title` / `extraStrings` if `text` is only a street name)
     *   against the prioritized regex rules from `Parsing_Rules`.
     * - **Stage 2 (Visual Icon Fallback)**: When `text` contains only a street name (e.g., `"MG Road"`) with no
     *   action verbs, downscales `largeIconBitmap` to a 24x24 matrix and evaluates horizontal/vertical pixel distribution:
     *   - Left-side pixel ratio > 62% -> `Direction.LEFT`
     *   - Right-side pixel ratio > 62% -> `Direction.RIGHT`
     *   - Balanced top-heavy density -> `Direction.STRAIGHT`
     *   - Bottom-heavy density -> `ManeuverAction.U_TURN`
     */
    fun extract(
        title: String,
        text: String,
        subText: String = "",
        largeIconBitmap: Bitmap? = null,
        extraStrings: List<String> = emptyList(),
        iconResourceName: String? = null,
        fallbackTurnType: TurnTypeConstant? = null
    ): TurnCommand {
        val cleanTitle = title.replace('\u00A0', ' ').trim()
        val cleanText = text.replace('\u00A0', ' ').trim()
        val cleanSubText = subText.replace('\u00A0', ' ').trim()

        val tripSummary = parseTripSummary(cleanSubText)
        val targetStreet = GoogleMapsNotificationListenerService.extractTargetStreetOrLocation(
            title = cleanTitle,
            text = cleanText,
            subText = cleanSubText,
            extraStrings = extraStrings
        )

        // Stage 1: Prioritized Text Regex Extraction
        // First inspect EXTRA_TEXT; if EXTRA_TEXT is only a street name, also check title and TalkBack extraStrings
        val stage1Candidates = buildList {
            if (cleanText.isNotBlank()) add(cleanText)
            if (cleanTitle.isNotBlank()) add(cleanTitle)
            addAll(extraStrings)
        }

        for (candidate in stage1Candidates) {
            val matched = matchStage1Regex(
                candidateText = candidate,
                distanceRemaining = cleanTitle,
                rawInstruction = cleanText,
                tripSummary = tripSummary,
                targetStreet = targetStreet
            )
            if (matched != null) {
                return matched
            }
        }

        // Optional resource name check before bitmap pixel analysis
        val resTurn = GoogleMapsNotificationListenerService.parseDirectionFromIconResourceName(iconResourceName)
        if (resTurn != null) {
            return turnCommandFromTurnTypeConstant(
                turnType = resTurn,
                distanceRemaining = cleanTitle,
                rawInstruction = cleanText,
                tripSummary = tripSummary,
                targetStreet = targetStreet
            )
        }

        // Stage 2: Visual Icon 24x24 Matrix Pixel-Density Fallback
        if (largeIconBitmap != null) {
            val visualMatch = evaluate24x24IconFallback(
                bitmap = largeIconBitmap,
                distanceRemaining = cleanTitle,
                rawInstruction = cleanText,
                tripSummary = tripSummary,
                targetStreet = targetStreet
            )
            if (visualMatch != null) {
                return visualMatch
            }
        }

        // Fallback to pre-computed shape direction or latched countdown direction if available
        if (fallbackTurnType != null) {
            return turnCommandFromTurnTypeConstant(
                turnType = fallbackTurnType,
                distanceRemaining = cleanTitle,
                rawInstruction = cleanText,
                tripSummary = tripSummary,
                targetStreet = targetStreet
            )
        }

        val defaultSpec = specForId(3)
        return TurnCommand(
            action = if (cleanTitle.isNotBlank() || cleanText.isNotBlank()) ManeuverAction.CONTINUE_STRAIGHT else ManeuverAction.UNKNOWN,
            direction = if (cleanTitle.isNotBlank() || cleanText.isNotBlank()) Direction.STRAIGHT else Direction.NONE,
            modifier = TurnModifier.NONE,
            distanceRemaining = cleanTitle,
            rawInstruction = cleanText,
            tripSummary = tripSummary,
            commandId = if (cleanTitle.isNotBlank() || cleanText.isNotBlank()) 3 else null,
            targetStreet = targetStreet,
            downstreamBehavior = defaultSpec?.downstreamBehavior.orEmpty()
        )
    }

    /**
     * Stage 1: Evaluates a single text string against the prioritized regex rules from `Parsing_Rules`.
     * Strips trailing street names after `"onto / toward / towards"` first so street names like `"Right Ave"`
     * do not false-trigger a turn verb, while still running full-string regexes for patterns that include `"onto"`.
     */
    internal fun matchStage1Regex(
        candidateText: String,
        distanceRemaining: String,
        rawInstruction: String,
        tripSummary: TripSummary?,
        targetStreet: String
    ): TurnCommand? {
        if (candidateText.isBlank()) return null

        // 1. Arrival: (?i)(?:arrived|destination\s+is\s+on\s+the\s+(left|right)|you\s+have\s+arrived)
        REGEX_ARRIVAL.find(candidateText)?.let { match ->
            val side = match.groupValues.getOrNull(1)?.lowercase(Locale.US).orEmpty()
            val (dir, cmdId) = when (side) {
                "left" -> Direction.LEFT to 24
                "right" -> Direction.RIGHT to 25
                else -> Direction.NONE to 23
            }
            val spec = specForId(cmdId)
            return TurnCommand(
                action = ManeuverAction.ARRIVE,
                direction = dir,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = cmdId,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }

        // 2. Rerouting: (?i)(?:rerouting|finding\s+best\s+route)
        if (REGEX_REROUTE.containsMatchIn(candidateText)) {
            val spec = specForId(26)
            return TurnCommand(
                action = ManeuverAction.REROUTE,
                direction = Direction.NONE,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 26,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }

        // 3. Roundabout: (?i)roundabout.*?(?:take\s+the\s+)?(\d+)(?:st|nd|rd|th)?\s+exit
        REGEX_ROUNDABOUT.find(candidateText)?.let { match ->
            val exitNum = match.groupValues.getOrNull(1)?.toIntOrNull()
            val cmdId = when (exitNum) {
                1 -> 18
                2 -> 19
                3 -> 20
                else -> 21
            }
            val spec = specForId(cmdId)
            return TurnCommand(
                action = ManeuverAction.ROUNDABOUT,
                direction = Direction.NONE,
                modifier = TurnModifier.NONE,
                roundaboutExit = exitNum,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = cmdId,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }
        if (REGEX_ROUNDABOUT_GENERIC.containsMatchIn(candidateText)) {
            val spec = specForId(21)
            return TurnCommand(
                action = ManeuverAction.ROUNDABOUT,
                direction = if (candidateText.lowercase(Locale.US).contains("left")) Direction.LEFT else Direction.NONE,
                modifier = TurnModifier.NONE,
                roundaboutExit = null,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 21,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }

        // 4. U-Turn: (?i)make\s+a\s+u-turn
        if (REGEX_U_TURN.containsMatchIn(candidateText)) {
            val lower = candidateText.lowercase(Locale.US)
            val isRightUTurn = lower.contains("right u-turn") ||
                lower.contains("u-turn right") ||
                lower.contains("traffic light") ||
                lower.contains("clockwise")
            val cmdId = if (isRightUTurn) 9 else 8
            val spec = specForId(cmdId)
            return TurnCommand(
                action = ManeuverAction.U_TURN,
                direction = if (isRightUTurn) Direction.RIGHT else Direction.LEFT,
                modifier = TurnModifier.U_TURN,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = cmdId,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }

        // 5. Highway Exit: left exit vs (?i)(?:take\s+)?exit\s+([A-Za-z0-9\-]+)
        if (REGEX_HIGHWAY_EXIT_LEFT.containsMatchIn(candidateText)) {
            val spec = specForId(16)
            return TurnCommand(
                action = ManeuverAction.HIGHWAY_EXIT,
                direction = Direction.LEFT,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 16,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }
        REGEX_HIGHWAY_EXIT.find(candidateText)?.let { match ->
            val token = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (token.isNotEmpty() && token.lowercase(Locale.US) !in NON_EXIT_NUMBER_WORDS) {
                val spec = specForId(15)
                return TurnCommand(
                    action = ManeuverAction.HIGHWAY_EXIT,
                    direction = Direction.RIGHT,
                    modifier = TurnModifier.NONE,
                    exitNumber = token,
                    distanceRemaining = distanceRemaining,
                    rawInstruction = rawInstruction,
                    tripSummary = tripSummary,
                    commandId = 15,
                    targetStreet = targetStreet,
                    downstreamBehavior = spec?.downstreamBehavior.orEmpty()
                )
            }
        }

        // 6. Sharp Turn: (?i)sharp\s+(left|right)
        REGEX_SHARP_TURN.find(candidateText)?.let { match ->
            val dirStr = match.groupValues.getOrNull(1)?.lowercase(Locale.US).orEmpty()
            val isLeft = dirStr == "left"
            val cmdId = if (isLeft) 6 else 7
            val spec = specForId(cmdId)
            return TurnCommand(
                action = ManeuverAction.TURN,
                direction = if (isLeft) Direction.LEFT else Direction.RIGHT,
                modifier = TurnModifier.SHARP,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = cmdId,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }

        // 7. Slight Turn: (?i)slight\s+(left|right)|bear\s+(left|right)
        REGEX_SLIGHT_TURN.find(candidateText)?.let { match ->
            val dirStr = (match.groupValues.getOrNull(1)?.ifBlank { match.groupValues.getOrNull(2) }).orEmpty().lowercase(Locale.US)
            val isLeft = dirStr == "left"
            val cmdId = if (isLeft) 4 else 5
            val spec = specForId(cmdId)
            return TurnCommand(
                action = ManeuverAction.TURN,
                direction = if (isLeft) Direction.LEFT else Direction.RIGHT,
                modifier = TurnModifier.SLIGHT,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = cmdId,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }

        // 8. Keep / Fork: (?i)(?:at\s+the\s+fork|keep)\s+(left|right)
        REGEX_KEEP_FORK.find(candidateText)?.let { match ->
            val forkPrefix = match.groupValues.getOrNull(1).orEmpty()
            val forkDir = match.groupValues.getOrNull(2).orEmpty()
            val keepDir = match.groupValues.getOrNull(4).orEmpty()
            val isFork = forkPrefix.isNotBlank() || candidateText.lowercase(Locale.US).contains("fork")
            val dirStr = (forkDir.ifBlank { keepDir }).lowercase(Locale.US)
            val isLeft = dirStr == "left"
            val action = if (isFork) ManeuverAction.FORK else ManeuverAction.KEEP
            val cmdId = when {
                isFork && isLeft -> 12
                isFork && !isLeft -> 13
                isLeft -> 10
                else -> 11
            }
            val spec = specForId(cmdId)
            return TurnCommand(
                action = action,
                direction = if (isLeft) Direction.LEFT else Direction.RIGHT,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = cmdId,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }

        // 9. Standard Turn: (?i)(?:turn\s+)?(left|right)\s+(?:onto|on)
        REGEX_STANDARD_TURN.find(candidateText)?.let { match ->
            val dirStr = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.lowercase(Locale.US).orEmpty()
            val isLeft = dirStr == "left"
            val cmdId = if (isLeft) 1 else 2
            val spec = specForId(cmdId)
            return TurnCommand(
                action = ManeuverAction.TURN,
                direction = if (isLeft) Direction.LEFT else Direction.RIGHT,
                modifier = TurnModifier.NORMAL,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = cmdId,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }

        // 10. Unicode Sub-Cue Arrows ("Then ↱", "↱", "↰", "↶", "↷")
        if (candidateText.contains("↱") || candidateText.contains("→")) {
            val spec = specForId(2)
            return TurnCommand(
                action = ManeuverAction.TURN,
                direction = Direction.RIGHT,
                modifier = TurnModifier.NORMAL,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 2,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }
        if (candidateText.contains("↰") || candidateText.contains("←")) {
            val spec = specForId(1)
            return TurnCommand(
                action = ManeuverAction.TURN,
                direction = Direction.LEFT,
                modifier = TurnModifier.NORMAL,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 1,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }

        // 11. Highway Ramp / Merge / Ferry / Head / Continue Straight (Catalog Cmds 3, 14, 17, 22, 27)
        if (REGEX_RAMP.containsMatchIn(candidateText)) {
            val isLeft = candidateText.lowercase(Locale.US).contains("left")
            val spec = specForId(14)
            return TurnCommand(
                action = ManeuverAction.RAMP,
                direction = if (isLeft) Direction.LEFT else Direction.RIGHT,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 14,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }
        if (REGEX_MERGE.containsMatchIn(candidateText)) {
            val isLeft = candidateText.lowercase(Locale.US).contains("left")
            val spec = specForId(17)
            return TurnCommand(
                action = ManeuverAction.MERGE,
                direction = if (isLeft) Direction.LEFT else Direction.RIGHT,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 17,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }
        if (REGEX_FERRY.containsMatchIn(candidateText)) {
            val spec = specForId(27)
            return TurnCommand(
                action = ManeuverAction.FERRY,
                direction = Direction.NONE,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 27,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }
        if (REGEX_HEAD_COMPASS.containsMatchIn(candidateText)) {
            val spec = specForId(22)
            return TurnCommand(
                action = ManeuverAction.CONTINUE_STRAIGHT,
                direction = Direction.STRAIGHT,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 22,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }
        if (REGEX_CONTINUE_STRAIGHT.containsMatchIn(candidateText)) {
            val spec = specForId(3)
            return TurnCommand(
                action = ManeuverAction.CONTINUE_STRAIGHT,
                direction = Direction.STRAIGHT,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 3,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }

        return null
    }

    /**
     * Stage 2 (Visual Icon Fallback):
     * Downscales the notification largeIcon [Bitmap] to a 24x24 matrix and evaluates horizontal
     * and vertical pixel distribution per `Parsing_Rules`:
     * - Left-side pixel ratio > 62% (`0.62f`) -> `Direction.LEFT` (`ManeuverAction.TURN`)
     * - Right-side pixel ratio > 62% (`0.62f`) -> `Direction.RIGHT` (`ManeuverAction.TURN`)
     * - Bottom-heavy density -> `ManeuverAction.U_TURN`
     * - Balanced top-heavy density -> `Direction.STRAIGHT` (`ManeuverAction.CONTINUE_STRAIGHT`)
     * Also incorporates topological loop/stem analysis when pixel ratios are between 38%..62% on centered icons.
     */
    fun evaluate24x24IconFallback(
        bitmap: Bitmap,
        distanceRemaining: String = "",
        rawInstruction: String = "",
        tripSummary: TripSummary? = null,
        targetStreet: String = ""
    ): TurnCommand? {
        if (bitmap.width < 4 || bitmap.height < 4) return null
        val matrixSize = 24
        val scaled = if (bitmap.width == matrixSize && bitmap.height == matrixSize) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, matrixSize, matrixSize, true)
        }

        var leftPixels = 0
        var rightPixels = 0
        var topPixels = 0
        var bottomPixels = 0
        var totalForeground = 0

        val mid = matrixSize / 2
        for (y in 0 until matrixSize) {
            for (x in 0 until matrixSize) {
                val px = scaled.getPixel(x, y)
                val alpha = (px ushr 24) and 0xFF
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                if (alpha > 60 && (r + g + b) > 120) {
                    totalForeground++
                    if (x < mid) leftPixels++ else rightPixels++
                    if (y < mid) topPixels++ else bottomPixels++
                }
            }
        }

        if (totalForeground < 4) return null

        val leftRatio = leftPixels.toFloat() / totalForeground
        val rightRatio = rightPixels.toFloat() / totalForeground
        val topRatio = topPixels.toFloat() / totalForeground
        val bottomRatio = bottomPixels.toFloat() / totalForeground

        // 1. First inspect normalized arrow geometry (stem-to-arrowhead horizontal shift, U-turn hooks, and roundabout circles)
        // so that stemmed icons (e.g. Turn Right `↱` whose vertical stem is on the left and arrowhead is on the right)
        // are never inverted by raw uncropped half-canvas pixel counts:
        val classifiedTurn = GoogleMapsNotificationListenerService.classifyManeuverBitmap(bitmap)
        if (classifiedTurn != null && classifiedTurn != TurnTypeConstant.CONTINUE_STRAIGHT) {
            return turnCommandFromTurnTypeConstant(
                turnType = classifiedTurn,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                targetStreet = targetStreet
            )
        }

        // 2. Explicit 24x24 Pixel-Density Thresholds from Parsing_Rules (for unstemmed/half-canvas icons):
        // Active pixels on the right half (> 62%) -> Direction.RIGHT
        if (rightRatio > 0.62f) {
            val spec = specForId(2)
            return TurnCommand(
                action = ManeuverAction.TURN,
                direction = Direction.RIGHT,
                modifier = TurnModifier.NORMAL,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 2,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }
        // Active pixels on the left half (> 62%) -> Direction.LEFT
        if (leftRatio > 0.62f) {
            val spec = specForId(1)
            return TurnCommand(
                action = ManeuverAction.TURN,
                direction = Direction.LEFT,
                modifier = TurnModifier.NORMAL,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 1,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }
        // Bottom-heavy density -> ManeuverAction.U_TURN
        if (bottomRatio > 0.58f && leftRatio in 0.35f..0.65f) {
            val spec = specForId(8)
            return TurnCommand(
                action = ManeuverAction.U_TURN,
                direction = Direction.LEFT,
                modifier = TurnModifier.U_TURN,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 8,
                targetStreet = targetStreet,
                downstreamBehavior = spec?.downstreamBehavior.orEmpty()
            )
        }

        // 3. Safe Default Fallback: If an icon cannot be confidently classified as a turn/U-turn/roundabout,
        // always default to Continue Straight (never default to Left turn).
        val spec = specForId(3)
        return TurnCommand(
            action = ManeuverAction.CONTINUE_STRAIGHT,
            direction = Direction.STRAIGHT,
            modifier = TurnModifier.NONE,
            distanceRemaining = distanceRemaining,
            rawInstruction = rawInstruction,
            tripSummary = tripSummary,
            commandId = 3,
            targetStreet = targetStreet,
            downstreamBehavior = spec?.downstreamBehavior.orEmpty()
        )
    }

    /**
     * Parses `Notification.EXTRA_SUB_TEXT` (split by middle-dot `·` or bullet `•`) into a [TripSummary]
     * containing duration, remaining distance, and ETA clock string.
     */
    fun parseTripSummary(subText: String): TripSummary? {
        if (subText.isBlank()) return null
        val segments = subText
            .split('·', '•', '|')
            .map { it.replace('\u00A0', ' ').trim() }
            .filter { it.isNotBlank() }

        if (segments.isEmpty()) return null

        var durationStr = ""
        var distanceStr = ""
        var etaClockStr = ""

        val durationRegex = Regex("""(?i)\b\d+\s*(?:days?|d|hours?|hrs?|hr|h|minutes?|mins?|min)\b""")
        val distanceRegex = Regex("""(?i)\b\d+(?:[.,]\d+)?\s*(?:km|m|mi|yd|ft)\b""")
        val clockRegex = Regex("""(?i)\b\d{1,2}:\d{2}(?:\s*(?:am|pm|a\.m\.|p\.m\.))?\b""")

        for (seg in segments) {
            when {
                durationStr.isEmpty() && durationRegex.containsMatchIn(seg) -> durationStr = seg
                distanceStr.isEmpty() && distanceRegex.containsMatchIn(seg) -> distanceStr = seg
                etaClockStr.isEmpty() && clockRegex.containsMatchIn(seg) -> etaClockStr = seg
            }
        }

        if (durationStr.isEmpty() && segments.size >= 1) durationStr = segments[0]
        if (distanceStr.isEmpty() && segments.size >= 2) distanceStr = segments[1]
        if (etaClockStr.isEmpty() && segments.size >= 3) etaClockStr = segments[2]

        val (hrs, mins) = GoogleMapsNotificationListenerService.parseEtaDurationHoursAndMinutes(subText) ?: (0 to 0)

        return TripSummary(
            duration = durationStr,
            remainingDistance = distanceStr,
            eta = etaClockStr,
            durationHours = hrs,
            durationMinutes = mins
        )
    }

    internal fun turnCommandFromTurnTypeConstant(
        turnType: TurnTypeConstant,
        distanceRemaining: String,
        rawInstruction: String,
        tripSummary: TripSummary?,
        targetStreet: String
    ): TurnCommand {
        return when (turnType) {
            TurnTypeConstant.TURN_LEFT,
            TurnTypeConstant.TAKE_LEFT_AT,
            TurnTypeConstant.TAKE_LEFT_AT_END -> TurnCommand(
                action = ManeuverAction.TURN,
                direction = Direction.LEFT,
                modifier = TurnModifier.NORMAL,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 1,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(1)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.TURN_RIGHT,
            TurnTypeConstant.TAKE_RIGHT_AT,
            TurnTypeConstant.TAKE_RIGHT_AT_END -> TurnCommand(
                action = ManeuverAction.TURN,
                direction = Direction.RIGHT,
                modifier = TurnModifier.NORMAL,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 2,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(2)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.TURN_SLIGHTLY_LEFT -> TurnCommand(
                action = ManeuverAction.TURN,
                direction = Direction.LEFT,
                modifier = TurnModifier.SLIGHT,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 4,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(4)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.TURN_SLIGHTLY_RIGHT -> TurnCommand(
                action = ManeuverAction.TURN,
                direction = Direction.RIGHT,
                modifier = TurnModifier.SLIGHT,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 5,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(5)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.TURN_SHARPLY_LEFT -> TurnCommand(
                action = ManeuverAction.TURN,
                direction = Direction.LEFT,
                modifier = TurnModifier.SHARP,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 6,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(6)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.TURN_SHARPLY_RIGHT -> TurnCommand(
                action = ManeuverAction.TURN,
                direction = Direction.RIGHT,
                modifier = TurnModifier.SHARP,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 7,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(7)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.U_TURN -> TurnCommand(
                action = ManeuverAction.U_TURN,
                direction = Direction.LEFT,
                modifier = TurnModifier.U_TURN,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 8,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(8)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.RIGHT_U_TURN -> TurnCommand(
                action = ManeuverAction.U_TURN,
                direction = Direction.RIGHT,
                modifier = TurnModifier.U_TURN,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 9,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(9)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.KEEP_LEFT,
            TurnTypeConstant.KEEP_LEFT_AT_INTERSECTION -> TurnCommand(
                action = ManeuverAction.KEEP,
                direction = Direction.LEFT,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 10,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(10)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.KEEP_RIGHT,
            TurnTypeConstant.KEEP_RIGHT_AT_INTERSECTION -> TurnCommand(
                action = ManeuverAction.KEEP,
                direction = Direction.RIGHT,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = tripSummary?.let { rawInstruction }.orEmpty(),
                tripSummary = tripSummary,
                commandId = 11,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(11)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.FORK_LEFT -> TurnCommand(
                action = ManeuverAction.FORK,
                direction = Direction.LEFT,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 12,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(12)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.FORK_RIGHT,
            TurnTypeConstant.RIGHT_FORK_V1,
            TurnTypeConstant.RIGHT_EXIT_RAMP_V1 -> TurnCommand(
                action = ManeuverAction.FORK,
                direction = Direction.RIGHT,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 13,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(13)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.HIGHWAY_EXIT_LEFT -> TurnCommand(
                action = ManeuverAction.HIGHWAY_EXIT,
                direction = Direction.LEFT,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 16,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(16)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.HIGHWAY_ENTER_LEFT -> TurnCommand(
                action = ManeuverAction.MERGE,
                direction = Direction.RIGHT,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 17,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(17)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.ROUNDABOUT_EXIT_1 -> TurnCommand(
                action = ManeuverAction.ROUNDABOUT,
                direction = Direction.NONE,
                modifier = TurnModifier.NONE,
                roundaboutExit = 1,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 18,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(18)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.ROUNDABOUT_EXIT_2 -> TurnCommand(
                action = ManeuverAction.ROUNDABOUT,
                direction = Direction.NONE,
                modifier = TurnModifier.NONE,
                roundaboutExit = 2,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 19,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(19)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.ROUNDABOUT_EXIT_3 -> TurnCommand(
                action = ManeuverAction.ROUNDABOUT,
                direction = Direction.NONE,
                modifier = TurnModifier.NONE,
                roundaboutExit = 3,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 20,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(20)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.ROUNDABOUT,
            TurnTypeConstant.ROUNDABOUT_LEFT,
            TurnTypeConstant.ROTARY -> TurnCommand(
                action = ManeuverAction.ROUNDABOUT,
                direction = if (turnType == TurnTypeConstant.ROUNDABOUT_LEFT) Direction.LEFT else Direction.NONE,
                modifier = TurnModifier.NONE,
                roundaboutExit = 4,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 21,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(21)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.DESTINATION_REACHED,
            TurnTypeConstant.ARRIVE_V1 -> TurnCommand(
                action = ManeuverAction.ARRIVE,
                direction = Direction.NONE,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 23,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(23)?.downstreamBehavior.orEmpty()
            )
            TurnTypeConstant.TAKE_FERRY,
            TurnTypeConstant.LEAVE_FERRY,
            TurnTypeConstant.FERRY_V1 -> TurnCommand(
                action = ManeuverAction.FERRY,
                direction = Direction.NONE,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 27,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(27)?.downstreamBehavior.orEmpty()
            )
            else -> TurnCommand(
                action = ManeuverAction.CONTINUE_STRAIGHT,
                direction = Direction.STRAIGHT,
                modifier = TurnModifier.NONE,
                distanceRemaining = distanceRemaining,
                rawInstruction = rawInstruction,
                tripSummary = tripSummary,
                commandId = 3,
                targetStreet = targetStreet,
                downstreamBehavior = specForId(3)?.downstreamBehavior.orEmpty()
            )
        }
    }

    private fun loadLargeIconBitmap(context: Context, notification: Notification): Bitmap? {
        runCatching {
            @Suppress("DEPRECATION")
            notification.largeIcon?.let { return it }
            val icon: Icon = notification.getLargeIcon() ?: return null
            val remoteContext = runCatching {
                context.createPackageContext(
                    GoogleMapsNotificationListenerService.GOOGLE_MAPS_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY
                )
            }.getOrNull() ?: context
            val drawable = icon.loadDrawable(remoteContext) ?: icon.loadDrawable(context) ?: return null
            val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, 48, 48)
            drawable.draw(canvas)
            return bmp
        }
        return null
    }
}
