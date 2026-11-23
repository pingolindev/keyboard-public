// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * "Open" on a downloaded language file or voice model, in the browser or a
 * file manager: adds it, then shows the setup screen.
 */
class OpenFileActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null || savedInstanceState != null) {
            finish()
            return
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(ProgressBar(context))
                addView(
                    TextView(context).apply {
                        text = getString(R.string.open_file_adding)
                        setPadding(pad, 0, 0, 0)
                    },
                )
            },
        )
        setFinishOnTouchOutside(false)
        io.execute {
            val message = KeyboardFiles.add(this, uri)
            runOnUiThread {
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(this, SetupActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                finish()
            }
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
