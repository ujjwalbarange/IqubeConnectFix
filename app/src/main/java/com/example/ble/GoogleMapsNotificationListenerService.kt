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
        const val COUNTDOWN_THROTTLE_MS = 1_000L

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

        // Absolute clock time regex (e.g. "10:45 PM", "9:30 am", "14:20", "20:19 ETA", "14:44 ETA", "ETA 10:45", "2019 ETA")
        private val CLOCK_TIME_REGEX = Regex(
            pattern = """(?i)\b(?:(?:eta|arrive|arrival|by)\s*)?\d{1,2}:\d{2}(?:\s*(?:am|pm|a\.m\.|p\.m\.))?(?:\s*eta)?\b|\b\d{3,4}\s*eta\b"""
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
            "steps",
            "now"
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
                // Only inspect explicit large icon extras for maneuver graphics (never EXTRA_SMALL_ICON or action icons)
                listOf(Notification.EXTRA_LARGE_ICON_BIG, Notification.EXTRA_LARGE_ICON).forEach { largeKey ->
                    runCatching {
                        @Suppress("DEPRECATION")
                        when (val v = extras.get(largeKey)) {
                            is Icon -> if (!candidateIcons.contains(v)) candidateIcons.add(v)
                            is Bitmap -> if (!candidateBitmaps.contains(v)) candidateBitmaps.add(v)
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

                // Extract TalkBack contentDescriptions and maneuver Icons/Bitmaps from custom RemoteViews
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
                    if (candidateIcons.isEmpty() && candidateBitmaps.isEmpty()) {
                        val (rvIcons, rvBitmaps) = extractIconsAndBitmapsFromRemoteViews(rv, context)
                        for (ic in rvIcons) {
                            if (!candidateIcons.contains(ic)) candidateIcons.add(ic)
                        }
                        for (bmp in rvBitmaps) {
                            if (!candidateBitmaps.contains(bmp)) candidateBitmaps.add(bmp)
                        }
                    }
                }
            }

            var iconShapeDir: TurnTypeConstant? = null
            var primaryLargeBitmap: Bitmap? = candidateBitmaps.firstOrNull()

            if (context != null) {
                // Rasterize candidateIcons to Bitmap and classify by pixel distribution (never rely on static Android resource IDs/names)
                for (icon in candidateIcons) {
                    val bmp = rasterizeIconToBitmap(context, icon)
                    if (bmp != null) {
                        if (primaryLargeBitmap == null) primaryLargeBitmap = bmp
                        val dir = classifyManeuverBitmap(bmp)
                        if (dir != null) {
                            iconShapeDir = dir
                            break
                        }
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
                iconResourceName = null, // Do not use static Android resource IDs/names on live notifications
                iconShapeDirection = iconShapeDir,
                largeIconBitmap = primaryLargeBitmap
            )
        }

        internal fun rasterizeIconToBitmap(context: Context, icon: Icon?): Bitmap? {
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
                bitmap
            }.getOrNull()
        }

        internal fun extractStringsFromRemoteViews(
            rv: android.widget.RemoteViews?,
            context: Context? = null
        ): List<String> {
            if (rv == null) return emptyList()
            val contentDescriptions = mutableListOf<String>()
            val subCueStrings = mutableListOf<String>()

            // 1. Try inflating the RemoteViews to inspect View contentDescriptions (TalkBack maneuver labels)
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
                            if (t.contains("Then ", ignoreCase = true) || t.contains("↱") || t.contains("↰")) {
                                if (!subCueStrings.contains(t)) subCueStrings.add(t)
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

            // 2. Walk mActions and ALL superclasses for setContentDescription or explicit "Then ↱/↰" sub-cues
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
                        } else if (s.contains("Then ", ignoreCase = true) || s.contains("↱") || s.contains("↰")) {
                            if (!subCueStrings.contains(s)) subCueStrings.add(s)
                        }
                    }
                }
            }

            return (contentDescriptions + subCueStrings).distinct()
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

                // 1. Identify foreground maneuver arrow pixels
                // Also track semi-transparent pixels (alpha > 45) for roundabout rings and fork branches
                var whiteCount = 0
                var opaqueCount = 0
                var cornerOpaqueCount = 0
                for (y in 0 until size) {
                    for (x in 0 until size) {
                        val px = bitmap.getPixel(x, y)
                        val a = (px ushr 24) and 0xFF
                        if ((x < 4 || x >= size - 4) && (y < 4 || y >= size - 4) && a > 50) {
                            cornerOpaqueCount++
                        }
                        if (a > 60) {
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

                val hasTransparentBackground = cornerOpaqueCount <= 4
                val useWhiteMask = whiteCount in 14..(size * size * 3 / 4)
                val fg = Array(size) { BooleanArray(size) }
                val fullFg = Array(size) { BooleanArray(size) }
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
                            a > 60
                        }
                        val isFullFg = if (hasTransparentBackground) {
                            a > 45 && (r + g + b) > 120
                        } else {
                            isFg
                        }
                        if (isFullFg) {
                            fullFg[y][x] = true
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
                if (fgCount < 14 || bw < 6 || bh < 6) return TurnTypeConstant.CONTINUE_STRAIGHT

                // 2. Compute normalized bounding-box statistics (nx, ny in [0.0, 1.0])
                var topSumX = 0f
                var topCount = 0
                var veryTopSumX = 0f
                var veryTopCount = 0
                var midSumX = 0f
                var midCount = 0
                var bottomSumX = 0f
                var bottomCount = 0
                var stemBottomSumX = 0f
                var stemBottomCount = 0

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
                        if (ny in 0.35f..0.68f) {
                            midSumX += nx
                            midCount++
                        }
                        if (ny >= 0.54f) {
                            bottomSumX += nx
                            bottomCount++
                            if (nx < 0.50f) bottomLeft++ else bottomRight++
                        }
                        if (ny >= 0.72f) {
                            stemBottomSumX += nx
                            stemBottomCount++
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

                if (topCount == 0 || bottomCount == 0) return TurnTypeConstant.CONTINUE_STRAIGHT

                val topMeanX = topSumX / topCount
                val veryTopMeanX = if (veryTopCount > 0) veryTopSumX / veryTopCount else topMeanX
                val midMeanX = if (midCount > 0) midSumX / midCount else topMeanX
                val bottomMeanX = bottomSumX / bottomCount
                val stemBottomMeanX = if (stemBottomCount > 0) stemBottomSumX / stemBottomCount else bottomMeanX
                val leftEdgeMeanY = if (leftEdgeCount > 0) leftEdgeSumY / leftEdgeCount else 0.5f
                val rightEdgeMeanY = if (rightEdgeCount > 0) rightEdgeSumY / rightEdgeCount else 0.5f
                val topCapDensity = if (topCapTotal > 0) topCapHits.toFloat() / topCapTotal else 0f
                val centerCoreDensity = if (centerCoreTotal > 0) centerCoreHits.toFloat() / centerCoreTotal else 0f

                // 3. Count separated horizontal foreground runs per scanline
                val minGapPx = (bw / 11).coerceAtLeast(2)
                fun countRunsInRow(mask: Array<BooleanArray>, y: Int, xStart: Int = minX, xEnd: Int = maxX): Int {
                    var runs = 0
                    var inRun = false
                    var gap = 0
                    for (x in xStart..xEnd) {
                        if (mask[y][x]) {
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
                var circleMidRows = 0
                var circleMidTwoRunRows = 0
                var rightLoopRows = 0
                var rightLoopTwoRunRows = 0
                var leftLoopRows = 0
                var leftLoopTwoRunRows = 0
                var bottomRows = 0
                var bottomTwoRunRows = 0
                var veryBottomRows = 0
                var veryBottomTwoRunRows = 0

                val rightLoopStartX = (minX + (bw * 0.48f).toInt()).coerceIn(0, size - 1)
                val leftLoopEndX = (maxX - (bw * 0.48f).toInt()).coerceIn(0, size - 1)

                for (y in minY..maxY) {
                    val ny = (y - minY).toFloat() / (bh - 1).coerceAtLeast(1)
                    val runs = countRunsInRow(fg, y)
                    val fullRuns = countRunsInRow(fullFg, y, 0, size - 1)
                    if (ny in 0.20f..0.54f) {
                        midRows++
                        if (runs >= 2) midTwoRunRows++
                    }
                    if (ny in 0.34f..0.72f) {
                        circleMidRows++
                        if (fullRuns >= 2 || runs >= 2) circleMidTwoRunRows++
                    }
                    if (ny in 0.22f..0.68f) {
                        rightLoopRows++
                        if (countRunsInRow(fullFg, y, rightLoopStartX, size - 1) >= 2) {
                            rightLoopTwoRunRows++
                        }
                        leftLoopRows++
                        if (countRunsInRow(fullFg, y, 0, leftLoopEndX) >= 2) {
                            leftLoopTwoRunRows++
                        }
                    }
                    if (ny in 0.54f..0.86f) {
                        bottomRows++
                        if (runs >= 2) bottomTwoRunRows++
                    }
                    if (ny in 0.78f..1.00f) {
                        veryBottomRows++
                        if (runs >= 2) veryBottomTwoRunRows++
                    }
                }

                val midTwoRunRatio = if (midRows > 0) midTwoRunRows.toFloat() / midRows else 0f
                val circleMidTwoRunRatio = if (circleMidRows > 0) circleMidTwoRunRows.toFloat() / circleMidRows else 0f
                val rightLoopTwoRunRatio = if (rightLoopRows > 0) rightLoopTwoRunRows.toFloat() / rightLoopRows else 0f
                val leftLoopTwoRunRatio = if (leftLoopRows > 0) leftLoopTwoRunRows.toFloat() / leftLoopRows else 0f
                val bottomTwoRunRatio = if (bottomRows > 0) bottomTwoRunRows.toFloat() / bottomRows else 0f
                val veryBottomTwoRunRatio = if (veryBottomRows > 0) veryBottomTwoRunRows.toFloat() / veryBottomRows else 0f

                val bottomHasTwoSides = bottomLeft >= (bottomCount * 0.16f) && bottomRight >= (bottomCount * 0.16f)
                val hasUpperHollowLoop = centerCoreDensity < 0.26f && midTwoRunRatio >= 0.22f && topCapDensity >= 0.14f

                // Rule 1: U-Turn (`↶`/`↷`) & Roundabout U-Turn (Screenshot_20261029-201804 & 001507:
                // top arch/loop + two parallel vertical legs in the middle and lower sections).
                // Always map to U_TURN (never misclassify a U-turn as ROUNDABOUT_LEFT / Exit 5!).
                if (hasUpperHollowLoop && bottomTwoRunRatio >= 0.16f && bottomHasTwoSides) {
                    return TurnTypeConstant.U_TURN
                }
                if (centerCoreDensity < 0.28f && topCapDensity >= 0.14f && midTwoRunRatio >= 0.28f && bottomTwoRunRatio >= 0.18f) {
                    return TurnTypeConstant.U_TURN
                }

                // Rule 2: Roundabout Straight (Screenshot_20261029-201708: single bottom entry stem + middle roundabout loop + top straight-up arrow)
                // The top arrow tip and bottom entry stem are vertically aligned (`abs(veryTopMeanX - stemBottomMeanX) <= 0.15f`),
                // while the middle bulges around the roundabout island (`abs(midMeanX - stemBottomMeanX) >= 0.10f` or `circleMidTwoRunRatio >= 0.26f`).
                val isSingleBottomStem = veryBottomTwoRunRatio < 0.16f
                if (
                    isSingleBottomStem &&
                    kotlin.math.abs(veryTopMeanX - stemBottomMeanX) <= 0.15f &&
                    (kotlin.math.abs(midMeanX - stemBottomMeanX) >= 0.10f || (circleMidTwoRunRatio >= 0.26f && centerCoreDensity < 0.35f))
                ) {
                    return TurnTypeConstant.ROUNDABOUT
                }

                // Rule 3: Sharp Right (`↘`) & Sharp Left (`↙`)
                // Require the bottom vertical stem AND the top apex to both be on the opposite side of the downward diagonal blade,
                // so a 90° Right Turn (`↱`, where stemBottomMeanX < 0.45f) can NEVER be misclassified as Sharp Left (`↙`)!
                if (bottomTwoRunRatio >= 0.22f && bottomHasTwoSides && !hasUpperHollowLoop) {
                    if (
                        stemBottomMeanX < 0.40f &&
                        veryTopMeanX < 0.42f &&
                        rightEdgeCount > leftEdgeCount &&
                        rightEdgeMeanY >= 0.56f
                    ) {
                        return TurnTypeConstant.TURN_SHARPLY_RIGHT
                    }
                    if (
                        stemBottomMeanX > 0.60f &&
                        veryTopMeanX > 0.58f &&
                        leftEdgeCount > rightEdgeCount &&
                        leftEdgeMeanY >= 0.56f
                    ) {
                        return TurnTypeConstant.TURN_SHARPLY_LEFT
                    }
                }

                // Rule 4: 90° Turn Right (`↱`), Roundabout Right, & Slight Right (`↗`)
                // Active pixels in the upper arm/arrowhead shift RIGHT relative to the bottom vertical stem (`dx > 0.08f`).
                val dx = topMeanX - stemBottomMeanX
                val veryTopDx = veryTopMeanX - stemBottomMeanX
                val isStemLeftTipRight = dx > 0.08f && veryTopDx > 0.05f && stemBottomMeanX < 0.52f && rightEdgeCount > 0 && rightEdgeMeanY < 0.62f
                val isUpperHeavyRight = topRight > topLeft * 1.22f && topMeanX > 0.53f && stemBottomMeanX <= 0.54f && veryTopDx > 0.04f

                if (isStemLeftTipRight || isUpperHeavyRight) {
                    if (isSingleBottomStem && stemBottomMeanX > 0.20f && leftLoopTwoRunRatio >= 0.24f) {
                        return TurnTypeConstant.ROUNDABOUT
                    }
                    return if (veryTopMeanX > 0.66f && rightEdgeMeanY < 0.18f && stemBottomMeanX < 0.38f) {
                        TurnTypeConstant.TURN_SLIGHTLY_RIGHT
                    } else {
                        TurnTypeConstant.TURN_RIGHT
                    }
                }

                // Rule 5: 90° Turn Left (`↰`), Roundabout Left (Screenshot_20261029-201832), & Slight Left (`↖`)
                // Active pixels in the upper arm/arrowhead shift LEFT relative to the bottom vertical stem (`dx < -0.08f`).
                val isStemRightTipLeft = dx < -0.08f && veryTopDx < -0.05f && stemBottomMeanX > 0.48f && leftEdgeCount > 0 && leftEdgeMeanY < 0.62f
                val isUpperHeavyLeft = topLeft > topRight * 1.22f && topMeanX < 0.47f && stemBottomMeanX >= 0.46f && veryTopDx < -0.04f

                if (isStemRightTipLeft || isUpperHeavyLeft) {
                    if (isSingleBottomStem && stemBottomMeanX < 0.80f && rightLoopTwoRunRatio >= 0.24f) {
                        return TurnTypeConstant.ROUNDABOUT_LEFT
                    }
                    return if (veryTopMeanX < 0.34f && leftEdgeMeanY < 0.18f && stemBottomMeanX > 0.62f) {
                        TurnTypeConstant.TURN_SLIGHTLY_LEFT
                    } else {
                        TurnTypeConstant.TURN_LEFT
                    }
                }

                // Rule 6: Roundabout with single bottom entry stem and hollow upper circle
                if (hasUpperHollowLoop && isSingleBottomStem) {
                    return if (dx < -0.10f && veryTopDx < -0.08f) TurnTypeConstant.ROUNDABOUT_LEFT else TurnTypeConstant.ROUNDABOUT
                }

                // Rule 7: Safe Fallback -> Continue Straight (`↑`)
                TurnTypeConstant.CONTINUE_STRAIGHT
            }.getOrDefault(TurnTypeConstant.CONTINUE_STRAIGHT)
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

            // High-priority immediate check vs routine distance countdown tick throttling (1 second):
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

            // Direction Extraction with Text-First priority:
            // 1. Text-First Detection: Check EXTRA_TITLE, EXTRA_TEXT, and tickerText (plus TalkBack extraStrings)
            //    for explicit navigation keywords first (never let subText ETA/distance summaries trigger false turns).
            val textCandidates = buildList {
                if (cleanTitle.isNotBlank()) add(cleanTitle)
                if (cleanText.isNotBlank()) add(cleanText)
                if (cleanTicker.isNotBlank()) add(cleanTicker)
                addAll(extraStrings)
                if (cleanSubText.isNotBlank() && !isTripSummaryOrTimestampSegment(cleanSubText)) {
                    add(cleanSubText)
                }
            }
            val textDirection = parseTurnDirectionFromCandidates(textCandidates)

            // 2. Reliable Icon Analysis (pixel distribution on EXTRA_LARGE_ICON / bitmap)
            val stage2BitmapCommand = largeIconBitmap?.let {
                ManeuverExtractor.evaluate24x24IconFallback(
                    bitmap = it,
                    distanceRemaining = cleanTitle,
                    rawInstruction = cleanText
                )
            }
            val shapeDirection = iconShapeDirection ?: stage2BitmapCommand?.toTurnTypeConstant()

            // 3. Optional test-only iconResourceName fallback (never populated on live notifications)
            val iconDirection = parseDirectionFromIconResourceName(iconResourceName)

            val etaPair = parseEtaDurationHoursAndMinutes(cleanSubText, cleanText, cleanTitle)

            // Reject generic background notifications that lack both a navigation category and turn/distance/ETA cues
            val hasNavCategory = category == Notification.CATEGORY_NAVIGATION
            if (textDirection == null && iconDirection == null && shapeDirection == null && extractedDistance == null) {
                return null
            }
            if (!hasNavCategory && textDirection == null && iconDirection == null && etaPair == null) {
                return null
            }

            // Priority: Explicit text direction -> Icon pixel shape -> Test icon resource -> Latch previous direction (when no icon bitmap) -> CONTINUE_STRAIGHT
            val resolvedDirection = textDirection
                ?: shapeDirection
                ?: iconDirection
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
            val candidates = buildList {
                if (title.isNotBlank()) add(title.trim())
                if (text.isNotBlank()) add(text.trim())
                if (title.isNotBlank() && text.isNotBlank()) add("${title.trim()} ${text.trim()}")
                if (subText.isNotBlank() && !isTripSummaryOrTimestampSegment(subText)) add(subText.trim())
            }
            return parseTurnDirectionFromCandidates(candidates)
        }

        internal fun parseTurnDirectionFromCandidates(candidates: List<String>): TurnTypeConstant? {
            for (rawCandidate in candidates) {
                if (rawCandidate.isBlank()) continue
                // Strip street suffix after "onto / toward / towards" to isolate the maneuver verb first
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
            val lower = input.lowercase(Locale.US).trim()

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
                lower.contains("right on ") ||
                lower.contains("right toward") ||
                lower.contains("right towards") ||
                lower.contains("right at") ||
                lower.contains("right turn") ||
                lower.contains("make a right") ||
                lower.contains("next right") ||
                lower == "right"

            val hasExplicitTurnLeft = lower.contains("turn left") ||
                lower.contains("left onto") ||
                lower.contains("left on ") ||
                lower.contains("left toward") ||
                lower.contains("left towards") ||
                lower.contains("left at") ||
                lower.contains("left turn") ||
                lower.contains("make a left") ||
                lower.contains("next left") ||
                lower == "left"

            if (hasExplicitTurnRight && !hasExplicitTurnLeft) return TurnTypeConstant.TURN_RIGHT
            if (hasExplicitTurnLeft && !hasExplicitTurnRight) return TurnTypeConstant.TURN_LEFT

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

        // Regex helpers for filtering out ETA timestamps ("20:19", "14:44 ETA", "2019 ETA"), durations ("2 min", "1 hr"), and distances ("60 m", "1.2 km")
        private val TIME_OR_ETA_TOKEN_REGEX = Regex(
            pattern = """(?i)(?:\b\d{1,2}:\d{2}(?:\s*(?:am|pm|a\.m\.|p\.m\.))?(?:\.\.\.)?(?:\s*eta)?\b|\b\d{3,4}\s*eta\b|\beta\b)"""
        )
        private val DURATION_OR_TELEMETRY_KEYWORD_REGEX = Regex(
            pattern = """(?i)\b(?:\d+\s*(?:days?|d|hours?|hrs?|hr|h|minutes?|mins?|min)|mins?|hrs?|eta|now)\b"""
        )
        private val PURE_DISTANCE_SEGMENT_REGEX = Regex(
            pattern = """(?i)^\s*\d+(?:[.,]\d+)?\s*(?:kilometers?|kilometres?|km|meters?|metres?|m|miles?|mi|yards?|yds?|yd|feet|foot|ft)\s*$"""
        )

        internal fun isTripSummaryOrTimestampSegment(segment: String): Boolean {
            val s = segment.replace('\u00A0', ' ').trim()
            if (s.isBlank()) return true
            val lower = s.lowercase(Locale.US)
            if (lower == "now" || lower == "m..." || lower == "maps" || lower == "google maps") return true
            if (PURE_DISTANCE_SEGMENT_REGEX.matches(s)) return true
            if (TIME_OR_ETA_TOKEN_REGEX.containsMatchIn(s)) return true
            if (DURATION_OR_TELEMETRY_KEYWORD_REGEX.containsMatchIn(s)) return true
            return false
        }

        /**
         * Cleans a potential street/road candidate by removing time formats (`HH:mm`, `\b\d{1,2}:\d{2}\b`),
         * collapsed ETA strings (`2019 ETA`, `1444 ETA`), telemetry keywords (`ETA`, `min`, `mins`, `hr`, `km`, `m`, `now`),
         * and leading maneuver instructions.
         */
        fun cleanStreetCandidate(raw: String): String {
            var s = raw.replace('\u00A0', ' ').trim()
            if (s.isBlank()) return ""

            // Reject segments that are purely trip summaries, arrival timestamps ("20:19", "20:19 ETA", "14:44 ETA", "2019 ETA"), or distances ("60 m")
            if (isTripSummaryOrTimestampSegment(s)) return ""

            // Strip any embedded time formats (HH:mm) and telemetry keywords (ETA, min, mins, hr, km, m, now)
            s = s.replace(TIME_OR_ETA_TOKEN_REGEX, " ")
                .replace(DURATION_OR_TELEMETRY_KEYWORD_REGEX, " ")
                .replace(Regex("""(?i)\b\d+(?:[.,]\d+)?\s*(?:km|m|mi|yd|yds)\b"""), " ")
                .replace(Regex("""(?i)^in\s+\d+.*?(?:turn\s+(?:sharply\s+|sharp\s+|slightly\s+|slight\s+)?(?:left|right)|make\s+a\s+(?:u-turn|left|right)|continue(?:\s+straight)?|head\s+\w+|keep\s+(?:left|right)|stay\s+(?:left|right))\s+(?:at\s+.*?\s+)?(?:onto|towards|toward|on|into|to)\s+"""), "")
                .replace(Regex("""(?i)^(?:at\s+the\s+roundabout,\s*take\s+the\s+\d+(?:st|nd|rd|th)?\s+exit|at\s+the\s+fork,\s*(?:stay|keep)\s+(?:left|right)|turn\s+(?:sharply\s+|sharp\s+|slightly\s+|slight\s+)?(?:left|right)|make\s+a\s+(?:right\s+)?(?:u-turn|left|right)|take\s+(?:the\s+)?(?:exit\s+[A-Za-z0-9\-]+\s+)?(?:left|right)?|keep\s+(?:left|right)(?:\s+at\s+the\s+intersection)?|stay\s+(?:left|right)|bear\s+(?:left|right)|sharp\s+(?:left|right)|slight\s+(?:left|right)|continue(?:\s+straight)?|head\s+\w+|merge)\s*(?:onto|towards|toward|on|into|for|to)?\s*"""), "")
                .replace(Regex("""(?i)^(?:towards|toward|onto|into)\s+"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()

            return s
        }

        /**
         * Extracts the target street name or destination locality from Google Maps navigation text
         * (e.g. "towards Police Nagar", "CRPF Rd", "onto Central Bazar Road", "Kachipura Chowk", "Wardha Rd").
         *
         * Handles dynamic title/text field swapping by prioritizing `text` and `title` first, filtering out
         * arrival timestamps (`20:19`, `14:44`, `2019 ETA`, `1444 ETA`) and telemetry tokens (`ETA`, `min`, `mins`, `hr`, `km`, `m`, `now`),
         * and abbreviating road descriptors (Road -> Rd, Street -> St, Square -> Sq) to fit within the 17-character scooter display.
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
                s.split('\n', '·', '•', '|', '/').forEach { part ->
                    val clean = part.replace('\u00A0', ' ').trim()
                    if (clean.isNotBlank() && !isIgnoredUiLabel(clean) && !isTripSummaryOrTimestampSegment(clean) && !candidates.contains(clean)) {
                        candidates.add(clean)
                    }
                }
            }

            // Prioritize `text` and `title` first (since Google Maps swaps title/text when approaching a turn),
            // followed by `subText` (for 2-line street banners like "onto Central Bazar Road" / "CRPF Rd") and `tickerText`.
            addClean(text)
            addClean(title)
            addClean(bigText)
            addClean(subText)
            addClean(tickerText)
            extraStrings.forEach { addClean(it) }

            val filtered = candidates.filter { s ->
                val lower = s.lowercase(Locale.US)
                !isTripSummaryOrTimestampSegment(s) &&
                    !isIgnoredUiLabel(s) &&
                    !lower.contains("cyclonic") &&
                    !lower.contains("traffic") &&
                    !lower.contains("fastest") &&
                    !lower.contains("dismiss") &&
                    !lower.contains("exit navigation") &&
                    !lower.contains("rerouting") &&
                    !lower.contains("searching for") &&
                    !lower.startsWith("then ↱") &&
                    !lower.startsWith("then ↰") &&
                    !lower.startsWith("only ") &&
                    !lower.contains("reviews") &&
                    s.length >= 2
            }

            var bestStreetName: String? = null
            var bestLocalityName: String? = null

            val roadKeywordRegex = Regex("""(?i)\b(?:road|rd|marg|street|st|chowk|square|sq|nagar|flyover|bypass|lane|ln|ave|avenue|blvd|highway|hwy|expwy|circle|path|way|nh-\d+|sh-\d+|bridge|bazar|market|colony|layout)\b""")
            val prepositionOntoRegex = Regex("""(?i)\b(?:onto|towards|toward|into|along)\s+([A-Za-z0-9\s.'-]+)""")

            for (cand in filtered) {
                val ontoMatch = prepositionOntoRegex.find(cand)
                val rawTarget = ontoMatch?.groupValues?.get(1)?.trim() ?: cand.trim()
                val cleanedTarget = cleanStreetCandidate(rawTarget)

                if (cleanedTarget.isBlank() || isIgnoredUiLabel(cleanedTarget) || isTripSummaryOrTimestampSegment(cleanedTarget)) {
                    continue
                }

                // Must contain at least one 2+ letter alphabetic word (never purely numeric or "2019")
                if (!Regex("""[A-Za-z]{2,}""").containsMatchIn(cleanedTarget)) {
                    continue
                }

                val lowerTarget = cleanedTarget.lowercase(Locale.US)
                if (
                    lowerTarget in setOf(
                        "turn right", "turn left", "sharp right", "sharp left",
                        "slight right", "slight left", "keep right", "keep left",
                        "continue", "continue straight", "straight", "u-turn",
                        "make a u-turn", "destination", "you have arrived", "arrived"
                    )
                ) {
                    continue
                }

                if (roadKeywordRegex.containsMatchIn(cleanedTarget)) {
                    if (
                        bestStreetName == null ||
                        (!bestStreetName.contains(Regex("""(?i)\b(?:rd|road)\b""")) && cleanedTarget.contains(Regex("""(?i)\b(?:rd|road)\b""")) && cand == subText.trim())
                    ) {
                        bestStreetName = cleanedTarget
                    }
                } else if (bestLocalityName == null && cleanedTarget.length in 3..28) {
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
                .replace(Regex("""\s+"""), " ")
                .trim()

            return abbreviated.take(17).trim()
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
