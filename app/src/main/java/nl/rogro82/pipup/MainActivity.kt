/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package nl.rogro82.pipup

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import nl.rogro82.pipup.Utils.getIpAddress

class MainActivity : Activity() {
    @Suppress("PropertyName", "MemberVisibilityCanBePrivate")
    var ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE = 5469
    @SuppressLint("CutPasteId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        //Ask permission to draw over other apps
        if (!Settings.canDrawOverlays(this)) {
            askPermission();
        }
        // start service in foreground

        val textViewConnection = findViewById<TextView>(R.id.textViewServerAddress)
        val textViewServerAddress = findViewById<TextView>(R.id.textViewServerAddress)
        val textViewVersion = findViewById<TextView>(R.id.textViewVersion)

        textViewVersion.apply {
            visibility = View.VISIBLE
            if (BuildConfig.DEBUG) {
                text = resources.getString(
                    R.string.version_number_debug,
                    BuildConfig.VERSION_NAME
                )
                setTextColor(resources.getColor(R.color.debug_color, null))
            } else {
                text = resources.getString(
                    R.string.version_number,
                    BuildConfig.VERSION_NAME
                )
            }
        }

        when(val ipAddress = getIpAddress()) {
            is String -> {
                textViewConnection.setText(R.string.server_running)
                textViewServerAddress.apply {
                    visibility = View.VISIBLE
                    text = resources.getString(
                        R.string.server_address,
                        ipAddress,
                        PipUpService.PIPUP_SERVER_PORT
                    )
                }
            }
            else -> {
                textViewConnection.setText(R.string.no_network_connection)
                textViewServerAddress.visibility = View.INVISIBLE
            }
        }


        val serviceIntent = Intent(this, PipUpService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun askPermission() {

        val intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivityForResult(intent, ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE) {
            if (!Settings.canDrawOverlays(this)) {
                askPermission()
            }
        }
    }


}
