package com.uberspot.pro

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.MotionEvent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.util.Locale
import java.util.regex.Pattern

class RideAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RideAccessibilityService? = null
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "kaptor_channel"
    }

    private lateinit var prefs: SharedPreferences
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var isOverlayAttached = false
    private var isCollapsed = false

    // Drag and drop tracking
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var resetRunnable: Runnable? = null

    // Track active offer to avoid redundant calculations
    private var currentOfferKey = ""
    private var lastEventTime = 0L
    private var lastFullScanTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = getSharedPreferences("KaptorPrefs", Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        updateStatusNotification(prefs.getBoolean("service_enabled", true))

        // Attach always-fixed dock immediately in idle/zero state
        mainHandler.post {
            ensureDockAttached()
            resetDockToIdle()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        removeOverlayView()
        removeNotification()
    }

    fun updateStatusNotification(isEnabled: Boolean) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Kaptor Monitoreo",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Copiloto táctico de Joan Lizarazo"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val title = if (isEnabled) "⚡ Kaptor Activo" else "⚪ Kaptor Pausado"
            val text = if (isEnabled) "Monitoreando ofertas de viaje (Algoritmo de Joan Lizarazo)" else "Asistente en reposo. Cero consumo de batería."

            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }.apply {
                setContentTitle(title)
                setContentText(text)
                setSmallIcon(android.R.drawable.ic_menu_compass)
                setOngoing(isEnabled)
            }.build()

            notificationManager.notify(NOTIFICATION_ID, notification)

            mainHandler.post {
                if (isEnabled) {
                    ensureDockAttached()
                    resetDockToIdle()
                } else {
                    removeOverlayView()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.getBoolean("service_enabled", true)) return

        val pkgName = (event.packageName ?: "").toString().lowercase()
        if (!pkgName.contains("uber") && !pkgName.contains("systemui")) return

        val now = System.currentTimeMillis()
        val isWindowState = (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)

        // Throttle content changes so we don't saturate CPU during map scrolling or GPS updates
        if (!isWindowState && (now - lastEventTime < 60)) {
            return
        }
        lastEventTime = now

        val rootNode = rootInActiveWindow ?: event.source ?: return

        var offerFound = false

        // PRIORITY 1: ULTRA-FAST ANCHOR NODE CONTAINER ISOLATION
        // Inside Uber, the offer is rendered in a CardView/Dialog with an accept button.
        // Searching for the button anchor directly isolates the card in < 1ms, completely bypassing the map!
        val anchorTerms = listOf("Me interesa", "me interesa", "Aceptar", "aceptar", "Contrato de renta", "(estimado)")
        var offerCardContainer: AccessibilityNodeInfo? = null

        for (term in anchorTerms) {
            val matchingNodes = rootNode.findAccessibilityNodeInfosByText(term)
            if (!matchingNodes.isNullOrEmpty()) {
                for (node in matchingNodes) {
                    var curr: AccessibilityNodeInfo? = node
                    for (level in 0..4) {
                        val parent = curr?.parent ?: break
                        // An offer card dialog/container typically has between 3 and 30 children
                        if (parent.childCount in 3..30) {
                            offerCardContainer = parent
                        }
                        curr = parent
                    }
                    if (offerCardContainer != null) break
                }
            }
            if (offerCardContainer != null) break
        }

        // If card container was isolated, collect texts ONLY from this clean card!
        // This completely eliminates all map text, surge pins ("1-2 min"), and banners!
        if (offerCardContainer != null) {
            val cardSb = StringBuilder()
            collectTextsRecursively(offerCardContainer, cardSb)
            val cardText = cardSb.toString()
            if (cardText.isNotBlank()) {
                offerFound = parseUberOfferAndEvaluate(cardText)
            }
        }

        // PRIORITY 2: SOURCE NODE INSPECTION
        if (!offerFound && event.source != null) {
            val srcSb = StringBuilder()
            collectTextsRecursively(event.source, srcSb)
            val srcText = srcSb.toString()
            if (hasUberOfferMarkers(srcText)) {
                offerFound = parseUberOfferAndEvaluate(srcText)
            }
        }

        // PRIORITY 3: FULL WINDOW SCAN WITH ROBUST SLICE FILTER
        if (!offerFound) {
            // Avoid scanning full window on every minor map frame unless 150ms has passed or window state changed
            if (isWindowState || (now - lastFullScanTime > 150)) {
                lastFullScanTime = now
                val fullSb = StringBuilder()
                collectTextsRecursively(rootNode, fullSb)
                val fullText = fullSb.toString()
                if (hasUberOfferMarkers(fullText)) {
                    parseUberOfferAndEvaluate(fullText)
                }
            }
        }
    }

    private fun hasUberOfferMarkers(text: String): Boolean {
        if (text.length < 15) return false
        val lower = text.lowercase()
        val hasCategoryOrButton = lower.contains("economy") ||
                lower.contains("uberx") ||
                lower.contains("comfort") ||
                lower.contains("flash") ||
                lower.contains("moto") ||
                lower.contains("prioridad") ||
                lower.contains("promo") ||
                lower.contains("vip") ||
                lower.contains("me interesa") ||
                lower.contains("aceptar") ||
                lower.contains("(estimado)") ||
                lower.contains("recogida") ||
                lower.contains("contrato de renta")

        val hasCurrencyOrDistance = (lower.contains("cop") || lower.contains("$") || lower.contains("km"))

        return hasCategoryOrButton && hasCurrencyOrDistance
    }

    private fun collectTextsRecursively(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) {
            sb.append(text).append("\n")
        }
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank() && desc != text) {
            sb.append(desc).append("\n")
        }
        val count = node.childCount
        for (i in 0 until count) {
            collectTextsRecursively(node.getChild(i), sb)
        }
    }

    private fun parseUberOfferAndEvaluate(rawText: String): Boolean {
        val cleanText = rawText.replace(Regex("[\\u00A0\\u202F\\u2000-\\u200B]"), " ")
        val rawLines = cleanText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (rawLines.isEmpty()) return false

        // STEP 1: Filter out map noise, surge pins, account banners, and street numbers
        val filteredLines = mutableListOf<String>()
        for (line in rawLines) {
            val lower = line.lowercase()
            // Ignore heat/surge map ranges like "1-2 min", "1-3 min", "1-4 min"
            if (lower.matches(Regex("^[0-9]+-[0-9]+\\s*min.*"))) continue

            // Ignore driver pass / wallet / status banners
            if (lower.contains("pase de ganancias") || lower.contains("vence a la") ||
                lower.contains("saldo") || lower.contains("billetera") ||
                lower.contains("buscando solicitud") || lower.contains("tu cuenta") ||
                lower.contains("ganancias de hoy")) continue

            // Ignore standalone street tags like "DG. 15", "AK. 27", "CRA. 33"
            if (lower.matches(Regex("^(dg|ak|cra|clle|cl|auto|av)\\.?\\s*[0-9]+$"))) continue

            filteredLines.add(line)
        }

        // STEP 2: Slicing - Locate exact offer boundaries from Category/Fare down to Action Button
        var startIdx = -1
        for (i in filteredLines.indices) {
            val l = filteredLines[i].lowercase()
            if (l.contains("economy") || l.contains("uberx") || l.contains("comfort") ||
                l.contains("flash") || l.contains("moto") || l.contains("prioridad") ||
                l.contains("promo") || l.contains("vip") || l.contains("envío") || l.contains("entrega")) {
                startIdx = i
                break
            }
            if ((l.contains("cop") || l.contains("$")) &&
                Pattern.compile("(?:COP|\\$)?\\s*([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,6})", Pattern.CASE_INSENSITIVE).matcher(filteredLines[i]).find()) {
                startIdx = i
                break
            }
        }

        var endIdx = filteredLines.size
        for (i in filteredLines.size - 1 downTo 0) {
            val l = filteredLines[i].lowercase()
            if (l.contains("me interesa") || l.contains("aceptar") || l.contains("confirmar") ||
                l.contains("toca para") || l.contains("contrato de renta")) {
                endIdx = i + 1
                break
            }
        }

        val targetLines = if (startIdx != -1 && startIdx < endIdx) {
            filteredLines.subList(startIdx, endIdx)
        } else {
            filteredLines
        }
        val targetText = targetLines.joinToString("\n")

        // STEP 3: FARE EXTRACTION
        var fare = 0
        for (line in targetLines) {
            val m = Pattern.compile("(?:COP|\\$)\\s*([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,6})", Pattern.CASE_INSENSITIVE).matcher(line)
            if (m.find()) {
                val numStr = m.group(1)?.replace(".", "")?.replace(",", "") ?: ""
                val cand = numStr.toIntOrNull() ?: 0
                if (cand in 4000..350000) {
                    fare = cand
                    break
                }
            }
        }

        if (fare == 0) {
            for (idx in 0 until targetLines.size - 1) {
                val curr = targetLines[idx]
                val nxt = targetLines[idx + 1].lowercase()
                if (nxt.contains("estimado") || nxt.contains("/km")) {
                    val m = Pattern.compile("\\b([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,6})\\b").matcher(curr)
                    if (m.find()) {
                        val numStr = m.group(1)?.replace(".", "")?.replace(",", "") ?: ""
                        val cand = numStr.toIntOrNull() ?: 0
                        if (cand in 4000..350000) {
                            fare = cand
                            break
                        }
                    }
                }
            }
        }

        if (fare < 4000) return false

        // STEP 4: DISTANCES & TIMES EXTRACTION
        var pickupKm = 0.0
        var totalKm = 0.0
        var totalMin = 0
        var legsFound = 0

        // Combined Pattern 1: "2.5 km (6 min)" or "2.5 km • 6 min"
        val p1 = Pattern.compile(
            "([0-9]+(?:[.,][0-9]+)?)\\s*(km|m)\\s*(?:[•·-]?\\s*)?\\(?\\s*([0-9]+)\\s*min(?:uto)?s?\\s*\\)?",
            Pattern.CASE_INSENSITIVE
        ).matcher(targetText)

        while (p1.find()) {
            legsFound++
            val dStr = p1.group(1)?.replace(",", ".") ?: "0"
            val d = dStr.toDoubleOrNull() ?: 0.0
            val unit = p1.group(2) ?: "km"
            val m = p1.group(3)?.toIntOrNull() ?: 0
            val legKm = if (unit.equals("m", ignoreCase = true)) d / 1000.0 else d

            if (legsFound == 1) {
                pickupKm = legKm
            }
            totalMin += m
            totalKm += legKm
        }

        // Pattern 2: "6 min (2.5 km)" or "6 min • 2.5 km"
        if (legsFound == 0) {
            val p2 = Pattern.compile(
                "([0-9]+)\\s*min(?:uto)?s?\\s*(?:[•·-]?\\s*)?\\(?\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(km|m)\\s*\\)?",
                Pattern.CASE_INSENSITIVE
            ).matcher(targetText)

            while (p2.find()) {
                legsFound++
                val m = p2.group(1)?.toIntOrNull() ?: 0
                val dStr = p2.group(2)?.replace(",", ".") ?: "0"
                val d = dStr.toDoubleOrNull() ?: 0.0
                val unit = p2.group(3) ?: "km"
                val legKm = if (unit.equals("m", ignoreCase = true)) d / 1000.0 else d

                if (legsFound == 1) {
                    pickupKm = legKm
                }
                totalMin += m
                totalKm += legKm
            }
        }

        // Pattern 3: Separated tokens inside the clean target lines
        if (legsFound == 0) {
            val kmList = mutableListOf<Double>()
            val minList = mutableListOf<Int>()

            for (line in targetLines) {
                val kmMatcher = Pattern.compile("\\b([0-9]+(?:[.,][0-9]+)?)\\s*km\\b", Pattern.CASE_INSENSITIVE).matcher(line)
                while (kmMatcher.find()) {
                    val d = kmMatcher.group(1)?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                    if (d in 0.1..80.0) {
                        kmList.add(d)
                    }
                }

                val minMatcher = Pattern.compile("\\b([0-9]{1,3})\\s*min(?:uto)?s?\\b", Pattern.CASE_INSENSITIVE).matcher(line)
                while (minMatcher.find()) {
                    val t = minMatcher.group(1)?.toIntOrNull() ?: 0
                    if (t in 1..180) {
                        minList.add(t)
                    }
                }
            }

            if (kmList.isNotEmpty() && minList.isNotEmpty()) {
                pickupKm = kmList[0]
                totalKm = kmList.sumOf { it }
                totalMin = minList.sumOf { it }
                legsFound = kmList.size
            }
        }

        if (totalKm <= 0.0) return false

        // Round totalKm to 1 decimal
        totalKm = Math.round(totalKm * 10.0) / 10.0
        pickupKm = Math.round(pickupKm * 10.0) / 10.0

        val offerKey = "$fare-$totalKm-$totalMin"
        if (offerKey == currentOfferKey) return true
        currentOfferKey = offerKey

        // STEP 5: CALCULATE AND UPDATE
        val minRateKm = prefs.getInt("min_rate_km", 1800)
        val minRateMin = prefs.getInt("min_rate_min", 400)
        val maxPickupKm = prefs.getFloat("max_pickup_km", 2.5f).toDouble()

        val perKm = (fare / totalKm).toInt()
        val perMin = if (totalMin > 0) (fare / totalMin) else 0

        val targetKmFare = (totalKm * minRateKm).toInt()
        val targetMinFare = (totalMin * minRateMin).toInt()
        val rawFairPrice = maxOf(targetKmFare, targetMinFare)
        val fairPrice = (Math.round(rawFairPrice / 100.0) * 100).toInt()
        val diffPrice = fare - fairPrice

        val isPickupTooFar = pickupKm > (maxPickupKm + 0.05)

        val verdict = when {
            isPickupTooFar -> "REJECT_FAR"
            fare >= fairPrice && perKm >= minRateKm -> "ACCEPT"
            perKm >= (minRateKm * 0.85) -> "REGULAR"
            else -> "REJECT"
        }

        mainHandler.post {
            updateDockWithOffer(
                totalKm = totalKm,
                totalMin = totalMin,
                pickupKm = pickupKm,
                perKm = perKm,
                perMin = perMin,
                fairPrice = fairPrice,
                diffPrice = diffPrice,
                verdict = verdict,
                isPickupTooFar = isPickupTooFar
            )
        }

        return true
    }

    private fun ensureDockAttached() {
        try {
            if (windowManager == null) {
                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }

            if (overlayView == null) {
                val inflater = LayoutInflater.from(this)
                overlayView = inflater.inflate(R.layout.overlay_bubble, null)

                val cardContainer = overlayView?.findViewById<View>(R.id.dockContainer)
                val bubbleContainer = overlayView?.findViewById<View>(R.id.bubbleContainer)
                val btnMinimize = overlayView?.findViewById<View>(R.id.btnMinimize)

                btnMinimize?.setOnClickListener {
                    toggleCollapse(true)
                }

                if (cardContainer != null) {
                    setupDragListener(cardContainer, isBubble = false)
                }
                if (bubbleContainer != null) {
                    setupDragListener(bubbleContainer, isBubble = true)
                }
            }

            if (!isOverlayAttached && overlayView != null) {
                val density = resources.displayMetrics.density
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels
                val cardWidthPx = (142 * density).toInt()

                val params = WindowManager.LayoutParams(
                    cardWidthPx,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    // Position at upper-right where user drew the green rectangle
                    x = (screenWidth - cardWidthPx - (8 * density).toInt()).coerceAtLeast(0)
                    y = (screenHeight * 0.32f).toInt()
                }
                overlayLayoutParams = params

                windowManager?.addView(overlayView, params)
                isOverlayAttached = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupDragListener(view: View, isBubble: Boolean) {
        view.setOnTouchListener { v, event ->
            val params = overlayLayoutParams ?: return@setOnTouchListener false
            val density = resources.displayMetrics.density
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels

            if (!isBubble) {
                val btnMin = overlayView?.findViewById<View>(R.id.btnMinimize)
                if (btnMin != null) {
                    val rect = Rect()
                    btnMin.getGlobalVisibleRect(rect)
                    if (rect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        if (event.action == MotionEvent.ACTION_UP) {
                            toggleCollapse(true)
                        }
                        return@setOnTouchListener true
                    }
                }
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > 10f) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val currentWidth = if (isBubble) (48 * density).toInt() else (142 * density).toInt()
                        params.x = (initialX + dx).toInt().coerceIn(0, (screenWidth - currentWidth).coerceAtLeast(0))
                        params.y = (initialY + dy).toInt().coerceIn(0, (screenHeight - 120).coerceAtLeast(0))
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        if (isBubble) {
                            toggleCollapse(false)
                        } else {
                            v.performClick()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun toggleCollapse(collapse: Boolean) {
        try {
            isCollapsed = collapse
            val cardContainer = overlayView?.findViewById<View>(R.id.dockContainer)
            val bubbleContainer = overlayView?.findViewById<View>(R.id.bubbleContainer)
            val params = overlayLayoutParams ?: return
            val density = resources.displayMetrics.density
            val screenWidth = resources.displayMetrics.widthPixels

            if (collapse) {
                cardContainer?.visibility = View.GONE
                bubbleContainer?.visibility = View.VISIBLE
                val bubbleSize = (48 * density).toInt()
                params.width = bubbleSize
                params.height = bubbleSize
                // Dock to right or left edge nicely
                if (params.x > screenWidth / 2) {
                    params.x = screenWidth - bubbleSize - (6 * density).toInt()
                } else {
                    params.x = (6 * density).toInt()
                }
            } else {
                bubbleContainer?.visibility = View.GONE
                cardContainer?.visibility = View.VISIBLE
                val cardWidth = (142 * density).toInt()
                params.width = cardWidth
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                if (params.x + cardWidth > screenWidth) {
                    params.x = (screenWidth - cardWidth - (6 * density).toInt()).coerceAtLeast(0)
                }
            }
            windowManager?.updateViewLayout(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateDockWithOffer(
        totalKm: Double,
        totalMin: Int,
        pickupKm: Double,
        perKm: Int,
        perMin: Int,
        fairPrice: Int,
        diffPrice: Int,
        verdict: String,
        isPickupTooFar: Boolean
    ) {
        ensureDockAttached()

        // Auto-expand if collapsed so driver immediately sees offer analysis
        if (isCollapsed) {
            toggleCollapse(false)
        }

        val dockContainer = overlayView?.findViewById<View>(R.id.dockContainer)
        val bubbleContainer = overlayView?.findViewById<View>(R.id.bubbleContainer)
        val tvStatusBadge = overlayView?.findViewById<TextView>(R.id.tvStatusBadge)
        val tvBubbleDot = overlayView?.findViewById<TextView>(R.id.tvBubbleDot)
        val tvTotalKm = overlayView?.findViewById<TextView>(R.id.tvTotalKm)
        val tvTotalMin = overlayView?.findViewById<TextView>(R.id.tvTotalMin)
        val tvOriginDist = overlayView?.findViewById<TextView>(R.id.tvOriginDist)
        val tvPerKm = overlayView?.findViewById<TextView>(R.id.tvPerKm)
        val tvPerMin = overlayView?.findViewById<TextView>(R.id.tvPerMin)
        val tvFairPrice = overlayView?.findViewById<TextView>(R.id.tvFairPrice)
        val tvDiffPrice = overlayView?.findViewById<TextView>(R.id.tvDiffPrice)

        val strokeColor: Int
        when (verdict) {
            "ACCEPT" -> {
                strokeColor = Color.parseColor("#10b981")
                tvStatusBadge?.text = "🟢 ACEPTAR"
                tvStatusBadge?.setBackgroundColor(Color.parseColor("#80064e3b"))
                tvStatusBadge?.setTextColor(Color.parseColor("#34d399"))
            }
            "REJECT_FAR" -> {
                strokeColor = Color.parseColor("#FF3366")
                tvStatusBadge?.text = "🔴 RECHAZAR • LEJOS"
                tvStatusBadge?.setBackgroundColor(Color.parseColor("#80450a0a"))
                tvStatusBadge?.setTextColor(Color.parseColor("#f87171"))
            }
            "REJECT" -> {
                strokeColor = Color.parseColor("#FF3366")
                tvStatusBadge?.text = "🔴 RECHAZAR"
                tvStatusBadge?.setBackgroundColor(Color.parseColor("#80450a0a"))
                tvStatusBadge?.setTextColor(Color.parseColor("#f87171"))
            }
            else -> {
                strokeColor = Color.parseColor("#FFBE0B")
                tvStatusBadge?.text = "🟡 REGULAR"
                tvStatusBadge?.setBackgroundColor(Color.parseColor("#80451a03"))
                tvStatusBadge?.setTextColor(Color.parseColor("#fbbf24"))
            }
        }

        val density = resources.displayMetrics.density

        // Dynamic colored border on translucent background (#D9080C14)
        val borderBg = GradientDrawable().apply {
            setColor(Color.parseColor("#D9080C14"))
            setStroke((1.5f * density).toInt(), strokeColor)
            cornerRadius = 14f * density
        }
        dockContainer?.background = borderBg

        // Update bubble styling as well
        val bubbleBg = GradientDrawable().apply {
            setColor(Color.parseColor("#D9080C14"))
            setStroke((2f * density).toInt(), strokeColor)
            cornerRadius = 24f * density
        }
        bubbleContainer?.background = bubbleBg
        tvBubbleDot?.setTextColor(strokeColor)

        // Route Summary: Total Km & Total Min
        tvTotalKm?.text = String.format(Locale.US, "%.1f km", totalKm)
        tvTotalMin?.text = "$totalMin min"

        // Pickup / Origin distance
        val pickupFormatted = String.format(Locale.US, "%.1f km", pickupKm)
        tvOriginDist?.text = pickupFormatted
        if (isPickupTooFar) {
            tvOriginDist?.setTextColor(Color.parseColor("#FF3366"))
        } else {
            tvOriginDist?.setTextColor(Color.parseColor("#10b981"))
        }

        // Rates
        tvPerKm?.text = "\$$perKm"
        tvPerKm?.setTextColor(Color.parseColor("#00F5D4"))
        tvPerMin?.text = "\$$perMin/m"
        tvFairPrice?.text = "\$$fairPrice"
        tvFairPrice?.setTextColor(Color.parseColor("#FFBE0B"))

        // Balance
        if (diffPrice >= 0) {
            tvDiffPrice?.text = "EXTRA: +\$$diffPrice"
            tvDiffPrice?.setTextColor(Color.parseColor("#10b981"))
        } else {
            val absDiff = if (diffPrice < 0) -diffPrice else diffPrice
            tvDiffPrice?.text = "FALTAN: -\$$absDiff"
            tvDiffPrice?.setTextColor(Color.parseColor("#FF3366"))
        }

        // 15 seconds auto-reset back to zero/idle
        resetRunnable?.let { mainHandler.removeCallbacks(it) }
        resetRunnable = Runnable {
            resetDockToIdle()
        }
        mainHandler.postDelayed(resetRunnable!!, 15000)
    }

    fun resetDockToIdle() {
        try {
            ensureDockAttached()

            val dockContainer = overlayView?.findViewById<View>(R.id.dockContainer)
            val bubbleContainer = overlayView?.findViewById<View>(R.id.bubbleContainer)
            val tvStatusBadge = overlayView?.findViewById<TextView>(R.id.tvStatusBadge)
            val tvBubbleDot = overlayView?.findViewById<TextView>(R.id.tvBubbleDot)
            val tvTotalKm = overlayView?.findViewById<TextView>(R.id.tvTotalKm)
            val tvTotalMin = overlayView?.findViewById<TextView>(R.id.tvTotalMin)
            val tvOriginDist = overlayView?.findViewById<TextView>(R.id.tvOriginDist)
            val tvPerKm = overlayView?.findViewById<TextView>(R.id.tvPerKm)
            val tvPerMin = overlayView?.findViewById<TextView>(R.id.tvPerMin)
            val tvFairPrice = overlayView?.findViewById<TextView>(R.id.tvFairPrice)
            val tvDiffPrice = overlayView?.findViewById<TextView>(R.id.tvDiffPrice)

            val density = resources.displayMetrics.density

            // Neutral translucent border
            val neutralBg = GradientDrawable().apply {
                setColor(Color.parseColor("#D9080C14"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#4D38BDF8"))
                cornerRadius = 14f * density
            }
            dockContainer?.background = neutralBg

            val neutralBubbleBg = GradientDrawable().apply {
                setColor(Color.parseColor("#D9080C14"))
                setStroke((2f * density).toInt(), Color.parseColor("#00F5D4"))
                cornerRadius = 24f * density
            }
            bubbleContainer?.background = neutralBubbleBg
            tvBubbleDot?.setTextColor(Color.parseColor("#00F5D4"))

            tvStatusBadge?.text = "⚪ KAPTOR • ESPERA"
            tvStatusBadge?.setBackgroundColor(Color.parseColor("#661E293B"))
            tvStatusBadge?.setTextColor(Color.parseColor("#94a3b8"))

            tvTotalKm?.text = "0.0 km"
            tvTotalMin?.text = "0 min"

            tvOriginDist?.text = "0.0 km"
            tvOriginDist?.setTextColor(Color.parseColor("#94a3b8"))

            tvPerKm?.text = "$0"
            tvPerMin?.text = "$0"
            tvFairPrice?.text = "$0"

            tvDiffPrice?.text = "BALANCE: --"
            tvDiffPrice?.setTextColor(Color.parseColor("#64748b"))

            currentOfferKey = ""
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeOverlayView() {
        try {
            resetRunnable?.let { mainHandler.removeCallbacks(it) }
            if (isOverlayAttached && overlayView != null) {
                windowManager?.removeView(overlayView)
                isOverlayAttached = false
            }
            currentOfferKey = ""
        } catch (e: Exception) {}
    }

    override fun onInterrupt() {
        removeOverlayView()
    }
}
