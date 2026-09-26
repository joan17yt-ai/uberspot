package com.uberspot.pro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("UberSpotPrefs", Context.MODE_PRIVATE)

        val swActive = findViewById<Switch>(R.id.swServiceActive)
        val tvSwitchSub = findViewById<TextView>(R.id.tvSwitchSub)
        val swMonitorUber = findViewById<Switch>(R.id.swMonitorUber)
        val swMonitorDidi = findViewById<Switch>(R.id.swMonitorDidi)

        val etKm = findViewById<EditText>(R.id.etMinKm)
        val etMin = findViewById<EditText>(R.id.etMinTime)
        val rgFuel = findViewById<RadioGroup>(R.id.rgFuel)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)

        val btnPermOverlay = findViewById<Button>(R.id.btnPermOverlay)
        val btnPermAccess = findViewById<Button>(R.id.btnPermAccess)

        val btnTestDidi = findViewById<Button>(R.id.btnTestDidi)
        val btnTestUber = findViewById<Button>(R.id.btnTestUber)

        // 1. MASTER SWITCH
        val isEnabled = prefs.getBoolean("service_enabled", true)
        swActive.isChecked = isEnabled
        updateMasterSwitchState(swActive, tvSwitchSub, isEnabled)

        swActive.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("service_enabled", isChecked).apply()
            updateMasterSwitchState(swActive, tvSwitchSub, isChecked)
            val msg = if (isChecked) "🟢 Asistente ACTIVADO (En Turno)" else "⚪ Asistente PAUSADO (En Descanso)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 2. PLATFORM SWITCHES (UBER & DIDI)
        swMonitorUber.isChecked = prefs.getBoolean("monitor_uber", true)
        swMonitorDidi.isChecked = prefs.getBoolean("monitor_didi", true)

        swMonitorUber.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("monitor_uber", isChecked).apply()
            val state = if (isChecked) "activado" else "desactivado"
            Toast.makeText(this, "Monitoreo de Uber $state", Toast.LENGTH_SHORT).show()
        }

        swMonitorDidi.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("monitor_didi", isChecked).apply()
            val state = if (isChecked) "activado" else "desactivado"
            Toast.makeText(this, "Monitoreo de DiDi $state", Toast.LENGTH_SHORT).show()
        }

        // 3. FINANCIAL INPUTS (OPTION B)
        etKm.setText(prefs.getInt("min_rate_km", 1800).toString())
        etMin.setText(prefs.getInt("min_rate_min", 400).toString())

        if (prefs.getString("fuel_type", "gnv") == "gasolina") {
            rgFuel.check(R.id.rbGas)
        } else {
            rgFuel.check(R.id.rbGnv)
        }

        btnSave.setOnClickListener {
            val kmVal = etKm.text.toString().toIntOrNull() ?: 1800
            val minVal = etMin.text.toString().toIntOrNull() ?: 400
            val fuel = if (rgFuel.checkedRadioButtonId == R.id.rbGas) "gasolina" else "gnv"

            prefs.edit()
                .putInt("min_rate_km", kmVal)
                .putInt("min_rate_min", minVal)
                .putString("fuel_type", fuel)
                .apply()

            Toast.makeText(this, "✓ Parámetros guardados (Opción B: Mayor entre Km y Min)", Toast.LENGTH_SHORT).show()
        }

        // 4. PERMISSIONS
        btnPermOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            } else {
                Toast.makeText(this, "Permiso de superposición ya concedido", Toast.LENGTH_SHORT).show()
            }
        }

        btnPermAccess.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Activa 'UberSpot Pro' en Servicios Instalados", Toast.LENGTH_LONG).show()
        }

        // 5. TEST DEMO BUTTONS
        btnTestDidi.setOnClickListener {
            // Sample: 10 km, 45 min, Fare: $12.000.
            // Option B: targetKm = 10 * 1800 = 18.000. targetMin = 45 * 400 = 18.000. FairPrice = 18.000.
            FloatingOverlayService.showVerdict(
                this,
                appName = "DiDi",
                fare = 12000,
                perKm = 1200,
                netPerHour = 13600,
                km = 10.0,
                min = 45,
                verdict = "REJECT",
                netProfit = 10200,
                fairPrice = 18000
            )
            Toast.makeText(this, "Simulando oferta DiDi Pon Tu Precio...", Toast.LENGTH_SHORT).show()
        }

        btnTestUber.setOnClickListener {
            // Sample: 7.2 km, 18 min, Fare: $16.500.
            // Option B: targetKm = 7.2 * 1800 = 12.960. targetMin = 18 * 400 = 7.200. FairPrice = 13.000.
            FloatingOverlayService.showVerdict(
                this,
                appName = "Uber",
                fare = 16500,
                perKm = 2291,
                netPerHour = 39200,
                km = 7.2,
                min = 18,
                verdict = "ACCEPT",
                netProfit = 11760,
                fairPrice = 13000
            )
            Toast.makeText(this, "Simulando oferta UberX...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMasterSwitchState(sw: Switch, tvSub: TextView, isChecked: Boolean) {
        if (isChecked) {
            sw.text = "🟢 Asistente ACTIVO (En Turno)"
            sw.setTextColor(Color.parseColor("#10b981"))
            tvSub.text = "Escaneando ofertas en segundo plano con respuesta en 20ms"
        } else {
            sw.text = "⚪ Asistente PAUSADO (En Descanso)"
            sw.setTextColor(Color.parseColor("#94a3b8"))
            tvSub.text = "Asistente dormido. Cero consumo de batería y sin alertas."
        }
    }
}
