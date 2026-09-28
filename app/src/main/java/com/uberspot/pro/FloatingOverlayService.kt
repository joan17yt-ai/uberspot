package com.uberspot.pro

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Legacy stub: Toda la funcionalidad del HUD flotante está unificada de forma nativa en RideAccessibilityService.
 */
class FloatingOverlayService : Service() {

    companion object {
        fun showVerdict(
            context: Context,
            appName: String,
            fare: Int,
            perKm: Int,
            netPerHour: Int,
            km: Double,
            min: Int,
            verdict: String,
            netProfit: Int,
            fairPrice: Int
        ) {
            // No-op legacy bridge
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
