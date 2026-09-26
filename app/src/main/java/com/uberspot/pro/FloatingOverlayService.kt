package com.uberspot.pro

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.Locale

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
            netProfit: Int,
            fairPrice: Int
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
                putExtra("fairPrice", fairPrice)
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
        val fairPrice = intent.getIntExtra("fairPrice", 0)

        showOverlayBubble(appName, fare, perKm, netPerHour, km, min, verdict, netProfit, fairPrice)
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
        netProfit: Int,
        fairPrice: Int
    ) {
        if (windowManager == null) {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }

        if (overlayView == null) {
            createOverlayView()
        }

        val pillContainer = overlayView?.findViewById<View>(R.id.pillContainer)
        val tvAppBadge = overlayView?.findViewById<TextView>(R.id.tvAppBadge)
        val tvTitle = overlayView?.findViewById<TextView>(R.id.tvVerdictTitle)
        val tvFair = overlayView?.findViewById<TextView>(R.id.tvFairPrice)
        val tvSub = overlayView?.findViewById<TextView>(R.id.tvVerdictSub)

        // App badge styling
        if (appName.equals("DiDi", ignoreCase = true)) {
            tvAppBadge?.text = "DIDI"
            val badgeBg = GradientDrawable().apply {
                setColor(Color.parseColor("#ff7d00"))
                cornerRadius = 14f
            }
            tvAppBadge?.background = badgeBg
        } else {
            tvAppBadge?.text = "UBER"
            val badgeBg = GradientDrawable().apply {
                setColor(Color.parseColor("#0f172a"))
                setStroke(2, Color.parseColor("#cbd5e1"))
                cornerRadius = 14f
            }
            tvAppBadge?.background = badgeBg
        }

        val kmFormatted = String.format(Locale.US, "%.1f", km)
        val kPerHour = netPerHour / 1000
        val diffPrice = fairPrice - fare

        val strokeColor: Int
        when (verdict) {
            "ACCEPT" -> {
                strokeColor = Color.parseColor("#10b981")
                tvTitle?.text = "🟢 ACEPTAR • \$$perKm / km"
                tvTitle?.setTextColor(Color.parseColor("#34d399"))
                tvFair?.text = "💡 Tarifa Justa: \$$fairPrice (¡Paga excelente!)"
                tvFair?.setTextColor(Color.parseColor("#a7f3d0"))
            }
            "REJECT" -> {
                strokeColor = Color.parseColor("#ef4444")
                tvTitle?.text = "🔴 RECHAZAR • \$$perKm / km"
                tvTitle?.setTextColor(Color.parseColor("#f87171"))
                val diffText = if (diffPrice > 0) " (Faltan \$$diffPrice)" else ""
                tvFair?.text = "💡 Debería pagar: \$$fairPrice$diffText"
                tvFair?.setTextColor(Color.parseColor("#fef08a"))
            }
            else -> {
                strokeColor = Color.parseColor("#f59e0b")
                tvTitle?.text = "🟡 REGULAR • \$$perKm / km"
                tvTitle?.setTextColor(Color.parseColor("#fbbf24"))
                val diffText = if (diffPrice > 0) " (Faltan \$$diffPrice)" else ""
                tvFair?.text = "💡 Debería pagar: \$$fairPrice$diffText"
                tvFair?.setTextColor(Color.parseColor("#ffffff"))
            }
        }

        // HUD Container Border Styling
        val containerDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#0f172a"))
            setStroke(4, strokeColor)
            cornerRadius = 32f
        }
        pillContainer?.background = containerDrawable

        tvSub?.text = "$appName: \$$fare (\$$netProfit neto) | ~\$${kPerHour}k/h ($kmFormatted km • $min min)"

        overlayView?.visibility = View.VISIBLE

        dismissRunnable?.let { autoDismissHandler.removeCallbacks(it) }
        dismissRunnable = Runnable {
            overlayView?.visibility = View.GONE
        }
        autoDismissHandler.postDelayed(dismissRunnable!!, 15000)
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
            y = 100
        }

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_bubble, null)

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
