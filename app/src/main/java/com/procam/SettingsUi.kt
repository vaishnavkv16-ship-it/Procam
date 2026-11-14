package com.procam

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object SettingsUi {
    fun show(a: Activity, prefs: Prefs, accent: Int, onClose: () -> Unit) {
        val d = Dialog(a, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val den = a.resources.displayMetrics.density
        fun px(i: Int) = (i * den).toInt()

        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF111111.toInt()) }
        val top = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(8), px(28), px(8), px(8)) }
        top.addView(TextView(a).apply {
            text = "←"; textSize = 26f; setTextColor(Color.WHITE); setPadding(px(12), px(4), px(16), px(4))
            setOnClickListener { d.dismiss() }
        })
        top.addView(TextView(a).apply { text = "Settings"; textSize = 22f; setTextColor(Color.WHITE) })
        root.addView(top)

        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), 0, px(16), px(40)) }
        var section = ""
        for (s in Cfg.list) {
            if (s.section != section) {
                section = s.section
                list.addView(TextView(a).apply {
                    text = section.uppercase(); textSize = 12f; setTextColor(accent)
                    setPadding(0, px(22), 0, px(6))
                })
            }
            val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(14), 0, px(14)) }
            val title = TextView(a).apply { text = s.title; textSize = 16f; setTextColor(Color.WHITE) }
            val value = TextView(a).apply { text = prefs.value(s.key); textSize = 16f; setTextColor(accent) }
            row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(value)
            row.setOnClickListener {
                val n = (prefs.idx(s.key) + 1) % s.options.size
                prefs.set(s.key, n)
                value.text = s.options[n]
            }
            list.addView(row)
        }
        list.addView(TextView(a).apply {
            text = "Reset all settings"; textSize = 16f; setTextColor(0xFFFF6B6B.toInt())
            setPadding(0, px(30), 0, px(10))
            setOnClickListener { prefs.reset(); d.dismiss() }
        })
        list.addView(TextView(a).apply {
            text = "Tap a setting to change it. Changes apply when you go back."
            textSize = 12f; setTextColor(0x88FFFFFF.toInt()); setPadding(0, px(10), 0, 0)
        })
        root.addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))

        d.setOnDismissListener { onClose() }
        d.setContentView(root)
        d.show()
    }
}
