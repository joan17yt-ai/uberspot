package com.uberspot.pro

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

class RideAccessibilityService : AccessibilityService() {

    private lateinit var prefs: SharedPreferences
    private var lastExtractedText = ""
    private var lastProcessTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("UberSpotPrefs", Context.MODE_PRIVATE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. Instant check: Is the assistant enabled by the driver?
        if (!prefs.getBoolean("service_enabled", true)) return

        val now = System.currentTimeMillis()
        if (now - lastProcessTime < 50) return // Throttling 50ms

        val pkgName = (event.packageName ?: "").toString().lowercase()

        // 2. Platform selector check
        val monitorUber = prefs.getBoolean("monitor_uber", true)
        val monitorDidi = prefs.getBoolean("monitor_didi", true)

        val isUberPkg = pkgName.contains("ubercab") || pkgName.contains("uber")
        val isDidiPkg = pkgName.contains("didi") || pkgName.contains("xiaojukeji")

        if (isUberPkg && !monitorUber) return
        if (isDidiPkg && !monitorDidi) return

        // 3. Extract text: Start with rootInActiveWindow for full screen context
        val textCollector = StringBuilder()
        rootInActiveWindow?.let { collectTextsRecursively(it, textCollector) }

        // Fallback to event.source if rootInActiveWindow was null or empty (e.g. popups/dialogs)
        if (textCollector.length < 25) {
            event.source?.let { collectTextsRecursively(it, textCollector) }
        }

        // Additional fallback across all windows on Android Lollipop+
        if (textCollector.length < 25 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                for (window in windows) {
                    window.root?.let { collectTextsRecursively(it, textCollector) }
                }
            } catch (e: Exception) {}
        }

        val fullText = textCollector.toString()
        if (fullText.isEmpty() || fullText == lastExtractedText) return
        lastExtractedText = fullText
        lastProcessTime = now

        parseOfferAndEvaluate(fullText, pkgName, monitorUber, monitorDidi)
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

