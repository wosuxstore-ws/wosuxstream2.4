package com.freetv.app

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import com.freetv.app.databinding.ActivityMainBinding

/**
 * Host activity. Since Phase 21 it owns the custom nav bar (view_nav.xml: Home, Browse, My List, Settings):
 * bottom on phones, top on TV. Each tab fragment is added once (first visit) with a tag and then only
 * shown / hidden, so its state is kept and switching is instant. BACK from a non-Home tab returns to Home.
 * Since Phase 24 a category grid ("See all", openBrowse(group)) can sit on the back stack above the current tab.
 */
class MainActivity : AppCompatActivity() {

    private class NavItem(val root: View, val icon: ImageView, val label: TextView)

    private lateinit var b: ActivityMainBinding
    private lateinit var items: List<NavItem>
    private var current = -1

    private val backToHome = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            selectTab(TAB_HOME)
            items[TAB_HOME].root.requestFocus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // must stay BEFORE super.onCreate (swaps Theme.Wosux.Starting for Theme.FreeTV)
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        CrashLog.showIfAny(this)

        val n = b.nav
        items = listOf(
            NavItem(n.navHome, n.navHomeIcon, n.navHomeLabel),
            NavItem(n.navBrowse, n.navBrowseIcon, n.navBrowseLabel),
            NavItem(n.navList, n.navListIcon, n.navListLabel),
            NavItem(n.navSettings, n.navSettingsIcon, n.navSettingsLabel)
        )

        // TV: the nav goes to the top (index 0 of the root LinearLayout). Phone: it stays at the bottom.
        if (isTv()) {
            val navView = n.root
            b.root.removeView(navView)
            b.root.addView(navView, 0)
        }

        for (i in items.indices) {
            items[i].root.setOnClickListener { selectTab(i) }
        }
        onBackPressedDispatcher.addCallback(this, backToHome)

        // First start: Home. After rotation the FragmentManager has already restored the fragments (with their hidden state);
        // selectTab only makes sure the saved tab is the visible one.
        val startTab = savedInstanceState?.getInt(KEY_TAB, TAB_HOME) ?: TAB_HOME
        if (savedInstanceState != null && supportFragmentManager.backStackEntryCount > 0) {
            // A category grid (openBrowse(group)) was open: it is restored on top of the hidden tab.
            // Only sync the nav state; selectTab would show the tab again underneath the grid.
            current = startTab
            backToHome.isEnabled = startTab != TAB_HOME
            updateNav()
        } else {
            selectTab(startTab)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, current)
    }

    /** Used by the "All channels" button on Home: switch to the Browse tab (no back stack, no replace). */
    fun openBrowse() {
        selectTab(TAB_BROWSE)
        items[TAB_BROWSE].root.requestFocus()
    }

    /**
     * "See all >" on a Home row (Phase 24): pushes BrowseFragment.newInstance(group) onto the back stack, above the current tab.
     * The tab below is hidden (not replaced), so BACK shows it again exactly as it was (scroll position, focus state).
     * BACK or the grid's back button pops it; a click on a nav item drops it first (see selectTab).
     */
    fun openBrowse(group: String?) {
        val fm = supportFragmentManager
        if (fm.isStateSaved || fm.backStackEntryCount > 0) return
        val below = if (current in TAGS.indices) fm.findFragmentByTag(TAGS[current]) else null
        fm.commit {
            setReorderingAllowed(true)
            if (below != null) hide(below)
            add(R.id.container, BrowseFragment.newInstance(group), TAG_GROUP)
            addToBackStack(TAG_GROUP)
        }
    }

    private fun isTv(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    private fun selectTab(tab: Int) {
        if (tab !in TAGS.indices) return
        val fm = supportFragmentManager
        // A category grid on top of the tab must go first, otherwise it would stay on screen over the tab that is selected now
        if (fm.backStackEntryCount > 0) fm.popBackStackImmediate(TAG_GROUP, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        val target = fm.findFragmentByTag(TAGS[tab])
        if (tab == current && target != null && !target.isHidden) return

        fm.commitNow {
            for (i in TAGS.indices) {
                if (i != tab) {
                    val other = fm.findFragmentByTag(TAGS[i])
                    if (other != null) hide(other)
                }
            }
            if (target == null) add(R.id.container, newTab(tab), TAGS[tab]) else show(target)
        }
        current = tab
        backToHome.isEnabled = tab != TAB_HOME
        updateNav()
    }

    private fun newTab(tab: Int): Fragment = when (tab) {
        TAB_HOME -> HomeFragment()
        TAB_BROWSE -> BrowseFragment()
        TAB_LIST -> MyListFragment()
        else -> SettingsFragment() // Settings (Phase 31)
    }

    private fun updateNav() {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val dim = ContextCompat.getColor(this, R.color.text_dim)
        for (i in items.indices) {
            val item = items[i]
            val color = if (i == current) accent else dim
            item.icon.setColorFilter(color)
            item.label.setTextColor(color)
            item.root.isSelected = i == current
        }
    }

    private companion object {
        const val TAB_HOME = 0
        const val TAB_BROWSE = 1
        const val TAB_LIST = 2
        const val KEY_TAB = "tab"
        const val TAG_GROUP = "group_browse" // fragment tag AND back stack name of the category grid
        val TAGS = arrayOf("tab_home", "tab_browse", "tab_list", "tab_settings")
    }
}
