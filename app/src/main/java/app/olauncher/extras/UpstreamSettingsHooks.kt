package app.olauncher.extras

import android.content.Context
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.databinding.FragmentSettingsBinding
import app.olauncher.helper.createDialog
import app.olauncher.extras.iconpack.AppIcons
import app.olauncher.helper.openUrl

/**
 * Inilauncher's changes to upstream's settings screen, kept in one place so
 * SettingsFragment only needs one-line hooks.
 */
object UpstreamSettingsHooks {

    /** This fork's source (GPL), shown instead of upstream's repo. */
    private const val URL_SOURCE = "https://github.com/Bootta11/inilauncher"

    fun apply(binding: FragmentSettingsBinding) {
        binding.moreFeatures.setText(R.string.extras_settings_row)
        binding.aboutOlauncher.setText(R.string.about_inilauncher)
        // Upstream's Play Store rate/share links, its developer's social and app promo
        // links, and its privacy policy don't apply to this fork
        listOf(binding.share, binding.rate, binding.twitter, binding.privacy, binding.footer)
            .forEach { it.isVisible = false }
        // Upstream's footer "GitHub" link opened Olauncher's repo
        binding.github.setOnClickListener { it.context.openUrl(URL_SOURCE) }
        bindIcons(binding)
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
