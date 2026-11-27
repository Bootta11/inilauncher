package app.olauncher.extras

import android.content.Context
import androidx.core.view.isVisible
import app.olauncher.ApkUpdates
import app.olauncher.BuildConfig
import app.olauncher.R
import app.olauncher.UpdateCheck
import app.olauncher.databinding.FragmentSettingsBinding
import app.olauncher.helper.createDialog
import app.olauncher.extras.iconpack.AppIcons
import app.olauncher.helper.openUrl
import app.olauncher.helper.showToast

/**
 * Inilauncher's changes to upstream's settings screen, kept in one place so
 * SettingsFragment only needs one-line hooks.
 */
object UpstreamSettingsHooks {

    /** This fork's source (GPL), shown instead of upstream's repo. */
    private const val URL_SOURCE = "https://github.com/Bootta11/inilauncher"

    fun apply(fragment: androidx.fragment.app.Fragment, binding: FragmentSettingsBinding) {
        binding.moreFeatures.setText(R.string.extras_settings_row)
        binding.aboutOlauncher.setText(R.string.about_inilauncher)
        // Upstream's Play Store rate/share links, its developer's social and app promo
        // links, and its privacy policy don't apply to this fork
        listOf(binding.share, binding.rate, binding.twitter, binding.privacy, binding.footer)
            .forEach { it.isVisible = false }
        // Upstream's footer "GitHub" link opened Olauncher's repo
        binding.github.setOnClickListener { it.context.openUrl(URL_SOURCE) }
        bindUpdateCheck(binding)
        bindIcons(binding)
        app.olauncher.extras.settingssearch.SettingsSearch.attach(fragment, binding)
    }

    /**
     * Upstream's hidden "Rate" link becomes "Check for updates" (direct build only). A newer
     * version shows the update card; otherwise a toast says it's the latest or the check failed.
     */
    private fun bindUpdateCheck(binding: FragmentSettingsBinding) {
        if (!ApkUpdates.supported) return
        val link = binding.rate
        link.setText(R.string.update_check)
        link.isVisible = true
        link.setOnClickListener {
            val context = link.context.applicationContext
            link.isEnabled = false
            link.setText(R.string.update_checking)
            // Later on an earlier offer shouldn't hide the result of a check asked for by hand
            ExtrasPrefs(context).setString(ExtrasKeys.UPDATE_DISMISSED, null)
            ApkUpdates.checkNow(context) { result ->
                link.isEnabled = true
                link.setText(R.string.update_check)
                when (result) {
                    UpdateCheck.NEWER -> ApkUpdates.available.value?.let {
                        context.showToast(context.getString(R.string.update_available, it.versionName))
                    }
                    UpdateCheck.LATEST -> context.showToast(context.getString(R.string.update_latest, BuildConfig.VERSION_NAME))
                    UpdateCheck.FAILED -> context.showToast(R.string.update_check_failed)
                }
            }
        }
    }

    /** Appearance → App icons: off, system, monochrome, grayscale, letter dots or an icon pack. */
    private fun bindIcons(binding: FragmentSettingsBinding) {
        val value = binding.appIcons
        value.text = AppIcons.label(value.context)
        val open = { _: android.view.View ->
            val options = AppIcons.options(value.context)
            value.showChoices(options.map { it.second }) { i ->
                AppIcons.setMode(value.context, options[i].first)
                value.text = AppIcons.label(value.context)
            }
        }
        value.setOnClickListener(open)
        binding.appIconsLabel.setOnClickListener(open)
    }

    fun showAbout(context: Context) {
        context.createDialog(
            title = R.string.about_inilauncher_title,
            action = R.string.okay,
            message = R.string.about_inilauncher_message,
            neutral = R.string.source_code,
            onNeutral = { context.openUrl(URL_SOURCE) },
        ).showRespectingStatusBar()
    }
}
