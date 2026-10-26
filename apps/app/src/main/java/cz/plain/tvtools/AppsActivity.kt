package cz.plain.tvtools

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Rychlé přepínání: seznam TV aplikací, OK = spustit, Info = detail aplikace v Nastavení. */
class AppsActivity : Activity() {

    private data class App(val pkg: String, val label: String, val icon: Drawable, val launch: Intent)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        val list = findViewById<LinearLayout>(R.id.list)

        loadApps().forEachIndexed { index, app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val launch = Button(this).apply {
                text = app.label
                textSize = 22f
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                setBackgroundResource(R.drawable.card_bg)
                stateListAnimator = null
                setCompoundDrawablesRelative(app.icon.also { it.setBounds(0, 0, dp(48), dp(48)) }, null, null, null)
                compoundDrawablePadding = dp(20)
                setOnClickListener { startActivity(app.launch) }
            }
            val info = Button(this).apply {
                text = getString(R.string.info)
                textSize = 20f
                isAllCaps = false
                setBackgroundResource(R.drawable.card_bg)
                stateListAnimator = null
                setOnClickListener {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                    )
                }
            }
            row.addView(launch, LinearLayout.LayoutParams(0, dp(72), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            row.addView(info, LinearLayout.LayoutParams(dp(140), dp(72)).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            if (index == 0) launch.requestFocus()
        }

        if (list.childCount == 0) {
            list.addView(TextView(this).apply { text = "Žádné aplikace"; textSize = 22f })
        }
    }

    private fun loadApps(): List<App> {
        val pm = packageManager
        val seen = mutableMapOf<String, App>()
        // Nejdřív TV (leanback) aktivity, pak běžné – každý balíček jen jednou.
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || pkg in seen) continue
                val launch = Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                seen[pkg] = App(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm), launch)
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
