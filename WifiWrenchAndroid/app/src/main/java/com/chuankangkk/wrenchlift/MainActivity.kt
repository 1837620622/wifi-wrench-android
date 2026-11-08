package com.chuankangkk.wrenchlift

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.chuankangkk.wrenchlift.diagnostics.DiagnosticLog
import com.chuankangkk.wrenchlift.ui.WrenchLiftApp

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val deniedPermissions = requiredRuntimePermissions().filter { permission ->
            results[permission] == false &&
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (deniedPermissions.isNotEmpty()) {
            Toast.makeText(
                this,
                "未授权 Wi-Fi/定位权限，可能无法读取当前 Wi-Fi 或自动诊断扳手网络。",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.install(this)
        requestMissingRuntimePermissions()
        setContent {
            WrenchLiftApp()
        }
    }

    private fun requestMissingRuntimePermissions() {
        val missing = requiredRuntimePermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requiredRuntimePermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }
}
