package com.freetv.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import com.freetv.app.databinding.FragmentSettingsBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 31: the Settings tab (replaces the "Coming soon" placeholder). A vertical list of focusable card rows:
 * three switches (each writes to Prefs the moment it changes), Video quality (dialog), Channel source (opens the SourcePicker),
 * Refresh playlist now, Clear image cache and About (version name). The settings are only STORED here (Prefs, Phase 30);
 * the code that obeys them follows in a later phase.
 */
class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    private val vm: MainViewModel by activityViewModels<MainViewModel>()

    // the open quality dialog (closed in onDestroyView so it never outlives the screen)
    private var dialog: AlertDialog? = null

    // true while the image caches are being cleared (a second tap does nothing)
    private var clearing = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindSwitch(b.rowHideDead, b.swHideDead, Prefs.hideDead) { Prefs.hideDead = it }
        bindSwitch(b.rowSkipDead, b.swSkipDead, Prefs.skipDeadOnZap) { Prefs.skipDeadOnZap = it }
        bindSwitch(b.rowAutoCheck, b.swAutoCheck, Prefs.autoCheck) { Prefs.autoCheck = it }

        b.qualityValue.text = qualityNames()[Prefs.qualityCap]
        b.rowQuality.setOnClickListener { showQualityDialog() }

        b.rowSource.setOnClickListener { openSourcePicker() }
        vm.source.observe(viewLifecycleOwner) { s -> b.sourceValue.text = s.name }

        b.rowRefresh.setOnClickListener {
            vm.load(force = true)
            Toast.makeText(requireContext(), R.string.set_refreshing, Toast.LENGTH_SHORT).show()
        }

        b.rowClearCache.setOnClickListener { clearImageCache() }

        // The version name comes from the PackageManager: ask it off the main thread
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val v = withContext(Dispatchers.IO) { readVersionName(app) }
            val text = if (v.isEmpty()) app.getString(R.string.app_name) else app.getString(R.string.set_version, v)
            _b?.aboutValue?.text = text
        }
    }

    override fun onDestroyView() {
        dialog?.dismiss()
        dialog = null
        _b = null
        super.onDestroyView()
    }

    // ---------- switches ----------

    /** Shows `value`, then saves every change at once. The row is the control: a click (touch or OK on the remote) toggles the switch. */
    private fun bindSwitch(row: View, sw: SwitchCompat, value: Boolean, save: (Boolean) -> Unit) {
        sw.isChecked = value                       // before the listener is set, so showing the value does not save it again
        sw.setOnCheckedChangeListener { _, checked -> save(checked) }
        row.setOnClickListener { sw.toggle() }
    }

    // ---------- video quality ----------

    // index = Prefs.qualityCap (0 = Auto, 1 = SD max, 2 = 720p max)
    private fun qualityNames(): Array<CharSequence> = arrayOf(
        getString(R.string.set_quality_auto),
        getString(R.string.set_quality_sd),
        getString(R.string.set_quality_720)
    )

    private fun showQualityDialog() {
        if (dialog?.isShowing == true) return
        val names = qualityNames()
        dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.set_quality)
            .setSingleChoiceItems(names, Prefs.qualityCap) { d, which ->
                Prefs.qualityCap = which
                _b?.qualityValue?.text = names[which]
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- channel source ----------

    /** Opens the full-screen source picker (Phase 28). Does nothing if it is already open. */
    private fun openSourcePicker() {
        val fm = childFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(SourcePicker.TAG) != null) return
        SourcePicker().show(fm, SourcePicker.TAG)
    }

    // ---------- image cache ----------

    /** Empties Coil's memory cache and disk cache. The disk cache is touched on Dispatchers.IO only. */
    private fun clearImageCache() {
        if (clearing) return
        clearing = true
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { clearCoil(app) }
                Toast.makeText(app, R.string.set_cache_cleared, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // nothing to show: the cache is only a copy of the logos
            } finally {
                clearing = false
            }
        }
    }

    // Both caches are thread-safe; the loader is the app-wide one built by FreeTVApp.newImageLoader()
    @OptIn(ExperimentalCoilApi::class)
    private fun clearCoil(app: Context) {
        val loader = Coil.imageLoader(app)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

    // ---------- about ----------

    @Suppress("DEPRECATION")
    private fun readVersionName(app: Context): String =
        try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
}
