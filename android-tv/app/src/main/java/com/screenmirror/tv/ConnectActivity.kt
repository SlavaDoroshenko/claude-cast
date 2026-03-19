package com.screenmirror.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.screenmirror.tv.databinding.ActivityConnectBinding
import com.screenmirror.tv.discovery.DiscoveredDevice
import com.screenmirror.tv.discovery.NetworkDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ConnectActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOCUS_PIN = "focus_pin"
    }

    private lateinit var binding: ActivityConnectBinding
    private val devices  = mutableListOf<DiscoveredDevice>()
    private lateinit var adapter: DeviceAdapter
    private var discovery: NetworkDiscovery? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDeviceList()
        setupPinEntry()
        startDiscovery()

        binding.btnRescan.setOnClickListener {
            devices.clear()
            adapter.notifyDataSetChanged()
            binding.tvScanStatus.text = getString(R.string.lbl_scanning)
            startDiscovery()
        }

        // If launched from "Enter PIN" button, request focus on PIN field
        if (intent.getBooleanExtra(EXTRA_FOCUS_PIN, false)) {
            binding.pinDigit1.requestFocus()
        }
    }

    // ── Device List ──────────────────────────────────────────────────────────

    private fun setupDeviceList() {
        adapter = DeviceAdapter(devices) { device ->
            // Ask user for PIN before connecting
            showPinDialogFor(device)
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = adapter
    }

    private fun showPinDialogFor(device: DiscoveredDevice) {
        // For simplicity on TV, just prompt via PIN fields on the right panel.
        // In a more complex UI this would be a dialog.
        Toast.makeText(this, "Enter PIN for ${device.name}", Toast.LENGTH_SHORT).show()
        binding.etIpAddress.setText(device.ip)
        binding.pinDigit1.requestFocus()
    }

    // ── PIN entry ────────────────────────────────────────────────────────────

    private fun setupPinEntry() {
        // Auto-advance digits
        val digits = listOf(
            binding.pinDigit1,
            binding.pinDigit2,
            binding.pinDigit3,
            binding.pinDigit4,
        )
        digits.forEachIndexed { index, field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int)     = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (s?.length == 1 && index < digits.lastIndex) {
                        digits[index + 1].requestFocus()
                    }
                }
            })
        }

        binding.btnConnectManual.setOnClickListener {
            val ip  = binding.etIpAddress.text.toString().trim()
            val pin = digits.joinToString("") { it.text.toString() }

            when {
                ip.isEmpty()     -> Toast.makeText(this, "Enter IP address", Toast.LENGTH_SHORT).show()
                pin.length != 4  -> Toast.makeText(this, "Enter 4-digit PIN", Toast.LENGTH_SHORT).show()
                else             -> launchMirror(ip, 8765, pin)
            }
        }
    }

    // ── Discovery ────────────────────────────────────────────────────────────

    private fun startDiscovery() {
        discovery?.stop()
        discovery = NetworkDiscovery { device ->
            lifecycleScope.launch(Dispatchers.Main) {
                if (devices.none { it.ip == device.ip }) {
                    devices.add(device)
                    adapter.notifyItemInserted(devices.lastIndex)
                    binding.tvScanStatus.visibility = View.GONE
                }
            }
        }
        discovery?.start(lifecycleScope)

        // If nothing found after 5 s, show hint
        lifecycleScope.launch {
            kotlinx.coroutines.delay(5_000)
            if (devices.isEmpty()) {
                binding.tvScanStatus.text = getString(R.string.lbl_no_found)
            }
        }
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    private fun launchMirror(ip: String, port: Int, pin: String) {
        saveRecentDevice(ip, port)
        val intent = Intent(this, MirrorActivity::class.java).apply {
            putExtra(MirrorActivity.EXTRA_IP,   ip)
            putExtra(MirrorActivity.EXTRA_PORT, port)
            putExtra(MirrorActivity.EXTRA_PIN,  pin)
        }
        startActivity(intent)
    }

    private fun saveRecentDevice(ip: String, port: Int) {
        val prefs = getSharedPreferences("screenmirror", Context.MODE_PRIVATE)
        val arr   = try {
            JSONArray(prefs.getString("recent_devices", "[]"))
        } catch (_: Exception) { JSONArray() }

        // Remove duplicate, then prepend
        val newArr = JSONArray()
        newArr.put(JSONObject().apply {
            put("name", ip)
            put("ip",   ip)
            put("port", port)
        })
        for (i in 0 until arr.length()) {
            try {
                val obj = arr.getJSONObject(i)
                if (obj.getString("ip") != ip) newArr.put(obj)
            } catch (_: Exception) {}
        }

        prefs.edit().putString("recent_devices", newArr.toString()).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        discovery?.stop()
    }

    // ── Device Adapter ────────────────────────────────────────────────────────

    private inner class DeviceAdapter(
        private val items:   MutableList<DiscoveredDevice>,
        private val onClick: (DiscoveredDevice) -> Unit,
    ) : RecyclerView.Adapter<DeviceAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvDeviceName)
            val tvIp:   TextView = v.findViewById(R.id.tvDeviceIp)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_device, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvIp.text   = item.ip
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
