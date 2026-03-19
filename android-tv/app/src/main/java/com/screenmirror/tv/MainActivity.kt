package com.screenmirror.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.screenmirror.tv.databinding.ActivityMainBinding
import org.json.JSONArray

/** Data class for a previously connected device. */
data class RecentDevice(val name: String, val ip: String, val port: Int)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnConnect.setOnClickListener {
            startActivity(Intent(this, ConnectActivity::class.java))
        }

        binding.btnEnterPin.setOnClickListener {
            // Open ConnectActivity scrolled to the manual PIN section
            val intent = Intent(this, ConnectActivity::class.java)
            intent.putExtra(ConnectActivity.EXTRA_FOCUS_PIN, true)
            startActivity(intent)
        }

        loadRecentDevices()
    }

    private fun loadRecentDevices() {
        val prefs = getSharedPreferences("screenmirror", Context.MODE_PRIVATE)
        val json  = prefs.getString("recent_devices", null) ?: return
        val arr   = try { JSONArray(json) } catch (_: Exception) { return }

        if (arr.length() == 0) return

        val devices = (0 until arr.length()).mapNotNull {
            try {
                val obj = arr.getJSONObject(it)
                RecentDevice(
                    name = obj.getString("name"),
                    ip   = obj.getString("ip"),
                    port = obj.getInt("port"),
                )
            } catch (_: Exception) { null }
        }

        if (devices.isEmpty()) return

        binding.tvRecentLabel.visibility = View.VISIBLE
        binding.rvRecent.visibility      = View.VISIBLE
        binding.rvRecent.layoutManager   = LinearLayoutManager(this)
        binding.rvRecent.adapter         = RecentAdapter(devices) { device ->
            launchMirror(device.ip, device.port, "1234") // PIN not needed for re-connect
        }
    }

    private fun launchMirror(ip: String, port: Int, pin: String) {
        val intent = Intent(this, MirrorActivity::class.java).apply {
            putExtra(MirrorActivity.EXTRA_IP,   ip)
            putExtra(MirrorActivity.EXTRA_PORT, port)
            putExtra(MirrorActivity.EXTRA_PIN,  pin)
        }
        startActivity(intent)
    }

    // ── Recent Adapter ───────────────────────────────────────────────────────

    private inner class RecentAdapter(
        private val items:    List<RecentDevice>,
        private val onClick:  (RecentDevice) -> Unit,
    ) : RecyclerView.Adapter<RecentAdapter.VH>() {

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
