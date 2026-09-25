package com.uberspot.pro

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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

        val pkgName = event.packageName?.toString() ?: ""
        if (!pkgName.contains("uber", ignoreCase = true) && !pkgName.contains("didi", ignoreCase = true)) {
            return
        }

        val root = rootInActiveWindow ?: return
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < 150) return // Throttling 150ms

        val textCollector = StringBuilder()
        collectTextsRecursively(root, textCollector)
        val fullText = textCollector.toString()

        if (fullText.isEmpty() || fullText == lastExtractedText) return
        lastExtractedText = fullText
        lastProcessTime = now

        parseOfferAndEvaluate(fullText)
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
        for (i in 0 until node.childCount) {
            collectTextsRecursively(node.getChild(i), sb)
        }
    }

    private fun parseOfferAndEvaluate(rawText: String) {
        // 1. FARE EXTRACTION (COP7,425 or $8.401 or $9.600)
        var fare = 0
        val lines = rawText.split("\n")
        for (line in lines) {
            val l = line.trim()
            if (l.contains("/km") || l.contains("+COP") || l.contains("+$")) continue
            val m = Pattern.compile("(?:COP|\\$)\\s*([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,6})", Pattern.CASE_INSENSITIVE).matcher(l)
            if (m.find()) {
                val cleaned = m.group(1)?.replace(".", "")?.replace(",", "") ?: ""
                fare = cleaned.toIntOrNull() ?: 0
                if (fare >= 4000) break
            }
        }

        if (fare == 0) {
            val acceptM = Pattern.compile("Aceptar\\s*\\$?\\s*([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,6})", Pattern.CASE_INSENSITIVE).matcher(rawText)
            if (acceptM.find()) {
                val cleaned = acceptM.group(1)?.replace(".", "")?.replace(",", "") ?: ""
                fare = cleaned.toIntOrNull() ?: 0
            }
        }

        if (fare < 4000) return // Not a ride offer screen

        // 2. DETECT APP & COMMISSION
        val isZeroCommission = rawText.contains("0% de tarifa", ignoreCase = true)
        val isDidi = rawText.contains("didi", ignoreCase = true) || rawText.contains("pon tu precio", ignoreCase = true) || rawText.contains("arrendamiento", ignoreCase = true)
        val appName = if (isDidi) "DiDi" else "Uber"

        val commissionRate = if (isZeroCommission) 0.0 else if (isDidi) 0.15 else 0.20

        // 3. DETECT DISTANCES AND TIMES
        // Handles: "3 min (820 m)", "8 min (2,6 km)", "A 5 min (0.9 km)", "Viaje: 9 min (2.6 km)"
        var totalKm = 0.0
        var totalMin = 0

        val legMatcher = Pattern.compile("([0-9]+)\\s*min(?:uto)?s?\\s*\\(\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(km|m)\\s*\\)", Pattern.CASE_INSENSITIVE).matcher(rawText)
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

        if (legsFound == 0) {
            // Fallback standalone search
            val kmMatcher = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*(?:km|kms)", Pattern.CASE_INSENSITIVE).matcher(rawText)
            while (kmMatcher.find()) {
                val d = kmMatcher.group(1)?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                if (d in 0.5..80.0) totalKm += d
            }
            val minMatcher = Pattern.compile("([0-9]{1,3})\\s*min", Pattern.CASE_INSENSITIVE).matcher(rawText)
            while (minMatcher.find()) {
                val t = minMatcher.group(1)?.toIntOrNull() ?: 0
                if (t in 2..180) totalMin += t
            }
        }

        if (totalKm <= 0.0) return

        // 4. FINANCIAL CALCULATION & VERDICT
        val minRateKm = prefs.getInt("min_rate_km", 1800)
        val minRateMin = prefs.getInt("min_rate_min", 400)
        val fuelType = prefs.getString("fuel_type", "gnv") ?: "gnv"

        val fuelPerKm = if (fuelType == "gnv") 200 else 420
        val fuelCost = (totalKm * fuelPerKm).toInt()
        val commAmount = (fare * commissionRate).toInt()
        val netProfit = (fare - commAmount - fuelCost).coerceAtLeast(0)

        val perKm = (fare / totalKm).toInt()
        val netPerHour = if (totalMin > 0) ((netProfit.toDouble() / totalMin) * 60).toInt() else 0
        val netPerMin = if (totalMin > 0) (netProfit / totalMin) else 0

        val verdict = when {
            perKm >= minRateKm && netPerMin >= minRateMin -> "ACCEPT"
            perKm >= (minRateKm * 0.85) -> "REGULAR"
            else -> "REJECT"
        }

        // 5. UPDATE FLOATING BUBBLE OVERLAY IN 50 MILLISECONDS
        FloatingOverlayService.showVerdict(
            this,
            appName = appName,
            fare = fare,
            perKm = perKm,
            netPerHour = netPerHour,
            km = totalKm,
            min = totalMin,
            verdict = verdict,
            netProfit = netProfit
        )
    }

    override fun onInterrupt() {}
}
