package com.uberspot.pro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("UberSpotPrefs", Context.MODE_PRIVATE)

        val swActive = findViewById<Switch>(R.id.swServiceActive)
        val etKm = findViewById<EditText>(R.id.etMinKm)
        val etMin = findViewById<EditText>(R.id.etMinTime)
        val rgFuel = findViewById<RadioGroup>(R.id.rgFuel)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)
        val btnPermOverlay = findViewById<Button>(R.id.btnPermOverlay)
        val btnPermAccess = findViewById<Button>(R.id.btnPermAccess)

        // Load saved state
        val isEnabled = prefs.getBoolean("service_enabled", true)
        swActive.isChecked = isEnabled
        updateSwitchText(swActive, isEnabled)

        swActive.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("service_enabled", isChecked).apply()
            updateSwitchText(swActive, isChecked)
            val msg = if (isChecked) "Asistente ACTIVADO (En Turno)" else "Asistente PAUSADO (En Descanso)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

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

            Toast.makeText(this, "Tarifas y configuración guardadas", Toast.LENGTH_SHORT).show()
        }

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
    }

    private fun updateSwitchText(sw: Switch, isChecked: Boolean) {
        if (isChecked) {
            sw.text = "🟢 Asistente ACTIVO (En Turno)"
            sw.setTextColor(android.graphics.Color.parseColor("#10b981"))
        } else {
            sw.text = "⚪ Asistente PAUSADO (En Descanso)"
            sw.setTextColor(android.graphics.Color.parseColor("#94a3b8"))
        }
    }
}
