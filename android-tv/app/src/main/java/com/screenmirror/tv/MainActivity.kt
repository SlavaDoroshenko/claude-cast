package com.screenmirror.tv

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.screenmirror.tv.databinding.ActivityMainBinding

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
            val intent = Intent(this, ConnectActivity::class.java)
            intent.putExtra(ConnectActivity.EXTRA_FOCUS_PIN, true)
            startActivity(intent)
        }
    }
}
