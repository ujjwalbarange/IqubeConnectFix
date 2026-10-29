package com.example.ble

import android.app.Notification
import android.content.ComponentName
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
import java.util.concurrent.atomic.AtomicLong
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
        etaMinutes: Int,
        streetOrLocationName: String = ""
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
            requestRebind(ComponentName(applicationContext, GoogleMapsNotificationListenerService::class.java))
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

    data class NotificationExtrasSnapshot(
        val title: String,
        val text: String,
        val subText: String,
        val bigText: String,
        val tickerText: String = "",
        val allExtraStrings: List<String> = emptyList(),
        val iconResourceName: String? = null,
        val iconShapeDirection: TurnTypeConstant? = null,
        val largeIconBitmap: Bitmap? = null
    )

    companion object {
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
        const val COUNTDOWN_THROTTLE_MS = 3_000L

        private val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private data class DeduplicationKey(
            val direction: TurnTypeConstant,
            val distanceMeters: Int,
            val etaHours: Int,
            val etaMinutes: Int,
            val isDestinationArrived: Boolean,
            val streetOrLocationName: String = "",
            val action: ManeuverAction = ManeuverAction.UNKNOWN,
            val modifier: TurnModifier = TurnModifier.NONE,
            val roundaboutExit: Int? = null,
            val exitNumber: String? = null
        )

        private val lastDispatchedKey = AtomicReference<DeduplicationKey?>(null)
        private val lastDispatchedAtMs = AtomicLong(0L)
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

        private val UI_ACTION_LABELS_TO_IGNORE = setOf(
            "exit navigation",
            "exit",
            "dismiss",
            "mute",
            "unmute",
            "stop",
            "hide",
            "share",
            "re-centre",
            "re-center",
            "overview",
            "preview",
            "steps"
        )

        private fun isIgnoredUiLabel(text: String): Boolean {
            val lower = text.lowercase(Locale.US).trim()
            return lower.isEmpty() || lower in UI_ACTION_LABELS_TO_IGNORE
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
            val candidateIcons = mutableListOf<Icon>()
            val candidateBitmaps = mutableListOf<Bitmap>()

            if (extras != null) {
                for (key in extras.keySet()) {
                    runCatching {
                        // Never inspect EXTRA_SMALL_ICON for turn direction (it is the static Maps app icon)
                        if (key == Notification.EXTRA_SMALL_ICON) return@runCatching
                        when (val value = extras.get(key)) {
                            is CharSequence -> {
                                val s = value.toString().replace('\u00A0', ' ').trim()
                                if (!isIgnoredUiLabel(s) && !allExtraStrings.contains(s)) {
                                    allExtraStrings.add(s)
                                }
                            }
                            is Array<*> -> {
                                value.forEach { item ->
                                    if (item is CharSequence) {
                                        val s = item.toString().replace('\u00A0', ' ').trim()
                                        if (!isIgnoredUiLabel(s) && !allExtraStrings.contains(s)) {
                                            allExtraStrings.add(s)
                                        }
                                    }
                                }
                            }
                            is Icon -> {
                                if (!candidateIcons.contains(value)) candidateIcons.add(value)
                            }
                            is Bitmap -> {
                                if (!candidateBitmaps.contains(value)) candidateBitmaps.add(value)
                            }
                        }
                    }
                }
            }

            if (notification != null) {
                // Prioritize notification.getLargeIcon() and deprecated largeIcon Bitmap (the white maneuver graphic)
                // NEVER use notification.smallIcon, which is always the static Google Maps status-bar app icon!
                notification.getLargeIcon()?.let { icon ->
                    if (!candidateIcons.contains(icon)) candidateIcons.add(0, icon)
                }
                @Suppress("DEPRECATION")
                notification.largeIcon?.let { bmp ->
                    if (!candidateBitmaps.contains(bmp)) candidateBitmaps.add(0, bmp)
                }

                // Extract strings, TalkBack contentDescriptions, Icons, and Bitmaps from custom RemoteViews
                val remoteViewsList = listOfNotNull(
                    notification.bigContentView,
                    notification.contentView,
                    notification.headsUpContentView
                )
                for (rv in remoteViewsList) {
                    val rvStrings = extractStringsFromRemoteViews(rv, context)
                    for (s in rvStrings) {
                        if (!isIgnoredUiLabel(s) && !allExtraStrings.contains(s)) {
                            allExtraStrings.add(s)
                        }
                    }
                    val (rvIcons, rvBitmaps) = extractIconsAndBitmapsFromRemoteViews(rv, context)
                    for (ic in rvIcons) {
                        if (!candidateIcons.contains(ic)) candidateIcons.add(ic)
                    }
                    for (bmp in rvBitmaps) {
                        if (!candidateBitmaps.contains(bmp)) candidateBitmaps.add(bmp)
                    }
                }
            }

            var iconResName: String? = null
            var iconShapeDir: TurnTypeConstant? = null

            if (context != null) {
                // 1. Check resource names on candidate maneuver icons
                for (icon in candidateIcons) {
                    val resName = resolveIconResourceName(context, icon)
                    if (!resName.isNullOrBlank()) {
                        if (iconResName == null) iconResName = resName
                        if (parseDirectionFromIconResourceName(resName) != null) {
                            iconResName = resName
                            break
                        }
                    }
                }

                val resTurn = parseDirectionFromIconResourceName(iconResName)
                if (resTurn == null) {
                    // 2. Classify maneuver bitmap / icon pixel geometry
                    for (icon in candidateIcons) {
                        val dir = classifyArrowFromIcon(context, icon)
                        if (dir != null) {
                            iconShapeDir = dir
                            break
                        }
                    }
                    if (iconShapeDir == null) {
                        for (bmp in candidateBitmaps) {
                            val dir = classifyManeuverBitmap(bmp)
                            if (dir != null) {
                                iconShapeDir = dir
                                break
                            }
                        }
                    }
                }
            } else {
                for (bmp in candidateBitmaps) {
                    val dir = classifyManeuverBitmap(bmp)
                    if (dir != null) {
                        iconShapeDir = dir
                        break
                    }
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
                iconShapeDirection = iconShapeDir,
                largeIconBitmap = candidateBitmaps.firstOrNull()
            )
        }

        internal fun extractStringsFromRemoteViews(
            rv: android.widget.RemoteViews?,
            context: Context? = null
        ): List<String> {
            if (rv == null) return emptyList()
            val contentDescriptions = mutableListOf<String>()
            val otherStrings = mutableListOf<String>()

            // 1. Try inflating the RemoteViews to inspect actual View contentDescriptions (TalkBack maneuver labels) and TextView texts
            if (context != null) {
                runCatching {
                    val remoteContext = runCatching {
                        context.createPackageContext(GOOGLE_MAPS_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                    }.getOrNull() ?: context
                    val container = android.widget.FrameLayout(remoteContext)
                    val inflated = rv.apply(remoteContext, container)
                    val queue = ArrayDeque<android.view.View>()
                    queue.add(inflated)
                    while (queue.isNotEmpty()) {
                        val view = queue.removeFirst()
                        val desc = view.contentDescription?.toString()?.replace('\u00A0', ' ')?.trim().orEmpty()
                        if (!isIgnoredUiLabel(desc) && !contentDescriptions.contains(desc)) {
                            contentDescriptions.add(desc)
                        }
                        if (view is android.widget.TextView) {
                            val t = view.text?.toString()?.replace('\u00A0', ' ')?.trim().orEmpty()
                            if (!isIgnoredUiLabel(t) && !otherStrings.contains(t)) {
                                otherStrings.add(t)
                            }
                        }
                        if (view is android.view.ViewGroup) {
                            for (i in 0 until view.childCount) {
                                view.getChildAt(i)?.let { queue.add(it) }
                            }
                        }
                    }
                }
            }

            // 2. Walk mActions and ALL superclasses (e.g., BaseReflectionAction.value for setContentDescription / setText)
            runCatching {
                var rvClass: Class<*>? = rv.javaClass
                var actions: List<*>? = null
                while (rvClass != null && rvClass != Any::class.java && actions == null) {
                    runCatching {
                        val f = rvClass!!.getDeclaredField("mActions")
                        f.isAccessible = true
                        actions = f.get(rv) as? List<*>
                    }
                    rvClass = rvClass.superclass
                }
                for (action in actions.orEmpty()) {
                    if (action == null) continue
                    var methodName = ""
                    var cls: Class<*>? = action.javaClass
                    val actionStrings = mutableListOf<String>()
                    while (cls != null && cls != Any::class.java) {
                        for (field in cls.declaredFields) {
                            runCatching {
                                field.isAccessible = true
                                val v = field.get(action)
                                if (field.name == "methodName" && v is String) {
                                    methodName = v
                                } else if (v is CharSequence) {
                                    val s = v.toString().replace('\u00A0', ' ').trim()
                                    if (!isIgnoredUiLabel(s) && !actionStrings.contains(s)) {
                                        actionStrings.add(s)
                                    }
                                }
                            }
                        }
                        cls = cls.superclass
                    }
                    for (s in actionStrings) {
                        if (methodName.equals("setContentDescription", ignoreCase = true)) {
                            if (!contentDescriptions.contains(s)) contentDescriptions.add(s)
                        } else {
                            if (!otherStrings.contains(s)) otherStrings.add(s)
                        }
                    }
                }
            }

            // Put contentDescriptions FIRST because Google Maps sets the exact turn instruction on the maneuver ImageView's contentDescription
            return (contentDescriptions + otherStrings).distinct()
        }

        internal fun extractIconsAndBitmapsFromRemoteViews(
            rv: android.widget.RemoteViews?,
            context: Context? = null
        ): Pair<List<Icon>, List<Bitmap>> {
            if (rv == null) return emptyList<Icon>() to emptyList()
            val icons = mutableListOf<Icon>()
            val bitmaps = mutableListOf<Bitmap>()

            // 1. Inspect mActions across superclasses for Icon / Bitmap values
            runCatching {
                var rvClass: Class<*>? = rv.javaClass
                var actions: List<*>? = null
                while (rvClass != null && rvClass != Any::class.java && actions == null) {
                    runCatching {
                        val f = rvClass!!.getDeclaredField("mActions")
                        f.isAccessible = true
                        actions = f.get(rv) as? List<*>
                    }
                    rvClass = rvClass.superclass
                }
                for (action in actions.orEmpty()) {
                    if (action == null) continue
                    var cls: Class<*>? = action.javaClass
                    while (cls != null && cls != Any::class.java) {
                        for (field in cls.declaredFields) {
                            runCatching {
                                field.isAccessible = true
                                when (val v = field.get(action)) {
                                    is Icon -> if (!icons.contains(v)) icons.add(v)
                                    is Bitmap -> if (v.width >= 20 && v.height >= 20 && !bitmaps.contains(v)) bitmaps.add(v)
                                }
                            }
                        }
                        cls = cls.superclass
                    }
                }
            }

            // 2. Inspect mBitmapCache on RemoteViews
            runCatching {
                var rvClass: Class<*>? = rv.javaClass
                while (rvClass != null && rvClass != Any::class.java) {
                    runCatching {
                        val cacheField = rvClass!!.getDeclaredField("mBitmapCache")
                        cacheField.isAccessible = true
                        val cache = cacheField.get(rv)
                        if (cache != null) {
                            val bitmapsField = cache.javaClass.getDeclaredField("mBitmaps")
                            bitmapsField.isAccessible = true
                            val list = bitmapsField.get(cache) as? List<*>
                            list?.forEach { item ->
                                if (item is Bitmap && item.width >= 20 && item.height >= 20 && !bitmaps.contains(item)) {
                                    bitmaps.add(item)
                                }
                            }
                        }
                    }
                    rvClass = rvClass.superclass
                }
            }

            // 3. Fallback: inspect ImageViews from inflated RemoteViews
            if (context != null && icons.isEmpty() && bitmaps.isEmpty()) {
                runCatching {
                    val remoteContext = runCatching {
                        context.createPackageContext(GOOGLE_MAPS_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                    }.getOrNull() ?: context
                    val container = android.widget.FrameLayout(remoteContext)
                    val inflated = rv.apply(remoteContext, container)
                    val queue = ArrayDeque<android.view.View>()
                    queue.add(inflated)
                    while (queue.isNotEmpty()) {
                        val view = queue.removeFirst()
                        if (view is android.widget.ImageView) {
                            val d = view.drawable
                            if (d != null && d.intrinsicWidth >= 24 && d.intrinsicHeight >= 24) {
                                val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(bmp)
                                d.setBounds(0, 0, 64, 64)
                                d.draw(canvas)
                                bitmaps.add(bmp)
                            }
                        }
                        if (view is android.view.ViewGroup) {
                            for (i in 0 until view.childCount) {
                                view.getChildAt(i)?.let { queue.add(it) }
                            }
                        }
                    }
                }
            }

            return icons to bitmaps
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
                    val remoteContext = runCatching {
                        context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
                    }.getOrNull() ?: context
                    val pm = remoteContext.packageManager
                    val res = pm.getResourcesForApplication(pkg)
                    res.getResourceEntryName(resId)
                } else null
            }.getOrNull()
        }

        internal fun classifyArrowFromIcon(context: Context, icon: Icon?): TurnTypeConstant? {
            if (icon == null) return null
            return runCatching {
                val remoteContext = runCatching {
                    context.createPackageContext(GOOGLE_MAPS_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                }.getOrNull() ?: context

                val drawable = icon.loadDrawable(remoteContext)
                    ?: icon.loadDrawable(context)
                    ?: return null

                val size = 64
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)

                classifyManeuverBitmap(bitmap)
            }.getOrNull()
        }

        /**
         * Classifies a Google Maps maneuver [Bitmap] using normalized bounding-box geometry,
         * horizontal scanline run-count topology (to detect hollow U-turn arches and roundabout circles),
         * and upper-vs-lower horizontal center-of-mass shift.
         *
         * Accurately distinguishes:
         * - [TurnTypeConstant.U_TURN] (standard U-turn `↶`/`↷` and Roundabout U-turn with upper loop + two bottom stems)
         * - [TurnTypeConstant.ROUNDABOUT] / [TurnTypeConstant.ROUNDABOUT_LEFT]
         * - [TurnTypeConstant.TURN_RIGHT] (`↱`) & [TurnTypeConstant.TURN_SLIGHTLY_RIGHT] (`↗`) & [TurnTypeConstant.TURN_SHARPLY_RIGHT] (`↘`)
         * - [TurnTypeConstant.TURN_LEFT] (`↰`) & [TurnTypeConstant.TURN_SLIGHTLY_LEFT] (`↖`) & [TurnTypeConstant.TURN_SHARPLY_LEFT] (`↙`)
         * - [TurnTypeConstant.CONTINUE_STRAIGHT] (`↑`)
         */
        internal fun classifyManeuverBitmap(sourceBitmap: Bitmap?): TurnTypeConstant? {
            if (sourceBitmap == null || sourceBitmap.width < 8 || sourceBitmap.height < 8) return null
            return runCatching {
                val size = 64
                val bitmap = if (sourceBitmap.width == size && sourceBitmap.height == size) {
                    sourceBitmap
                } else {
                    Bitmap.createScaledBitmap(sourceBitmap, size, size, true)
                }

                // 1. Identify foreground maneuver arrow pixels (prefer bright white arrow pixels so any dark/colored notification circle is ignored)
                var whiteCount = 0
                var opaqueCount = 0
                for (y in 0 until size) {
                    for (x in 0 until size) {
                        val px = bitmap.getPixel(x, y)
                        val a = (px ushr 24) and 0xFF
                        if (a > 70) {
                            opaqueCount++
                            val r = (px ushr 16) and 0xFF
                            val g = (px ushr 8) and 0xFF
                            val b = px and 0xFF
                            if (a > 110 && r > 170 && g > 170 && b > 170) {
                                whiteCount++
                            }
                        }
                    }
                }

                val useWhiteMask = whiteCount in 14..(size * size * 3 / 4)
                val fg = Array(size) { BooleanArray(size) }
                var minX = size
                var maxX = -1
                var minY = size
                var maxY = -1
                var fgCount = 0

                for (y in 0 until size) {
                    for (x in 0 until size) {
                        val px = bitmap.getPixel(x, y)
                        val a = (px ushr 24) and 0xFF
                        val r = (px ushr 16) and 0xFF
                        val g = (px ushr 8) and 0xFF
                        val b = px and 0xFF
                        val isFg = if (useWhiteMask) {
                            a > 110 && r > 170 && g > 170 && b > 170
                        } else {
                            a > 70
                        }
                        if (isFg) {
                            fg[y][x] = true
                            fgCount++
                            if (x < minX) minX = x
                            if (x > maxX) maxX = x
                            if (y < minY) minY = y
                            if (y > maxY) maxY = y
                        }
                    }
                }

                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                if (fgCount < 14 || bw < 6 || bh < 6) return null

                // 2. Compute normalized bounding-box statistics (nx, ny in [0.0, 1.0])
                var topSumX = 0f
                var topCount = 0
                var veryTopSumX = 0f
                var veryTopCount = 0
                var bottomSumX = 0f
                var bottomCount = 0

                var topLeft = 0
                var topRight = 0
                var bottomLeft = 0
                var bottomRight = 0

                var leftEdgeSumY = 0f
                var leftEdgeCount = 0
                var rightEdgeSumY = 0f
                var rightEdgeCount = 0

                var topCapHits = 0
                var topCapTotal = 0
                var centerCoreHits = 0
                var centerCoreTotal = 0

                for (y in minY..maxY) {
                    val ny = (y - minY).toFloat() / (bh - 1).coerceAtLeast(1)
                    for (x in minX..maxX) {
                        val nx = (x - minX).toFloat() / (bw - 1).coerceAtLeast(1)

                        if (ny <= 0.20f && nx in 0.34f..0.66f) {
                            topCapTotal++
                            if (fg[y][x]) topCapHits++
                        }
                        if (ny in 0.24f..0.48f && nx in 0.38f..0.62f) {
                            centerCoreTotal++
                            if (fg[y][x]) centerCoreHits++
                        }

                        if (!fg[y][x]) continue

                        if (ny < 0.22f) {
                            veryTopSumX += nx
                            veryTopCount++
                        }
                        if (ny < 0.50f) {
                            topSumX += nx
                            topCount++
                            if (nx < 0.50f) topLeft++ else topRight++
                        }
                        if (ny >= 0.54f) {
                            bottomSumX += nx
                            bottomCount++
                            if (nx < 0.50f) bottomLeft++ else bottomRight++
                        }
                        if (nx <= 0.22f) {
                            leftEdgeSumY += ny
                            leftEdgeCount++
                        }
                        if (nx >= 0.78f) {
                            rightEdgeSumY += ny
                            rightEdgeCount++
                        }
                    }
                }

                if (topCount == 0 || bottomCount == 0) return null

                val topMeanX = topSumX / topCount
                val veryTopMeanX = if (veryTopCount > 0) veryTopSumX / veryTopCount else topMeanX
                val bottomMeanX = bottomSumX / bottomCount
                val leftEdgeMeanY = if (leftEdgeCount > 0) leftEdgeSumY / leftEdgeCount else 0.5f
                val rightEdgeMeanY = if (rightEdgeCount > 0) rightEdgeSumY / rightEdgeCount else 0.5f
                val topCapDensity = if (topCapTotal > 0) topCapHits.toFloat() / topCapTotal else 0f
                val centerCoreDensity = if (centerCoreTotal > 0) centerCoreHits.toFloat() / centerCoreTotal else 0f

                // 3. Count separated horizontal foreground runs per scanline in upper-mid (0.20..0.54) and lower (0.54..0.86)
                val minGapPx = (bw / 11).coerceAtLeast(2)
                fun countRunsInRow(y: Int): Int {
                    var runs = 0
                    var inRun = false
                    var gap = 0
                    for (x in minX..maxX) {
                        if (fg[y][x]) {
                            if (!inRun) {
                                if (runs == 0 || gap >= minGapPx) {
                                    runs++
                                }
                                inRun = true
                            }
                            gap = 0
                        } else if (inRun) {
                            gap++
                            if (gap >= minGapPx) {
                                inRun = false
                            }
                        }
                    }
                    return runs
                }

                var midRows = 0
                var midTwoRunRows = 0
                var bottomRows = 0
                var bottomTwoRunRows = 0

                for (y in minY..maxY) {
                    val ny = (y - minY).toFloat() / (bh - 1).coerceAtLeast(1)
                    val runs = countRunsInRow(y)
                    if (ny in 0.20f..0.54f) {
                        midRows++
                        if (runs >= 2) midTwoRunRows++
                    } else if (ny in 0.54f..0.86f) {
                        bottomRows++
                        if (runs >= 2) bottomTwoRunRows++
                    }
                }

                val midTwoRunRatio = if (midRows > 0) midTwoRunRows.toFloat() / midRows else 0f
                val bottomTwoRunRatio = if (bottomRows > 0) bottomTwoRunRows.toFloat() / bottomRows else 0f

                val bottomHasTwoSides = bottomLeft >= (bottomCount * 0.16f) && bottomRight >= (bottomCount * 0.16f)
                val hasUpperHollowLoop = centerCoreDensity < 0.26f && midTwoRunRatio >= 0.22f && topCapDensity >= 0.14f

                // Rule 1: U-Turn (`↶`/`↷`) & Roundabout U-Turn (as in Screenshot_20261029-001507: hollow circle/arch at top + 2 separated stems at bottom)
                if (hasUpperHollowLoop && bottomTwoRunRatio >= 0.18f && bottomHasTwoSides) {
                    // Check if a roundabout has a strong lateral upper exit arm vs exiting straight down (U-turn)
                    return when {
                        topMeanX in 0.39f..0.61f -> TurnTypeConstant.U_TURN
                        topMeanX < 0.39f -> TurnTypeConstant.ROUNDABOUT_LEFT
                        else -> TurnTypeConstant.ROUNDABOUT
                    }
                }

                // Also catch standard U-turn where the arch is at the top, center core is hollow, and two parallel legs run down the middle & bottom
                if (centerCoreDensity < 0.28f && topCapDensity >= 0.16f && topMeanX in 0.38f..0.62f && midTwoRunRatio >= 0.30f && bottomTwoRunRatio >= 0.20f) {
                    return TurnTypeConstant.U_TURN
                }

                // Rule 2: Sharp Right (`↘`) & Sharp Left (`↙`) (vertical stem + downward-pointing diagonal arm in lower half)
                if (bottomTwoRunRatio >= 0.20f && bottomHasTwoSides && !hasUpperHollowLoop) {
                    if (rightEdgeCount > leftEdgeCount && rightEdgeMeanY >= 0.50f && topMeanX < 0.52f) {
                        return TurnTypeConstant.TURN_SHARPLY_RIGHT
                    }
                    if (leftEdgeCount > rightEdgeCount && leftEdgeMeanY >= 0.50f && topMeanX > 0.48f) {
                        return TurnTypeConstant.TURN_SHARPLY_LEFT
                    }
                }

                // Rule 3: 90° Turn Right (`↱`) & Slight Right (`↗`)
                // In a right turn, the upper arm/arrowhead shifts right relative to the bottom vertical stem (`topMeanX > bottomMeanX`)
                val dx = topMeanX - bottomMeanX
                val isStemLeftTipRight = dx > 0.08f && bottomMeanX < 0.49f && rightEdgeCount > 0 && rightEdgeMeanY < 0.58f
                val isUpperHeavyRight = topRight > topLeft * 1.22f && topMeanX > 0.52f && bottomRight < bottomLeft * 1.15f

                if (isStemLeftTipRight || isUpperHeavyRight) {
                    return if (veryTopMeanX > 0.66f && rightEdgeMeanY < 0.18f && bottomMeanX < 0.38f) {
                        TurnTypeConstant.TURN_SLIGHTLY_RIGHT
                    } else {
                        TurnTypeConstant.TURN_RIGHT
                    }
                }

                // Rule 4: 90° Turn Left (`↰`) & Slight Left (`↖`)
                // In a left turn, the upper arm/arrowhead shifts left relative to the bottom vertical stem (`topMeanX < bottomMeanX`)
                val isStemRightTipLeft = dx < -0.08f && bottomMeanX > 0.51f && leftEdgeCount > 0 && leftEdgeMeanY < 0.58f
                val isUpperHeavyLeft = topLeft > topRight * 1.22f && topMeanX < 0.48f && bottomLeft < bottomRight * 1.15f

                if (isStemRightTipLeft || isUpperHeavyLeft) {
                    return if (veryTopMeanX < 0.34f && leftEdgeMeanY < 0.18f && bottomMeanX > 0.62f) {
                        TurnTypeConstant.TURN_SLIGHTLY_LEFT
                    } else {
                        TurnTypeConstant.TURN_LEFT
                    }
                }

                // Rule 5: Roundabout with single bottom entry stem and hollow upper circle
                if (hasUpperHollowLoop) {
                    return if (topMeanX < 0.46f) TurnTypeConstant.ROUNDABOUT_LEFT else TurnTypeConstant.ROUNDABOUT
                }

                // Rule 6: Continue Straight (`↑`)
                TurnTypeConstant.CONTINUE_STRAIGHT
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
            snapshot: NotificationExtrasSnapshot,
            nowMs: Long = System.currentTimeMillis(),
            countdownThrottleMs: Long = COUNTDOWN_THROTTLE_MS
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
                largeIconBitmap = snapshot.largeIconBitmap,
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

            val turnCmd = parsed.turnCommand
            val newKey = DeduplicationKey(
                direction = parsed.direction,
                distanceMeters = parsed.distanceMeters,
                etaHours = parsed.etaHours,
                etaMinutes = parsed.etaMinutes,
                isDestinationArrived = parsed.isDestinationArrived,
                streetOrLocationName = parsed.streetOrLocationName,
                action = turnCmd?.action ?: ManeuverAction.UNKNOWN,
                modifier = turnCmd?.modifier ?: TurnModifier.NONE,
                roundaboutExit = turnCmd?.roundaboutExit,
                exitNumber = turnCmd?.exitNumber
            )

            // Exact duplicate check
            if (previousKey == newKey) {
                _listenerState.update {
                    it.copy(
                        ignoredDuplicateCount = it.ignoredDuplicateCount + 1,
                        lastUpdatedAtMs = nowMs
                    )
                }
                return
            }

            // High-priority immediate check vs routine distance countdown tick throttling (3 seconds):
            // Fire immediately if action, direction, modifier, roundaboutExit, exitNumber, arrival state, or street name changed.
            val isHighPriorityChange = previousKey == null ||
                previousKey.direction != newKey.direction ||
                previousKey.action != newKey.action ||
                previousKey.modifier != newKey.modifier ||
                previousKey.roundaboutExit != newKey.roundaboutExit ||
                previousKey.exitNumber != newKey.exitNumber ||
                previousKey.isDestinationArrived != newKey.isDestinationArrived ||
                previousKey.streetOrLocationName != newKey.streetOrLocationName

            val elapsedSinceLastDispatchMs = nowMs - lastDispatchedAtMs.get()
            if (!isHighPriorityChange && elapsedSinceLastDispatchMs in 0 until countdownThrottleMs) {
                _listenerState.update {
                    it.copy(
                        throttledCountdownCount = it.throttledCountdownCount + 1,
                        lastUpdatedAtMs = nowMs
                    )
                }
                return
            }

            lastDispatchedKey.set(newKey)
            lastDispatchedAtMs.set(nowMs)

            _listenerState.update {
                it.copy(
                    isActiveNavigationOngoing = !parsed.isDestinationArrived,
                    lastParsedNavigation = parsed,
                    lastTurnCommand = parsed.turnCommand,
                    dispatchedUpdateCount = it.dispatchedUpdateCount + 1,
                    lastStatusMessage = if (parsed.isDestinationArrived) {
                        "Destination Reached (0x08) • Arrived"
                    } else {
                        "Dispatched ${parsed.direction.title} (${parsed.direction.hexLabel}) • ${parsed.distanceMeters}m • ETA ${parsed.etaHours}h ${parsed.etaMinutes}m" +
                            if (parsed.streetOrLocationName.isNotBlank()) " • Street: ${parsed.streetOrLocationName}" else ""
                    },
                    lastUpdatedAtMs = nowMs
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
                    etaMinutes = parsed.etaMinutes,
                    streetOrLocationName = parsed.streetOrLocationName
                )
            }
        }

        internal fun handleNavigationStopped(reason: String = "Navigation stopped — active route cleared") {
            lastDispatchedKey.set(null)
            lastDispatchedAtMs.set(0L)
            _listenerState.update {
                it.copy(
                    isActiveNavigationOngoing = false,
                    lastParsedNavigation = null,
                    lastTurnCommand = null,
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
         * 1. Direction ([TurnTypeConstant]) and unified [TurnCommand] (via [ManeuverExtractor])
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
            largeIconBitmap: Bitmap? = null,
            fallbackPreviousDirection: TurnTypeConstant? = null
        ): ParsedGoogleMapsNavigation? {
            val cleanTitle = title.replace('\u00A0', ' ').trim()
            val cleanText = text.replace('\u00A0', ' ').trim()
            val cleanSubText = subText.replace('\u00A0', ' ').trim()
            val cleanTicker = tickerText.replace('\u00A0', ' ').trim()

            if (cleanTitle.isEmpty() && cleanText.isEmpty() && cleanSubText.isEmpty() && cleanTicker.isEmpty() && iconResourceName.isNullOrEmpty() && largeIconBitmap == null) {
                return null
            }

            val combinedLower = "$cleanTitle | $cleanText | $cleanSubText | $cleanTicker".lowercase(Locale.US)
            if (isNonNavigationOrTransientAlert(combinedLower)) {
                return null
            }

            val extractedDistance = parseDistanceToTurnMeters(cleanTitle, cleanText.ifBlank { cleanTicker })
            val isArrival = isDestinationArrivalText(cleanTitle, cleanText, cleanSubText, extractedDistance) ||
                (iconResourceName != null && parseDirectionFromIconResourceName(iconResourceName) == TurnTypeConstant.DESTINATION_REACHED)

            val candidateExtraStrings = buildList {
                if (cleanTicker.isNotBlank()) add(cleanTicker)
                addAll(extraStrings)
            }

            if (isArrival) {
                val arrivalCommand = ManeuverExtractor.extract(
                    title = cleanTitle,
                    text = if (cleanText.contains("arrived", ignoreCase = true) || cleanText.contains("destination", ignoreCase = true)) cleanText else "You have arrived",
                    subText = cleanSubText,
                    largeIconBitmap = largeIconBitmap,
                    extraStrings = candidateExtraStrings,
                    iconResourceName = iconResourceName,
                    fallbackTurnType = TurnTypeConstant.DESTINATION_REACHED
                )
                return ParsedGoogleMapsNavigation(
                    direction = TurnTypeConstant.DESTINATION_REACHED,
                    distanceMeters = 0,
                    etaHours = 0,
                    etaMinutes = 0,
                    isDestinationArrived = true,
                    rawTitle = cleanTitle,
                    rawText = cleanText,
                    rawSubText = cleanSubText,
                    turnCommand = arrivalCommand
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

            // 3. Fallback to icon shape analysis (24x24 pixel-density + normalized topology)
            val stage2BitmapCommand = largeIconBitmap?.let {
                ManeuverExtractor.evaluate24x24IconFallback(
                    bitmap = it,
                    distanceRemaining = cleanTitle,
                    rawInstruction = cleanText
                )
            }
            val shapeDirection = iconShapeDirection ?: stage2BitmapCommand?.toTurnTypeConstant()

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

            val extractedStreet = extractTargetStreetOrLocation(
                title = cleanTitle,
                text = cleanText.ifBlank { cleanTicker },
                subText = cleanSubText,
                bigText = "",
                tickerText = cleanTicker,
                extraStrings = extraStrings
            )

            val turnCommand = ManeuverExtractor.extract(
                title = cleanTitle,
                text = cleanText,
                subText = cleanSubText,
                largeIconBitmap = largeIconBitmap,
                extraStrings = candidateExtraStrings,
                iconResourceName = iconResourceName,
                fallbackTurnType = resolvedDirection
            )

            return ParsedGoogleMapsNavigation(
                direction = resolvedDirection,
                distanceMeters = resolvedDistanceMeters,
                etaHours = etaHours,
                etaMinutes = etaMinutes,
                isDestinationArrived = resolvedDirection == TurnTypeConstant.DESTINATION_REACHED,
                rawTitle = cleanTitle,
                rawText = cleanText,
                rawSubText = cleanSubText,
                streetOrLocationName = extractedStreet,
                turnCommand = turnCommand
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

            // 0. Explicit Unicode Directional Arrows & Sub-Cues (e.g. Google Maps sub-cue "Then ↱", "↱", "↰")
            if (lower.contains("then ↱") || lower.contains("then right") || lower.contains("then turn right") || lower.contains("↱") || lower.contains("->") || lower.contains("→")) {
                return TurnTypeConstant.TURN_RIGHT
            }
            if (lower.contains("then ↰") || lower.contains("then left") || lower.contains("then turn left") || lower.contains("↰") || lower.contains("<-") || lower.contains("←")) {
                return TurnTypeConstant.TURN_LEFT
            }
            if (lower.contains("↗") || lower.contains("bear right")) {
                return TurnTypeConstant.TURN_SLIGHTLY_RIGHT
            }
            if (lower.contains("↖") || lower.contains("bear left")) {
                return TurnTypeConstant.TURN_SLIGHTLY_LEFT
            }
            if (lower.contains("↘")) {
                return TurnTypeConstant.TURN_SHARPLY_RIGHT
            }
            if (lower.contains("↙")) {
                return TurnTypeConstant.TURN_SHARPLY_LEFT
            }
            if (lower.contains("↷")) {
                return TurnTypeConstant.RIGHT_U_TURN
            }
            if (lower.contains("↶")) {
                return TurnTypeConstant.U_TURN
            }
            if (lower.contains("↑")) {
                return TurnTypeConstant.CONTINUE_STRAIGHT
            }

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

            // 3. U-Turns (Right vs Left/Standard) with strict word boundaries to prevent accidental matches
            if (lower.contains("right u-turn") || lower.contains("u-turn right") || lower.contains("right u turn")) {
                return TurnTypeConstant.RIGHT_U_TURN
            }
            if (Regex("""\b(?:u-turn|u\s+turn|uturn)\b""").containsMatchIn(lower)) {
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
            if (lower.contains("left at the end") || lower.contains("take left at end") || lower.contains("take left") || lower.contains("take the left")) {
                return TurnTypeConstant.TURN_LEFT
            }
            if (lower.contains("right at the end") || lower.contains("take right at end") || lower.contains("take right") || lower.contains("take the right")) {
                return TurnTypeConstant.TURN_RIGHT
            }

            // 9. Standard Turn Left / Turn Right
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
         * Extracts the target street name or destination locality from Google Maps navigation text
         * (e.g. "towards Police Nagar", "CRPF Rd", "onto Central Bazar Road", "Kachipura Chowk", "Wardha Rd").
         *
         * Abbreviates road descriptors (Road -> Rd, Street -> St, Avenue -> Ave) to ensure the result
         * cleanly fits within the TVS iQube cluster's 17-character ASCII location banner (0x5B 0x5C).
         */
        fun extractTargetStreetOrLocation(
            title: String,
            text: String,
            subText: String,
            bigText: String = "",
            tickerText: String = "",
            extraStrings: List<String> = emptyList()
        ): String {
            val candidates = mutableListOf<String>()
            fun addClean(s: String) {
                s.split('\n', '·', '|', '/').forEach { part ->
                    val clean = part.replace('\u00A0', ' ').trim()
                    if (clean.isNotBlank() && !isIgnoredUiLabel(clean) && !candidates.contains(clean)) {
                        candidates.add(clean)
                    }
                }
            }

            addClean(subText)
            addClean(text)
            addClean(bigText)
            addClean(tickerText)
            addClean(title)
            extraStrings.forEach { addClean(it) }

            // Filter out distances, ETAs, clock times, system notices, and pure maneuver words
            val filtered = candidates.filter { s ->
                val lower = s.lowercase(Locale.US)
                !DISTANCE_TOKEN_REGEX.matches(s) &&
                    !CLOCK_TIME_REGEX.matches(s) &&
                    !isIgnoredUiLabel(s) &&
                    !lower.contains("min") &&
                    !lower.contains("hr") &&
                    !lower.contains("cyclonic") &&
                    !lower.contains("traffic") &&
                    !lower.contains("fastest") &&
                    !lower.contains("dismiss") &&
                    !lower.contains("exit navigation") &&
                    !lower.contains("rerouting") &&
                    !lower.contains("searching for") &&
                    !lower.startsWith("then ↱") &&
                    !lower.startsWith("then ↰") &&
                    s.length >= 2
            }

            var bestStreetName: String? = null
            var bestLocalityName: String? = null

            val roadKeywordRegex = Regex("""(?i)\b(?:road|rd|marg|street|st|chowk|square|sq|nagar|flyover|bypass|lane|ln|ave|avenue|blvd|highway|hwy|expwy|circle|path|way)\b""")
            val prepositionRegex = Regex("""(?i)\b(?:towards|toward|onto|on|into|along|to)\s+([A-Za-z0-9\s.'-]+)""")

            for (cand in filtered) {
                val prepMatch = prepositionRegex.find(cand)
                val target = prepMatch?.groupValues?.get(1)?.trim() ?: cand.trim()

                val cleanedTarget = target
                    .replace(Regex("""(?i)^in\s+\d+.*?(?:turn\s+(?:left|right|sharp\s+right|slight\s+right|slight\s+left|sharp\s+left)|continue|head)\s+(?:onto|towards|toward|on|into)\s+"""), "")
                    .replace(Regex("""(?i)^(?:turn\s+(?:left|right|sharp\s+right|slight\s+right|slight\s+left|sharp\s+left)|make\s+a\s+(?:left|right)|take\s+(?:left|right)|continue|head)\s+(?:onto|towards|toward|on|into)\s+"""), "")
                    .replace(DISTANCE_TOKEN_REGEX, "")
                    .trim()

                if (cleanedTarget.isBlank() || isIgnoredUiLabel(cleanedTarget)) continue

                val lowerTarget = cleanedTarget.lowercase(Locale.US)
                if (lowerTarget == "turn right" || lowerTarget == "turn left" || lowerTarget == "continue" || lowerTarget == "destination") {
                    continue
                }

                if (roadKeywordRegex.containsMatchIn(cleanedTarget)) {
                    if (bestStreetName == null || (!bestStreetName.contains(Regex("""(?i)\b(?:rd|road)\b""")) && cleanedTarget.contains(Regex("""(?i)\b(?:rd|road)\b""")))) {
                        bestStreetName = cleanedTarget
                    }
                } else if (bestLocalityName == null && cleanedTarget.length in 3..25) {
                    bestLocalityName = cleanedTarget
                }
            }

            val rawSelection = bestStreetName ?: bestLocalityName ?: ""
            if (rawSelection.isBlank()) return ""

            val abbreviated = rawSelection
                .replace(Regex("""(?i)\broad\b"""), "Rd")
                .replace(Regex("""(?i)\bstreet\b"""), "St")
                .replace(Regex("""(?i)\bavenue\b"""), "Ave")
                .replace(Regex("""(?i)\bboulevard\b"""), "Blvd")
                .replace(Regex("""(?i)\blane\b"""), "Ln")
                .replace(Regex("""(?i)\bhighway\b"""), "Hwy")
                .replace(Regex("""(?i)\bexpressway\b"""), "Expwy")
                .replace(Regex("""(?i)\bsquare\b"""), "Sq")
                .replace(Regex("""(?i)^(?:towards|toward|onto|on|into)\s+"""), "")
                .replace(Regex("""[^A-Za-z0-9\s.'-]"""), "")
                .trim()

            return abbreviated.take(17)
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
