package com.uberspot.pro

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val autoDismissHandler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

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
            netProfit: Int
        ) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                putExtra("appName", appName)
                putExtra("fare", fare)
                putExtra("perKm", perKm)
                putExtra("netPerHour", netPerHour)
                putExtra("km", km)
                putExtra("min", min)
                putExtra("verdict", verdict)
                putExtra("netProfit", netProfit)
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val appName = intent.getStringExtra("appName") ?: "Viaje"
        val fare = intent.getIntExtra("fare", 0)
        val perKm = intent.getIntExtra("perKm", 0)
        val netPerHour = intent.getIntExtra("netPerHour", 0)
        val km = intent.getDoubleExtra("km", 0.0)
        val min = intent.getIntExtra("min", 0)
        val verdict = intent.getStringExtra("verdict") ?: "REGULAR"
        val netProfit = intent.getIntExtra("netProfit", 0)

        showOverlayBubble(appName, fare, perKm, netPerHour, km, min, verdict, netProfit)
        return START_STICKY
    }

    private fun showOverlayBubble(
        appName: String,
        fare: Int,
        perKm: Int,
        netPerHour: Int,
        km: Double,
        min: Int,
        verdict: String,
        netProfit: Int
    ) {
        if (windowManager == null) {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }

        if (overlayView == null) {
            createOverlayView()
        }

        val tvTitle = overlayView?.findViewById<TextView>(R.id.tvVerdictTitle)
        val tvSub = overlayView?.findViewById<TextView>(R.id.tvVerdictSub)
        val pillContainer = overlayView?.findViewById<View>(R.id.pillContainer)

        when (verdict) {
            "ACCEPT" -> {
                pillContainer?.setBackgroundColor(Color.parseColor("#10b981")) // Green
                tvTitle?.text = "🟢 ACEPTAR • $$perKm / km"
                tvSub?.text = "$appName: $$fare ($netProfit neto) | ~$${netPerHour/1000}k/h (${String.format("%.1f", km)}km • ${min}m)"
            }
            "REJECT" -> {
                pillContainer?.setBackgroundColor(Color.parseColor("#ef4444")) // Red
                tvTitle?.text = "🔴 RECHAZAR • $$perKm / km"
                tvSub?.text = "$appName: $$fare | Bajo retorno: ~$${netPerHour/1000}k/h"
            }
            else -> {
                pillContainer?.setBackgroundColor(Color.parseColor("#f59e0b")) // Amber
                tvTitle?.text = "🟡 REGULAR • $$perKm / km"
                tvSub?.text = "$appName: $$fare (${String.format("%.1f", km)}km • ${min}m)"
            }
        }

        overlayView?.visibility = View.VISIBLE

        // Auto dismiss after 16 seconds (Uber/DiDi offer expires)
        dismissRunnable?.let { autoDismissHandler.removeCallbacks(it) }
        dismissRunnable = Runnable {
            overlayView?.visibility = View.GONE
        }
        autoDismissHandler.postDelayed(dismissRunnable!!, 16000)
    }

    private fun createOverlayView() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120 // Positioned just above or below the offer card
        }

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_bubble, null)

        // Close on tap
        overlayView?.setOnClickListener {
            overlayView?.visibility = View.GONE
        }

        windowManager?.addView(overlayView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { windowManager?.removeView(it) }
    }
}
