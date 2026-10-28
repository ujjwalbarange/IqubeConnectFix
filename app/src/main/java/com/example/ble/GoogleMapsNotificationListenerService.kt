package com.example.ble

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.example.model.GoogleMapsListenerState
import com.example.model.ParsedGoogleMapsNavigation
import com.example.model.TurnTypeConstant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Vehicle Navigation Handler callback interface invoked asynchronously whenever
 * active Google Maps navigation updates or ends.
 */
interface VehicleNavigationHandler {
    fun onNavigationUpdated(
        direction: TurnTypeConstant,
        distanceMeters: Int,
        etaHours: Int,
        etaMinutes: Int
    )

    fun onNavigationArrived()

    fun onNavigationStopped()
}

/**
 * Crash-proof Kotlin [NotificationListenerService] that extracts three normalized values
 * from active Google Maps (`com.google.android.apps.maps`) ongoing turn-by-turn notifications:
 * 1. Direction ([TurnTypeConstant] maneuver)
 * 2. Distance Remaining to next turn (normalized integer meters)
 * 3. ETA Duration (remaining travel duration in integer hours and minutes, ignoring arrival clock times)
 *
 * Deduplicates incoming notifications so updates are only dispatched when direction, distance, or ETA
 * changes, and dispatches all updates asynchronously without blocking the system binder callback.
 */
class GoogleMapsNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onListenerConnected() {
        runCatching {
            super.onListenerConnected()
            updateListenerConnectionState(connected = true)
            // Inspect any already-active notifications safely without blocking the binder thread
            serviceScope.launch {
                runCatching {
                    val currentActive = activeNotifications ?: emptyArray()
                    for (sbn in currentActive) {
                        if (sbn?.packageName == GOOGLE_MAPS_PACKAGE) {
                            processGoogleMapsStatusBarNotification(sbn)
                        }
                    }
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        runCatching {
            super.onListenerDisconnected()
            updateListenerConnectionState(connected = false)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        runCatching {
            if (sbn == null || sbn.packageName != GOOGLE_MAPS_PACKAGE) return
            // Extract lightweight immutable snapshot immediately and process asynchronously
            val notification = sbn.notification ?: return
            val isOngoing = sbn.isOngoing || ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
            val category = notification.category
            val extrasSnapshot = extractExtrasSnapshot(context = this, notification = notification)

            serviceScope.launch {
                runCatching {
                    processExtractedNotificationSnapshot(
                        isOngoing = isOngoing,
                        category = category,
                        snapshot = extrasSnapshot
                    )
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        runCatching {
            if (sbn == null || sbn.packageName != GOOGLE_MAPS_PACKAGE) return
            val notification = sbn.notification
            val wasOngoing = sbn.isOngoing ||
                (notification != null && (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) ||
                notification?.category == Notification.CATEGORY_NAVIGATION

            // Only clear active route state when the ongoing navigation notification is dismissed/stopped
            if (wasOngoing || _listenerState.value.isActiveNavigationOngoing) {
                serviceScope.launch {
                    runCatching {
                        handleNavigationStopped("Google Maps navigation notification dismissed — route cleared")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            updateListenerConnectionState(connected = false)
            serviceScope.cancel()
            super.onDestroy()
        }
    }

    private fun processGoogleMapsStatusBarNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val isOngoing = sbn.isOngoing || ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        val snapshot = extractExtrasSnapshot(context = this, notification = notification)
        processExtractedNotificationSnapshot(
            isOngoing = isOngoing,
            category = notification.category,
            snapshot = snapshot
        )
    }

    companion object {
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

        private val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private data class DeduplicationKey(
            val direction: TurnTypeConstant,
            val distanceMeters: Int,
            val etaHours: Int,
            val etaMinutes: Int,
            val isDestinationArrived: Boolean
        )

        internal data class NotificationExtrasSnapshot(
            val title: String,
            val text: String,
            val subText: String,
            val bigText: String,
            val tickerText: String = "",
            val allExtraStrings: List<String> = emptyList(),
            val iconResourceName: String? = null,
            val iconShapeDirection: TurnTypeConstant? = null
        )

        private val lastDispatchedKey = AtomicReference<DeduplicationKey?>(null)
        private val registeredHandlerRef = AtomicReference<VehicleNavigationHandler?>(null)

        private val _listenerState = MutableStateFlow(GoogleMapsListenerState())
        val listenerState: StateFlow<GoogleMapsListenerState> = _listenerState.asStateFlow()

        // Non-navigation alerts, background notices, rerouting statuses, and GPS search phrases to ignore
        private val IGNORED_NON_NAV_PATTERNS = listOf(
            "rerouting",
            "searching for gps",
            "waiting for gps",
            "gps signal lost",
            "finding best route",
            "loading route",
            "calculating route",
            "checking traffic",
            "updating route",
            "starting navigation",
            "location sharing",
            "sharing your location",
            "timeline",
            "offline maps",
            "downloading",
            "rate your",
            "contribute",
            "local guide",
            "explore nearby",
            "parking location",
            "saved places"
        )

        // Absolute clock time regex (e.g. "10:45 PM", "9:30 am", "14:20", "ETA 10:45", "Arrive 10:45")
        private val CLOCK_TIME_REGEX = Regex(
            pattern = """(?i)\b(?:eta|arrive|arrival|by)?\s*\d{1,2}:\d{2}(?:\s*(?:am|pm|a\.m\.|p\.m\.))?\b"""
        )

        // Distance unit regex for next-turn extraction (supports "." and "," decimal separators)
        private val DISTANCE_TOKEN_REGEX = Regex(
            pattern = """(?i)(\d+(?:[.,]\d+)?)\s*(kilometers|kilometres|kilometer|kilometre|km|meters|metres|meter|metre|m|miles|mile|mi|yards|yard|yds|yd|feet|foot|ft)\b"""
        )

        // Duration regexes for days, hours, and minutes
        private val DURATION_DAYS_REGEX = Regex("""(?i)(\d+)\s*(?:days|day|d)\b""")
        private val DURATION_HOURS_REGEX = Regex("""(?i)(\d+)\s*(?:hours|hour|hrs|hr|h)\b""")
        private val DURATION_MINUTES_REGEX = Regex("""(?i)(\d+)\s*(?:minutes|minute|mins|min|m)\b""")

        /**
         * Registers the app's vehicle navigation handler ([IqubeBleManager]) to receive
         * deduplicated, asynchronous Google Maps navigation updates.
         */
        fun registerVehicleNavigationHandler(handler: VehicleNavigationHandler?) {
            registeredHandlerRef.set(handler)
        }

        /**
         * Enables or disables automatic forwarding of parsed Google Maps updates to the cluster.
         */
        fun setAutoForwardEnabled(enabled: Boolean) {
            _listenerState.update { it.copy(isAutoForwardEnabled = enabled) }
        }

        /**
         * Checks whether the user has granted Notification Listener access to this app in Android Settings.
         */
        fun isNotificationAccessGranted(context: Context): Boolean {
            return runCatching {
                NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)
            }.getOrDefault(false)
        }

        internal fun updateListenerConnectionState(connected: Boolean) {
            _listenerState.update {
                it.copy(
                    isServiceConnected = connected,
                    lastStatusMessage = if (connected) {
                        "NotificationListenerService connected • Monitoring $GOOGLE_MAPS_PACKAGE"
                    } else {
                        "NotificationListenerService disconnected"
                    }
                )
            }
        }

        internal fun extractExtrasSnapshot(
            context: Context? = null,
            notification: Notification? = null,
            extras: Bundle? = notification?.extras
        ): NotificationExtrasSnapshot {
            if (extras == null && notification == null) {
                return NotificationExtrasSnapshot("", "", "", "")
            }
            val title = extras?.getCharSequenceSafe(Notification.EXTRA_TITLE_BIG)
                ?.ifBlank { extras.getCharSequenceSafe(Notification.EXTRA_TITLE) }.orEmpty()
            val text = extras?.getCharSequenceSafe(Notification.EXTRA_TEXT).orEmpty()
            val bigText = extras?.getCharSequenceSafe(Notification.EXTRA_BIG_TEXT).orEmpty()
            val subText = extras?.getCharSequenceSafe(Notification.EXTRA_SUB_TEXT)
                ?.ifBlank { extras.getCharSequenceSafe(Notification.EXTRA_INFO_TEXT) }
                ?.ifBlank { extras.getCharSequenceSafe(Notification.EXTRA_SUMMARY_TEXT) }.orEmpty()

            val tickerText = notification?.tickerText?.toString()?.replace('\u00A0', ' ')?.trim().orEmpty()

            val allExtraStrings = mutableListOf<String>()
            if (extras != null) {
                for (key in extras.keySet()) {
                    runCatching {
                        when (val value = extras.get(key)) {
                            is CharSequence -> {
                                val s = value.toString().replace('\u00A0', ' ').trim()
                                if (s.isNotBlank() && !allExtraStrings.contains(s)) allExtraStrings.add(s)
                            }
                            is Array<*> -> {
                                value.forEach { item ->
                                    if (item is CharSequence) {
                                        val s = item.toString().replace('\u00A0', ' ').trim()
                                        if (s.isNotBlank() && !allExtraStrings.contains(s)) allExtraStrings.add(s)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            var iconResName: String? = null
            var iconShapeDir: TurnTypeConstant? = null

            if (context != null && notification != null) {
                val smallIcon = notification.smallIcon
                val largeIcon = notification.getLargeIcon()

                iconResName = resolveIconResourceName(context, smallIcon)
                    ?: resolveIconResourceName(context, largeIcon)

                val resTurn = parseDirectionFromIconResourceName(iconResName)
                if (resTurn == null) {
                    iconShapeDir = classifyArrowFromIcon(context, smallIcon)
                        ?: classifyArrowFromIcon(context, largeIcon)
                }
            }

            return NotificationExtrasSnapshot(
                title = title,
                text = text,
                subText = subText,
                bigText = bigText,
                tickerText = tickerText,
                allExtraStrings = allExtraStrings,
                iconResourceName = iconResName,
                iconShapeDirection = iconShapeDir
            )
        }

        internal fun resolveIconResourceName(context: Context, icon: Icon?): String? {
            if (icon == null) return null
            return runCatching {
                val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    icon.resPackage
                } else {
                    val m = icon.javaClass.getMethod("getResPackage")
                    m.invoke(icon) as? String
                } ?: GOOGLE_MAPS_PACKAGE

                val resId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    icon.resId
                } else {
                    val m = icon.javaClass.getMethod("getResId")
                    (m.invoke(icon) as? Number)?.toInt() ?: 0
                }

                if (resId != 0) {
                    val pm = context.packageManager
                    val res = pm.getResourcesForApplication(pkg)
                    res.getResourceEntryName(resId)
                } else null
            }.getOrNull()
        }

        internal fun classifyArrowFromIcon(context: Context, icon: Icon?): TurnTypeConstant? {
            if (icon == null) return null
            return runCatching {
                val drawable = icon.loadDrawable(context) ?: return null
                val width = 24
                val height = 24
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)

                var leftWeight = 0
                var rightWeight = 0
                var topWeight = 0
                var bottomWeight = 0
                var nonTransparentPixels = 0

                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val pixel = bitmap.getPixel(x, y)
                        val alpha = (pixel ushr 24) and 0xFF
                        if (alpha > 40) {
                            nonTransparentPixels++
                            if (x < (width / 2 - 1)) leftWeight++
                            else if (x > (width / 2 + 1)) rightWeight++

                            if (y < height / 2) topWeight++
                            else bottomWeight++
                        }
                    }
                }

                if (nonTransparentPixels < 10) return null

                val rightBias = if (leftWeight > 0) rightWeight.toFloat() / leftWeight else rightWeight.toFloat()
                val leftBias = if (rightWeight > 0) leftWeight.toFloat() / rightWeight else leftWeight.toFloat()

                when {
                    rightBias >= 1.5f -> TurnTypeConstant.TURN_RIGHT
                    leftBias >= 1.5f -> TurnTypeConstant.TURN_LEFT
                    bottomWeight > topWeight * 1.6f -> TurnTypeConstant.U_TURN
                    else -> TurnTypeConstant.CONTINUE_STRAIGHT
                }
            }.getOrNull()
        }

        internal fun parseDirectionFromIconResourceName(resName: String?): TurnTypeConstant? {
            if (resName.isNullOrBlank()) return null
            val lower = resName.lowercase(Locale.US)

            if (lower.contains("arrive") || lower.contains("destination") || lower.contains("finish")) {
                return TurnTypeConstant.DESTINATION_REACHED
            }
            if (lower.contains("u_turn") || lower.contains("uturn")) {
                return if (lower.contains("right")) TurnTypeConstant.RIGHT_U_TURN else TurnTypeConstant.U_TURN
            }
            if (lower.contains("roundabout") || lower.contains("rotary") || lower.contains("circle")) {
                return when {
                    lower.contains("1") || lower.contains("one") || lower.contains("first") -> TurnTypeConstant.ROUNDABOUT_EXIT_1
                    lower.contains("2") || lower.contains("two") || lower.contains("second") -> TurnTypeConstant.ROUNDABOUT_EXIT_2
                    lower.contains("3") || lower.contains("three") || lower.contains("third") -> TurnTypeConstant.ROUNDABOUT_EXIT_3
                    lower.contains("left") -> TurnTypeConstant.ROUNDABOUT_LEFT
                    else -> TurnTypeConstant.ROUNDABOUT
                }
            }
            if (lower.contains("sharp_right") || lower.contains("sharply_right") || lower.contains("hard_right")) {
                return TurnTypeConstant.TURN_SHARPLY_RIGHT
            }
            if (lower.contains("sharp_left") || lower.contains("sharply_left") || lower.contains("hard_left")) {
                return TurnTypeConstant.TURN_SHARPLY_LEFT
            }
            if (lower.contains("slight_right") || lower.contains("slightly_right") || lower.contains("bear_right") || lower.contains("curve_right")) {
                return TurnTypeConstant.TURN_SLIGHTLY_RIGHT
            }
            if (lower.contains("slight_left") || lower.contains("slightly_left") || lower.contains("bear_left") || lower.contains("curve_left")) {
                return TurnTypeConstant.TURN_SLIGHTLY_LEFT
            }
            if (lower.contains("fork_right") || lower.contains("ramp_right") || lower.contains("exit_right")) {
                return TurnTypeConstant.FORK_RIGHT
            }
            if (lower.contains("fork_left") || lower.contains("ramp_left") || lower.contains("exit_left")) {
                return TurnTypeConstant.FORK_LEFT
            }
            if (lower.contains("keep_right") || lower.contains("stay_right")) {
                return TurnTypeConstant.KEEP_RIGHT
            }
            if (lower.contains("keep_left") || lower.contains("stay_left")) {
                return TurnTypeConstant.KEEP_LEFT
            }
            if (lower.contains("right") && !lower.contains("left")) {
                return TurnTypeConstant.TURN_RIGHT
            }
            if (lower.contains("left") && !lower.contains("right")) {
                return TurnTypeConstant.TURN_LEFT
            }
            if (lower.contains("straight") || lower.contains("continue") || lower.contains("proceed") || lower.contains("up")) {
                return TurnTypeConstant.CONTINUE_STRAIGHT
            }
            return null
        }

        private fun Bundle.getCharSequenceSafe(key: String): String {
            return runCatching {
                getCharSequence(key)?.toString()?.replace('\u00A0', ' ')?.trim().orEmpty()
            }.getOrDefault("")
        }

        /**
         * Processes a raw Google Maps notification snapshot (or a simulated test notification),
         * enforces filtering & deduplication, and dispatches asynchronously to [VehicleNavigationHandler].
         */
        fun processRawGoogleMapsNotification(
            isOngoing: Boolean,
            category: String? = Notification.CATEGORY_NAVIGATION,
            title: String,
            text: String,
            subText: String,
            bigText: String = "",
            tickerText: String = "",
            extraStrings: List<String> = emptyList(),
            iconResourceName: String? = null,
            iconShapeDirection: TurnTypeConstant? = null
        ) {
            dispatchScope.launch {
                runCatching {
                    processExtractedNotificationSnapshot(
                        isOngoing = isOngoing,
                        category = category,
                        snapshot = NotificationExtrasSnapshot(
                            title = title.trim(),
                            text = text.trim(),
                            subText = subText.trim(),
                            bigText = bigText.trim(),
                            tickerText = tickerText.trim(),
                            allExtraStrings = extraStrings,
                            iconResourceName = iconResourceName,
                            iconShapeDirection = iconShapeDirection
                        )
                    )
                }
            }
        }

        internal fun processExtractedNotificationSnapshot(
            isOngoing: Boolean,
            category: String?,
            snapshot: NotificationExtrasSnapshot
        ) {
            // Requirement 1: Process only ongoing navigation notifications
            if (!isOngoing) {
                _listenerState.update {
                    it.copy(
                        filteredNonNavCount = it.filteredNonNavCount + 1,
                        lastStatusMessage = "Ignored non-ongoing Google Maps notification"
                    )
                }
                return
            }

            val combinedLower = "${snapshot.title} | ${snapshot.text} | ${snapshot.subText} | ${snapshot.bigText} | ${snapshot.tickerText}"
                .lowercase(Locale.US)

            if (combinedLower.isBlank() || isNonNavigationOrTransientAlert(combinedLower)) {
                _listenerState.update {
                    it.copy(
                        filteredNonNavCount = it.filteredNonNavCount + 1,
                        lastStatusMessage = "Filtered transient/non-nav status (\"${snapshot.title.ifBlank { snapshot.text }}\")"
                    )
                }
                return
            }

            val previousKey = lastDispatchedKey.get()
            val parsed = parseNavigationNotification(
                title = snapshot.title,
                text = snapshot.text.ifBlank { snapshot.bigText },
                subText = snapshot.subText,
                category = category,
                tickerText = snapshot.tickerText,
                extraStrings = snapshot.allExtraStrings,
                iconResourceName = snapshot.iconResourceName,
                iconShapeDirection = snapshot.iconShapeDirection,
                fallbackPreviousDirection = previousKey?.direction
            )

            if (parsed == null) {
                _listenerState.update {
                    it.copy(
                        filteredNonNavCount = it.filteredNonNavCount + 1,
                        lastStatusMessage = "Ignored non-navigation notice"
                    )
                }
                return
            }

            // Requirement 5: Deduplicate incoming notifications so updates are only dispatched
            // when direction, distance, or ETA changes.
            val newKey = DeduplicationKey(
                direction = parsed.direction,
                distanceMeters = parsed.distanceMeters,
                etaHours = parsed.etaHours,
                etaMinutes = parsed.etaMinutes,
                isDestinationArrived = parsed.isDestinationArrived
            )

            if (previousKey == newKey) {
                _listenerState.update {
                    it.copy(
                        ignoredDuplicateCount = it.ignoredDuplicateCount + 1,
                        lastUpdatedAtMs = System.currentTimeMillis()
                    )
                }
                return
            }
            lastDispatchedKey.set(newKey)

            _listenerState.update {
                it.copy(
                    isActiveNavigationOngoing = !parsed.isDestinationArrived,
                    lastParsedNavigation = parsed,
                    dispatchedUpdateCount = it.dispatchedUpdateCount + 1,
                    lastStatusMessage = if (parsed.isDestinationArrived) {
                        "Destination Reached (0x08) • Arrived"
                    } else {
                        "Dispatched ${parsed.direction.title} (${parsed.direction.hexLabel}) • ${parsed.distanceMeters}m • ETA ${parsed.etaHours}h ${parsed.etaMinutes}m"
                    },
                    lastUpdatedAtMs = System.currentTimeMillis()
                )
            }

            if (!_listenerState.value.isAutoForwardEnabled) return

            val handler = registeredHandlerRef.get() ?: return
            if (parsed.isDestinationArrived) {
                // Requirement 6: Detect destination arrival and trigger the arrival state
                handler.onNavigationArrived()
            } else {
                handler.onNavigationUpdated(
                    direction = parsed.direction,
                    distanceMeters = parsed.distanceMeters,
                    etaHours = parsed.etaHours,
                    etaMinutes = parsed.etaMinutes
                )
            }
        }

        internal fun handleNavigationStopped(reason: String = "Navigation stopped — active route cleared") {
            lastDispatchedKey.set(null)
            _listenerState.update {
                it.copy(
                    isActiveNavigationOngoing = false,
                    lastParsedNavigation = null,
                    lastStatusMessage = reason,
                    lastUpdatedAtMs = System.currentTimeMillis()
                )
            }
            if (_listenerState.value.isAutoForwardEnabled) {
                registeredHandlerRef.get()?.onNavigationStopped()
            }
        }

        /**
         * Pure, unit-testable parser that extracts:
         * 1. Direction ([TurnTypeConstant])
         * 2. Distance Remaining to next turn (normalized integer meters)
         * 3. ETA Duration (integer hours and minutes, ignoring arrival clock times)
         *
         * Returns `null` if the notification is not a valid navigation instruction.
         */
        fun parseNavigationNotification(
            title: String,
            text: String,
            subText: String,
            category: String? = Notification.CATEGORY_NAVIGATION,
            tickerText: String = "",
            extraStrings: List<String> = emptyList(),
            iconResourceName: String? = null,
            iconShapeDirection: TurnTypeConstant? = null,
            fallbackPreviousDirection: TurnTypeConstant? = null
        ): ParsedGoogleMapsNavigation? {
            val cleanTitle = title.replace('\u00A0', ' ').trim()
            val cleanText = text.replace('\u00A0', ' ').trim()
            val cleanSubText = subText.replace('\u00A0', ' ').trim()
            val cleanTicker = tickerText.replace('\u00A0', ' ').trim()

            if (cleanTitle.isEmpty() && cleanText.isEmpty() && cleanSubText.isEmpty() && cleanTicker.isEmpty() && iconResourceName.isNullOrEmpty()) {
                return null
            }

            val combinedLower = "$cleanTitle | $cleanText | $cleanSubText | $cleanTicker".lowercase(Locale.US)
            if (isNonNavigationOrTransientAlert(combinedLower)) {
                return null
            }

            val extractedDistance = parseDistanceToTurnMeters(cleanTitle, cleanText.ifBlank { cleanTicker })
            val isArrival = isDestinationArrivalText(cleanTitle, cleanText, cleanSubText, extractedDistance) ||
                (iconResourceName != null && parseDirectionFromIconResourceName(iconResourceName) == TurnTypeConstant.DESTINATION_REACHED)

            if (isArrival) {
                return ParsedGoogleMapsNavigation(
                    direction = TurnTypeConstant.DESTINATION_REACHED,
                    distanceMeters = 0,
                    etaHours = 0,
                    etaMinutes = 0,
                    isDestinationArrived = true,
                    rawTitle = cleanTitle,
                    rawText = cleanText,
                    rawSubText = cleanSubText
                )
            }

            // Direction Extraction with multi-tier resolution:
            // 1. Icon resource name check
            val iconDirection = parseDirectionFromIconResourceName(iconResourceName)

            // 2. Text direction from all textual sources (tickerText, title, text, subText, extraStrings)
            val allCandidates = buildList {
                if (cleanTicker.isNotBlank()) add(cleanTicker)
                if (cleanTitle.isNotBlank()) add(cleanTitle)
                if (cleanText.isNotBlank()) add(cleanText)
                if (cleanSubText.isNotBlank()) add(cleanSubText)
                addAll(extraStrings)
            }
            val textDirection = parseTurnDirectionFromCandidates(allCandidates)

            // 3. Fallback to icon shape analysis (pixel distribution)
            val shapeDirection = iconShapeDirection

            val etaPair = parseEtaDurationHoursAndMinutes(cleanSubText, cleanText, cleanTitle)

            // Reject generic background notifications that lack both a navigation category and turn/distance/ETA cues
            val hasNavCategory = category == Notification.CATEGORY_NAVIGATION
            if (textDirection == null && iconDirection == null && shapeDirection == null && extractedDistance == null) {
                return null
            }
            if (!hasNavCategory && textDirection == null && iconDirection == null && etaPair == null) {
                return null
            }

            // Priority: Explicit text direction -> Icon resource name -> Icon shape -> Latch previous direction (if countdown in progress) -> CONTINUE_STRAIGHT
            val resolvedDirection = textDirection
                ?: iconDirection
                ?: shapeDirection
                ?: (if ((extractedDistance ?: 0) > 0 && fallbackPreviousDirection != null && fallbackPreviousDirection != TurnTypeConstant.DESTINATION_REACHED) {
                    fallbackPreviousDirection
                } else {
                    TurnTypeConstant.CONTINUE_STRAIGHT
                })

            val resolvedDistanceMeters = (extractedDistance ?: 0).coerceIn(0, 65535)
            val (etaHours, etaMinutes) = etaPair ?: (0 to 0)

            return ParsedGoogleMapsNavigation(
                direction = resolvedDirection,
                distanceMeters = resolvedDistanceMeters,
                etaHours = etaHours,
                etaMinutes = etaMinutes,
                isDestinationArrived = resolvedDirection == TurnTypeConstant.DESTINATION_REACHED,
                rawTitle = cleanTitle,
                rawText = cleanText,
                rawSubText = cleanSubText
            )
        }

        internal fun isNonNavigationOrTransientAlert(combinedLower: String): Boolean {
            return IGNORED_NON_NAV_PATTERNS.any { pattern -> combinedLower.contains(pattern) }
        }

        internal fun isDestinationArrivalText(
            title: String,
            text: String,
            subText: String,
            extractedDistanceMeters: Int?
        ): Boolean {
            val primaryLower = "$title | $text | $subText".lowercase(Locale.US)
            if (
                primaryLower.contains("you have arrived") ||
                primaryLower.contains("you've arrived") ||
                primaryLower.contains("destination reached") ||
                primaryLower.contains("arrived at") ||
                Regex("""(?i)\barrived\b""").containsMatchIn(primaryLower)
            ) {
                return true
            }
            // Google Maps final step: "Destination will be on the left/right" when within 15 meters or 0m
            if (primaryLower.contains("destination") && (extractedDistanceMeters == null || extractedDistanceMeters <= 15)) {
                return true
            }
            return false
        }

        /**
         * Requirement 2: Parses the next turn direction from the notification title, text, or subtext
         * and maps it to [TurnTypeConstant].
         *
         * Strips street targets ("onto ...", "toward ...", "on ...") for primary matching first so a street
         * named "Right Way" or "North Left Ave" never overrides the actual turn instruction!
         */
        fun parseTurnDirection(
            title: String,
            text: String,
            subText: String = ""
        ): TurnTypeConstant? {
            val candidates = listOf(title, text, "$title $text", subText)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            return parseTurnDirectionFromCandidates(candidates)
        }

        internal fun parseTurnDirectionFromCandidates(candidates: List<String>): TurnTypeConstant? {
            for (rawCandidate in candidates) {
                // Strip street suffix after "onto / toward / towards / on" to isolate the maneuver verb first
                val maneuverPrefixOnly = rawCandidate
                    .replace(DISTANCE_TOKEN_REGEX, " ")
                    .split(Regex("""(?i)\b(?:onto|toward|towards)\b"""), limit = 2)
                    .firstOrNull()
                    ?.trim()
                    .orEmpty()

                matchManeuverPhrase(maneuverPrefixOnly)?.let { return it }
                matchManeuverPhrase(rawCandidate)?.let { return it }
            }
            return null
        }

        internal fun matchManeuverPhrase(input: String): TurnTypeConstant? {
            if (input.isBlank()) return null
            val lower = input.lowercase(Locale.US)

            // 1. Destination / Arrival
            if (
                lower.contains("destination reached") ||
                lower.contains("you have arrived") ||
                lower.contains("you've arrived") ||
                Regex("""\b(?:arrived|arrive)\b""").containsMatchIn(lower)
            ) {
                return TurnTypeConstant.DESTINATION_REACHED
            }

            // 2. Roundabout / Traffic Circle / Rotary + Exit numbers
            if (lower.contains("1st exit") || lower.contains("first exit") || lower.contains("exit 1")) {
                return TurnTypeConstant.ROUNDABOUT_EXIT_1
            }
            if (lower.contains("2nd exit") || lower.contains("second exit") || lower.contains("exit 2")) {
                return TurnTypeConstant.ROUNDABOUT_EXIT_2
            }
            if (lower.contains("3rd exit") || lower.contains("third exit") || lower.contains("exit 3")) {
                return TurnTypeConstant.ROUNDABOUT_EXIT_3
            }
            if (lower.contains("4th exit") || lower.contains("fourth exit") || lower.contains("exit 4")) {
                return TurnTypeConstant.ROUNDABOUT
            }
            if (lower.contains("5th exit") || lower.contains("fifth exit") || lower.contains("exit 5") || lower.contains("roundabout left")) {
                return TurnTypeConstant.ROUNDABOUT_LEFT
            }
            if (lower.contains("6th exit") || lower.contains("sixth exit") || lower.contains("exit 6")) {
                return TurnTypeConstant.ROUNDABOUT_EXIT_6_RIGHT_2
            }
            if (lower.contains("7th exit") || lower.contains("seventh exit") || lower.contains("exit 7")) {
                return TurnTypeConstant.ROUNDABOUT_EXIT_7_RIGHT_1
            }
            if (lower.contains("rotary") || lower.contains("traffic circle")) {
                return TurnTypeConstant.ROTARY
            }
            if (lower.contains("roundabout")) {
                return TurnTypeConstant.ROUNDABOUT
            }

            // 3. U-Turns (Right vs Left/Standard)
            if (lower.contains("right u-turn") || lower.contains("u-turn right") || lower.contains("right u turn")) {
                return TurnTypeConstant.RIGHT_U_TURN
            }
            if (lower.contains("u-turn") || lower.contains("u turn") || lower.contains("uturn")) {
                return TurnTypeConstant.U_TURN
            }

            // 4. Sharp & Slight Turns
            if (lower.contains("sharp left") || lower.contains("sharply left") || lower.contains("hard left")) {
                return TurnTypeConstant.TURN_SHARPLY_LEFT
            }
            if (lower.contains("sharp right") || lower.contains("sharply right") || lower.contains("hard right")) {
                return TurnTypeConstant.TURN_SHARPLY_RIGHT
            }
            if (
                lower.contains("slight left") ||
                lower.contains("slightly left") ||
                lower.contains("bear left") ||
                lower.contains("curve left")
            ) {
                return TurnTypeConstant.TURN_SLIGHTLY_LEFT
            }
            if (
                lower.contains("slight right") ||
                lower.contains("slightly right") ||
                lower.contains("bear right") ||
                lower.contains("curve right")
            ) {
                return TurnTypeConstant.TURN_SLIGHTLY_RIGHT
            }

            // 5. Forks
            if (lower.contains("middle fork") || lower.contains("center fork")) {
                return TurnTypeConstant.MIDDLE_FORK
            }
            if (lower.contains("fork left") || lower.contains("left at the fork") || lower.contains("left fork")) {
                return TurnTypeConstant.FORK_LEFT
            }
            if (lower.contains("fork right") || lower.contains("right at the fork") || lower.contains("right fork")) {
                return TurnTypeConstant.FORK_RIGHT
            }

            // 6. Highway Exits, Ramps, Ferry & Border Crossing
            if (lower.contains("leave ferry") || lower.contains("exit ferry")) {
                return TurnTypeConstant.LEAVE_FERRY
            }
            if (lower.contains("ferry")) {
                return TurnTypeConstant.TAKE_FERRY
            }
            if (lower.contains("border crossing") || lower.contains("enter country")) {
                return TurnTypeConstant.BORDER_CROSSING
            }
            if (lower.contains("exit left") || lower.contains("left exit") || lower.contains("ramp on the left")) {
                return TurnTypeConstant.HIGHWAY_EXIT_LEFT
            }
            if (lower.contains("exit right") || lower.contains("right exit") || lower.contains("ramp on the right")) {
                return TurnTypeConstant.FORK_RIGHT
            }
            if (lower.contains("enter highway") || lower.contains("onto highway") || lower.contains("merge onto")) {
                return TurnTypeConstant.HIGHWAY_ENTER_LEFT
            }

            // 7. Keep Left / Keep Right (Intersection & Standard)
            if (lower.contains("keep left at")) {
                return TurnTypeConstant.KEEP_LEFT_AT_INTERSECTION
            }
            if (lower.contains("keep right at")) {
                return TurnTypeConstant.KEEP_RIGHT_AT_INTERSECTION
            }
            if (lower.contains("keep left") || lower.contains("stay left") || lower.contains("use the left")) {
                return TurnTypeConstant.KEEP_LEFT
            }
            if (lower.contains("keep right") || lower.contains("stay right") || lower.contains("use the right")) {
                return TurnTypeConstant.KEEP_RIGHT
            }

            // 8. Take Left / Take Right at end or intersection
            // Maps to standard TURN_LEFT / TURN_RIGHT so cluster firmware displays native arrows
            if (lower.contains("left at the end") || lower.contains("take left at end") || lower.contains("take left") || lower.contains("take the left")) {
                return TurnTypeConstant.TURN_LEFT
            }
            if (lower.contains("right at the end") || lower.contains("take right at end") || lower.contains("take right") || lower.contains("take the right")) {
                return TurnTypeConstant.TURN_RIGHT
            }

            // 9. Standard Turn Left / Turn Right
            // Check multi-word directional cues first:
            val hasExplicitTurnRight = lower.contains("turn right") ||
                lower.contains("right onto") ||
                lower.contains("right toward") ||
                lower.contains("right towards") ||
                lower.contains("right at") ||
                lower.contains("right turn") ||
                lower.contains("make a right") ||
                lower.contains("next right")

            val hasExplicitTurnLeft = lower.contains("turn left") ||
                lower.contains("left onto") ||
                lower.contains("left toward") ||
                lower.contains("left towards") ||
                lower.contains("left at") ||
                lower.contains("left turn") ||
                lower.contains("make a left") ||
                lower.contains("next left")

            if (hasExplicitTurnRight && !hasExplicitTurnLeft) return TurnTypeConstant.TURN_RIGHT
            if (hasExplicitTurnLeft && !hasExplicitTurnRight) return TurnTypeConstant.TURN_LEFT

            // Isolated token matching:
            val matchesRight = Regex("""\bright\b""").containsMatchIn(lower)
            val matchesLeft = Regex("""\bleft\b""").containsMatchIn(lower)

            if (matchesRight && !matchesLeft) return TurnTypeConstant.TURN_RIGHT
            if (matchesLeft && !matchesRight) return TurnTypeConstant.TURN_LEFT

            // 10. Straight / Continue / Head / Merge
            if (lower.contains("straight at")) {
                return TurnTypeConstant.CONTINUE_STRAIGHT_AT_INTERSECTION
            }
            if (
                lower.contains("continue") ||
                lower.contains("straight") ||
                Regex("""\b(?:head|proceed|follow|merge)\b""").containsMatchIn(lower)
            ) {
                return TurnTypeConstant.CONTINUE_STRAIGHT
            }

            return null
        }

        /**
         * Requirement 3: Extracts the distance to the next turn from the notification text (`title` or `text`)
         * and normalizes the value into integer meters.
         */
        fun parseDistanceToTurnMeters(title: String, text: String): Int? {
            // Google Maps places the next-turn distance in `title` (e.g., "250 m" or "1.2 km · Turn left")
            // or at the start of `text` (e.g., "In 300 m, turn right onto MG Rd").
            for (source in listOf(title, text)) {
                if (source.isBlank()) continue
                val match = DISTANCE_TOKEN_REGEX.find(source) ?: continue
                val rawNumber = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
                val rawUnit = match.groupValues[2].lowercase(Locale.US)
                val meters = when (rawUnit) {
                    "km", "kilometer", "kilometers", "kilometre", "kilometres" -> (rawNumber * 1000.0).roundToInt()
                    "m", "meter", "meters", "metre", "metres" -> rawNumber.roundToInt()
                    "mi", "mile", "miles" -> (rawNumber * 1609.344).roundToInt()
                    "yd", "yds", "yard", "yards" -> (rawNumber * 0.9144).roundToInt()
                    "ft", "foot", "feet" -> (rawNumber * 0.3048).roundToInt()
                    else -> rawNumber.roundToInt()
                }
                return meters.coerceIn(0, 65535)
            }
            return null
        }

        /**
         * Requirement 4: Extracts the remaining travel duration (ETA) into integer hours and minutes,
         * strictly ignoring absolute arrival clock times (e.g., "10:45 PM", "14:20", "ETA 09:15").
         */
        fun parseEtaDurationHoursAndMinutes(
            subText: String,
            text: String = "",
            title: String = ""
        ): Pair<Int, Int>? {
            // Google Maps puts remaining duration in `subText` (e.g. "1 hr 15 min · 42 km · 10:45 PM" or "18 min (6.2 km) · 9:30 AM")
            for (source in listOf(subText, text, title)) {
                if (source.isBlank()) continue

                // 1. Strip out absolute arrival clock times ("10:45 PM", "ETA 14:20") and distance tokens ("250 m", "4.5 km")
                val sanitized = source
                    .replace(CLOCK_TIME_REGEX, " ")
                    .replace(DISTANCE_TOKEN_REGEX, " ")

                val days = DURATION_DAYS_REGEX.find(sanitized)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val hoursMatch = DURATION_HOURS_REGEX.find(sanitized)?.groupValues?.get(1)?.toIntOrNull()
                val minutesMatch = DURATION_MINUTES_REGEX.find(sanitized)?.groupValues?.get(1)?.toIntOrNull()

                if (days > 0 || hoursMatch != null || minutesMatch != null) {
                    val totalMinutes = (days * 24 * 60) + ((hoursMatch ?: 0) * 60) + (minutesMatch ?: 0)
                    val normalizedHours = (totalMinutes / 60).coerceIn(0, 99)
                    val normalizedMinutes = (totalMinutes % 60).coerceIn(0, 59)
                    return normalizedHours to normalizedMinutes
                }
            }
            return null
        }
    }
}
