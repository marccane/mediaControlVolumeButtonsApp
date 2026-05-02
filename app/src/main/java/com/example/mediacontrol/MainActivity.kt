package com.example.mediacontrol

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.example.mediacontrol.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinners()
        setupActiveSwitch()

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        // Sync switch in case something changed while app was in background
        binding.switchActive.isChecked = GestureConfig.isActive(this)
    }

    private fun setupActiveSwitch() {
        binding.switchActive.isChecked = GestureConfig.isActive(this)
        binding.switchActive.setOnCheckedChangeListener { _, isChecked ->
            GestureConfig.setActive(this, isChecked)
        }
    }

    private fun setupSpinners() {
        val actions = MediaAction.values()
        val labels = actions.map { it.displayName }.toTypedArray()

        val pairs = listOf(
            binding.spinnerVolUpSingle   to GestureType.VOL_UP_SINGLE,
            binding.spinnerVolUpDouble   to GestureType.VOL_UP_DOUBLE,
            binding.spinnerVolUpLong     to GestureType.VOL_UP_LONG,
            binding.spinnerVolDownSingle to GestureType.VOL_DOWN_SINGLE,
            binding.spinnerVolDownDouble to GestureType.VOL_DOWN_DOUBLE,
            binding.spinnerVolDownLong   to GestureType.VOL_DOWN_LONG
        )

        for ((spinner, gesture) in pairs) {
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
            spinner.setSelection(actions.indexOf(GestureConfig.getAction(this, gesture)), false)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    GestureConfig.setAction(this@MainActivity, gesture, actions[pos])
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
    }

    private fun updateServiceStatus() {
        if (isServiceEnabled()) {
            binding.tvServiceStatus.text = "Service active — gestures are working"
            binding.tvServiceStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            binding.tvServiceStatus.text = "Service inactive — tap below to enable in Accessibility Settings"
            binding.tvServiceStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }

    private fun isServiceEnabled(): Boolean {
        val component = ComponentName(this, VolumeKeyService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(component, ignoreCase = true) }
    }
}
