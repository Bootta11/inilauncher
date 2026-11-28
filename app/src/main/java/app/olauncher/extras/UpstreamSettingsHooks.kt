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

    /** This fork's privacy policy (docs/privacy.md, served by GitHub Pages), also linked from the Play listing. */
    const val URL_PRIVACY = "https://bootta11.github.io/inilauncher/privacy"

    fun apply(fragment: androidx.fragment.app.Fragment, binding: FragmentSettingsBinding) {
        binding.moreFeatures.setText(R.string.extras_settings_row)
        binding.aboutOlauncher.setText(R.string.about_inilauncher)
        // Upstream's Play Store rate/share links and its developer's social link don't apply to this
        // fork; each is reused below or stays hidden. Its footer note becomes the version line.
        listOf(binding.share, binding.rate, binding.twitter)
            .forEach { it.isVisible = false }
        // Upstream's footer "GitHub" link opened Olauncher's repo
        binding.github.setOnClickListener { it.context.openUrl(URL_SOURCE) }
        bindUpdateCheck(binding)
        bindWhatsNew(binding)
        bindFeedback(binding)
        bindPrivacy(binding)
        bindVersion(binding)
        bindIcons(binding)
        app.olauncher.extras.settingssearch.SettingsSearch.attach(fragment, binding)
    }

    /**
     * Upstream's hidden "Rate" link becomes "Check for updates" (direct build only). A newer
     * version shows the update card; otherwise a toast says it's the latest or the check failed.
     */
    private fun bindUpdateCheck(binding: FragmentSettingsBinding) {
        if (!ApkUpdates.supported || app.olauncher.extras.showcase.Showcase.inProcess) return
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

    /** Upstream's hidden "Share" link becomes "What's new": this version's notes again (both builds). */
    private fun bindWhatsNew(binding: FragmentSettingsBinding) {
        val link = binding.share
        link.setText(R.string.whats_new)
        link.isVisible = true
        link.setOnClickListener {
            val context = link.context
            val notes = app.olauncher.extras.whatsnew.WhatsNew.load(context)
            app.olauncher.extras.whatsnew.WhatsNewDialog.show(context,
                app.olauncher.extras.whatsnew.WhatsNew.latest(notes, BuildConfig.VERSION_CODE.toLong()))
        }
    }

    /**
     * Upstream's hidden X/Twitter link becomes "Feedback": a bug report, a feature idea or an email.
     * Four links don't fit the footer row, so it moves into the first card, under About.
     */
    private fun bindFeedback(binding: FragmentSettingsBinding) {
        val link = binding.twitter
        moveUnder(link, binding.aboutOlauncher)
        link.setText(R.string.feedback)
        link.setOnClickListener { app.olauncher.extras.feedback.FeedbackLinks.showMenu(link) }
    }

    /** Upstream's privacy link opens this fork's policy instead. It joins About and Feedback in the first card. */
    private fun bindPrivacy(binding: FragmentSettingsBinding) {
        val link = binding.privacy
        moveUnder(link, binding.twitter)
        link.setText(R.string.privacy)
        link.setOnClickListener { it.context.openUrl(URL_PRIVACY) }
    }

    /** Moves a footer [link] into [above]'s card, right under it, styled like it. */
    private fun moveUnder(link: android.widget.TextView, above: android.widget.TextView) {
        (link.parent as? android.view.ViewGroup)?.removeView(link)
        val card = above.parent as android.view.ViewGroup
        link.setPadding(above.paddingLeft, above.paddingTop, above.paddingRight, above.paddingBottom)
        link.gravity = above.gravity
        link.maxLines = Int.MAX_VALUE
        card.addView(link, card.indexOfChild(above) + 1,
            android.widget.LinearLayout.LayoutParams(above.layoutParams as android.view.ViewGroup.MarginLayoutParams))
        link.isVisible = true
    }

    /**
     * Upstream's footer note becomes the installed version ("Inilauncher 0.1.2 (102) · 1e5c898"),
     * faded like a caption. A long press copies it, e.g. for a bug report.
     */
    private fun bindVersion(binding: FragmentSettingsBinding) {
        val footer = binding.footer
        val context = footer.context
        val line = context.getString(R.string.settings_version, app.olauncher.versionLine)
        footer.text = line
        // Faded through the text colour: the settings search fades it back in fully opaque
        footer.setTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(footer.currentTextColor, 128))
        footer.isVisible = true
        footer.setOnLongClickListener {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(line, line))
            // Android 13+ shows its own "Copied" confirmation
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU)
                context.showToast(R.string.version_copied)
            true
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