    private fun parseOfferAndEvaluate(
        rawText: String,
        pkgName: String,
        monitorUber: Boolean,
        monitorDidi: Boolean
    ) {
        // Normalize Unicode non-breaking spaces (\u00A0, \u202F, etc.) to standard ASCII space
        val cleanText = rawText.replace(Regex("[\\u00A0\\u202F\\u2000-\\u200B]"), " ")
        val lowerText = cleanText.lowercase()
        val lowerPkg = pkgName.lowercase()

        // Detect Uber
        val isUber = (
            lowerPkg.contains("ubercab") || lowerPkg.contains("uber") ||
            lowerText.contains("uber") || lowerText.contains("economy") || lowerText.contains("comfort") ||
            lowerText.contains("contrato de renta") || lowerText.contains("exclusivo") || lowerText.contains("viaje:")
        ) && monitorUber

        // Detect DiDi
        val isDidi = (
            lowerPkg.contains("didi") || lowerPkg.contains("xiaojukeji") ||
            lowerText.contains("didi") || lowerText.contains("pon tu precio") || lowerText.contains("contraofertar") ||
            lowerText.contains("tarifa de servicio") || lowerText.contains("4 puntos") || lowerText.contains("arrendamiento") ||
            (lowerText.contains("aceptar") && !isUber)
        ) && monitorDidi

        if (!isUber && !isDidi) return

        val appName = if (isDidi) "DiDi" else "Uber"

        // 1. FARE EXTRACTION
        val lines = cleanText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        var fare = 0

        // Rule A (Uber specific): Main trip fare is immediately followed by '(estimado)' or '/km'
        for (idx in 0 until lines.size - 1) {
            val curr = lines[idx]
            val nxt = lines[idx + 1].lowercase()
            if (nxt.contains("/km") || nxt.contains("estimado")) {
                val m = Pattern.compile("(?:COP|\\$)?\\s*([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,6})", Pattern.CASE_INSENSITIVE).matcher(curr)
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

        // Rule B: Line immediately following service category (Economy, UberX, Comfort, DiDi Express, etc.)
        if (fare == 0) {
            for (idx in 0 until lines.size - 1) {
                val curr = lines[idx].lowercase()
                if (curr.contains("economy") || curr.contains("uberx") || curr.contains("comfort") ||
                    curr.contains("flash") || curr.contains("moto") || curr.contains("express")) {
                    val nxt = lines[idx + 1]
                    val m = Pattern.compile("(?:COP|\\$)?\\s*([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,6})", Pattern.CASE_INSENSITIVE).matcher(nxt)
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

        // Rule C: Inside 'Aceptar $X' or 'Oferta $X' button (common in DiDi Pon Tu Precio)
        if (fare == 0) {
            val acceptMatcher = Pattern.compile(
                "(?:Aceptar|Oferta|Precio)\\s*(?:COP|\\$)?\\s*([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,6})",
                Pattern.CASE_INSENSITIVE
            ).matcher(cleanText)
            if (acceptMatcher.find()) {
                val numStr = acceptMatcher.group(1)?.replace(".", "")?.replace(",", "") ?: ""
                val cand = numStr.toIntOrNull() ?: 0
                if (cand in 4000..350000) {
                    fare = cand
                }
            }
        }

        // Rule D: General line scan, strictly ignoring balances (saldo/hoy), surges (+COP), and rates (/km)
        if (fare == 0) {
            for (line in lines) {
                val l = line.lowercase()
                if (l.contains("/km") || l.contains("+cop") || l.contains("+$") ||
                    l.contains("saldo") || l.contains("hoy") || l.contains("ganancia") || l.contains("meta")) {
                    continue
                }
                val m = Pattern.compile(
                    "(?:COP|\\$)\\s*([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,6})|([0-9]{1,3}(?:[.,][0-9]{3})+)\\s*(?:COP|\\$)",
                    Pattern.CASE_INSENSITIVE
                ).matcher(line)
                if (m.find()) {
                    val numStr = (m.group(1) ?: m.group(2))?.replace(".", "")?.replace(",", "") ?: ""
                    val cand = numStr.toIntOrNull() ?: 0
                    if (cand in 4000..350000) {
                        fare = cand
                        break
                    }
                }
            }
        }

        // Rule E: Standalone numbers formatted as Colombian currency without currency symbol (e.g. 8.500)
        if (fare == 0) {
            for (line in lines) {
                val l = line.lowercase()
                if (l.contains("/km") || l.contains("km") || l.contains("min") || l.contains("%") ||
                    l.contains("saldo") || l.contains("hoy")) {
                    continue
                }
                val standaloneMatcher = Pattern.compile(
                    "\\b([4-9]\\.[0-9]{3}|[1-9][0-9]\\.[0-9]{3}|[1-2][0-9]{2}\\.[0-9]{3})\\b"
                ).matcher(line)
                if (standaloneMatcher.find()) {
                    val cand = standaloneMatcher.group(1)?.replace(".", "")?.toIntOrNull() ?: 0
                    if (cand in 4000..350000) {
                        fare = cand
                        break
                    }
                }
            }
        }

        if (fare < 4000) return

        // 2. COMMISSION
        val isZeroCommission = lowerText.contains("0% de tarifa")
        val commissionRate = if (isZeroCommission) 0.0 else if (isDidi) 0.15 else 0.20

        // 3. DISTANCES AND TIMES
        var totalKm = 0.0
        var totalMin = 0

        // Pattern 1: Combined "X min (Y km)" or "X min (Y m)"
        val legMatcher = Pattern.compile(
            "([0-9]+)\\s*min(?:uto)?s?\\s*\\(\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(km|m)\\s*\\)",
            Pattern.CASE_INSENSITIVE
        ).matcher(cleanText)

        var legsFound = 0
        while (legMatcher.find()) {
            legsFound++
            val m = legMatcher.group(1)?.toIntOrNull() ?: 0
            val dStr = legMatcher.group(2)?.replace(",", ".") ?: "0"
            val d = dStr.toDoubleOrNull() ?: 0.0
            val unit = legMatcher.group(3) ?: "km"

            totalMin += m
            if (unit.equals("m", ignoreCase = true)) {
                totalKm += d / 1000.0
            } else {
                totalKm += d
            }
        }

        // Pattern 2: Independent chips (common in DiDi Express & Pon Tu Precio)
        if (legsFound == 0) {
            val kmMatcher = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*(?:km|kms)\\b", Pattern.CASE_INSENSITIVE).matcher(cleanText)
            while (kmMatcher.find()) {
                val d = kmMatcher.group(1)?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                if (d in 0.4..70.0) totalKm += d
            }

            val mMatcher = Pattern.compile("([0-9]{2,4})\\s*(?:m|metros)\\b", Pattern.CASE_INSENSITIVE).matcher(cleanText)
            while (mMatcher.find()) {
                val mVal = mMatcher.group(1)?.toDoubleOrNull() ?: 0.0
                if (mVal in 50.0..2500.0) totalKm += mVal / 1000.0
            }

            val minMatcher = Pattern.compile("([0-9]{1,3})\\s*(?:min|mins|minuto|minutos)\\b", Pattern.CASE_INSENSITIVE).matcher(cleanText)
            while (minMatcher.find()) {
                val t = minMatcher.group(1)?.toIntOrNull() ?: 0
                if (t in 1..180) totalMin += t
            }
        }

        if (totalKm <= 0.0) return

        // 4. FINANCIAL CALCULATION - OPCIÓN B (MAYOR ENTRE KM Y TIEMPO, SIN SUMARLOS)
        val minRateKm = prefs.getInt("min_rate_km", 1800)
        val minRateMin = prefs.getInt("min_rate_min", 400)
        val fuelType = prefs.getString("fuel_type", "gnv") ?: "gnv"

        val fuelPerKm = if (fuelType == "gnv") 200 else 420
        val fuelCost = (totalKm * fuelPerKm).toInt()
        val commAmount = (fare * commissionRate).toInt()
        val netProfit = (fare - commAmount - fuelCost).coerceAtLeast(0)

        val perKm = (fare / totalKm).toInt()
        val netPerHour = if (totalMin > 0) ((netProfit.toDouble() / totalMin) * 60).toInt() else 0

        // OPTION B: Elige el MAYOR entre el valor por Km y el valor por Minutos
        val targetKmFare = (totalKm * minRateKm).toInt()
        val targetMinFare = (totalMin * minRateMin).toInt()
        val rawFairPrice = maxOf(targetKmFare, targetMinFare)
        val fairPrice = (Math.round(rawFairPrice / 100.0) * 100).toInt() // Redondeo a la centena

        // VERDICT
        val verdict = when {
            fare >= fairPrice && perKm >= minRateKm -> "ACCEPT"
            perKm >= (minRateKm * 0.85) -> "REGULAR"
            else -> "REJECT"
        }

        // 5. SHOW FLOATING HUD OVERLAY
        FloatingOverlayService.showVerdict(
            this,
            appName = appName,
            fare = fare,
            perKm = perKm,
            netPerHour = netPerHour,
            km = totalKm,
            min = totalMin,
            verdict = verdict,
            netProfit = netProfit,
            fairPrice = fairPrice
        )
    }

    override fun onInterrupt() {}
}
