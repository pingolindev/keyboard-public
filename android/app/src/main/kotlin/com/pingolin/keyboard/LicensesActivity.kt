// SPDX-FileCopyrightText: 2026 Pingolin LLC
// SPDX-License-Identifier: GPL-3.0-or-later

package com.pingolin.keyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat

/** The parts the app is made of. A tap on one shows its license. */
class LicensesActivity : Activity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_licenses)
        content = findViewById(R.id.content)
        val pages = Licenses.pages(asset("index.tsv"))
        val page = pages.getOrNull(intent.getIntExtra(EXTRA_PAGE, -1))
        val title = findViewById<TextView>(R.id.title)
        title.text = page?.part ?: getString(R.string.licenses)
        ViewCompat.setAccessibilityHeading(title, true)
        if (page == null) pages.forEachIndexed(::addPart) else page.files.forEach { addText(asset(it)) }
    }

    private fun addPart(index: Int, page: LicensePage) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(8), 0, dp(8))
            background = selectableBackground()
            setOnClickListener {
                startActivity(Intent(this@LicensesActivity, LicensesActivity::class.java).putExtra(EXTRA_PAGE, index))
            }
        }
        row.addView(text(page.part, android.R.style.TextAppearance_Material_Subhead))
        row.addView(text(page.license, android.R.style.TextAppearance_Material_Body1))
        content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addText(text: String) {
        for (paragraph in Licenses.paragraphs(text)) {
            content.addView(text(paragraph, android.R.style.TextAppearance_Material_Body1).apply { setPadding(0, 0, 0, dp(10)) })
        }
    }

    private fun text(text: String, style: Int) = TextView(this).apply {
        setTextAppearance(style)
        this.text = text
    }

    private fun selectableBackground() = TypedValue().let {
        theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        getDrawable(it.resourceId)
    }

    private fun asset(name: String): String = assets.open("licenses/$name").use { it.readBytes() }.toString(Charsets.UTF_8)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Which page to show; none for the list of them. */
        const val EXTRA_PAGE = "page"
    }
}
