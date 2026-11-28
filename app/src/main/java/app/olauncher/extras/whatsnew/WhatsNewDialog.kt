package app.olauncher.extras.whatsnew

import android.content.Context
import android.graphics.BitmapFactory
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import app.olauncher.R
import app.olauncher.databinding.ExtrasWhatsNewBinding
import app.olauncher.databinding.ExtrasWhatsNewPageBinding
import app.olauncher.helper.Motion
import app.olauncher.helper.OlDialog
import app.olauncher.helper.animateChanges
import app.olauncher.helper.animationsOn
import app.olauncher.helper.fadeTo
import app.olauncher.helper.fadeVisible
import app.olauncher.helper.showToast

/**
 * The What's new dialog: per version an intro page, then one page per feature (screenshot, title, a
 * line or two), newest version first. Swipe or Next; Skip, Done, Back or a tap outside close it.
 */
object WhatsNewDialog {

    private sealed interface Page { val release: WhatsNew.Release }
    private data class Intro(override val release: WhatsNew.Release) : Page
    private data class FeaturePage(override val release: WhatsNew.Release, val feature: WhatsNew.Feature) : Page

    /** Shows [releases] (newest first); a toast instead when there's nothing to show. */
    fun show(context: Context, releases: List<WhatsNew.Release>) {
        val pages = releases.flatMap { r ->
            listOfNotNull<Page>(Intro(r).takeIf { r.intro.isNotBlank() }) + r.features.map { FeaturePage(r, it) }
        }
        if (pages.isEmpty()) return context.showToast(R.string.whats_new_none)

        val dialog = OlDialog(context)
        val b = ExtrasWhatsNewBinding.inflate(LayoutInflater.from(dialog.context))
        val metrics = context.resources.displayMetrics
        val maxImage = (metrics.heightPixels * 0.45f).toInt()
        // Screenshots that match the launcher's theme (light pictures in the light theme)
        val light = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_NO
        // Picture shapes from the file headers only, so every page can be measured before any loads
        val aspects = pages.filterIsInstance<FeaturePage>().mapNotNull { p ->
            p.release.imagePath(p.feature, light)?.let { path -> aspectOf(context, path)?.let { path to it } }
        }.toMap()

        val adapter = PageAdapter(pages, aspects, maxImage, metrics.widthPixels, light)
        val pager = ViewPager2(context).apply {
            this.adapter = adapter
            offscreenPageLimit = 1 // the next page is ready before it slides in
        }
        b.whatsNewPages.addView(pager, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        b.whatsNewPages.tallest = { width ->
            val spec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            pages.maxOf { page ->
                val probe = ExtrasWhatsNewPageBinding.inflate(LayoutInflater.from(context), b.whatsNewPages, false)
                adapter.bind(probe, page, loadImage = false)
                probe.root.measure(spec, free)
                probe.root.measuredHeight
            }
        }

        val dots = pages.map {
            View(context).apply {
                setBackgroundResource(R.drawable.extras_dot)
                val size = (6 * metrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = size / 2; marginEnd = size / 2 }
                alpha = DOT_OFF
            }
        }
        dots.forEach(b.whatsNewDots::addView)
        b.whatsNewDots.isVisible = pages.size > 1
        b.whatsNewButtons.animateChanges() // Next ↔ Done and Skip hiding slide instead of jumping

        var shownVersion: String? = null
        fun render(position: Int) {
            val last = position == pages.lastIndex
            dots.forEachIndexed { i, dot -> dot.fadeTo(if (i == position) 1f else DOT_OFF) }
            b.whatsNewSkip.fadeVisible(!last)
            b.whatsNewNext.setText(if (last) R.string.whats_new_done else R.string.whats_new_next)
            val version = pages[position].release.version
            if (version != shownVersion) {
                val first = shownVersion == null
                shownVersion = version
                crossfade(b.whatsNewTitle, context.getString(R.string.whats_new_title, version), instant = first)
            }
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = render(position)
        })
        render(0)

        b.whatsNewClose.setOnClickListener { dialog.dismiss() }
        b.whatsNewSkip.setOnClickListener { dialog.dismiss() }
        b.whatsNewNext.setOnClickListener {
            val i = pager.currentItem
            if (i >= pages.lastIndex) dialog.dismiss() else pager.setCurrentItem(i + 1, context.animationsOn())
        }
        dialog.setView(b.root)
        dialog.showRespectingStatusBar()
    }

    private const val DOT_OFF = 0.3f

    /** Swaps a title's text with a quick fade (instant with animations off). */
    private fun crossfade(view: android.widget.TextView, text: String, instant: Boolean) {
        if (instant || !view.context.animationsOn()) { view.text = text; return }
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(Motion.SHORT / 2).setInterpolator(Motion.easing).withEndAction {
            view.text = text
            view.animate().alpha(1f).setDuration(Motion.SHORT / 2).setInterpolator(Motion.easing).start()
        }.start()
    }

    private fun aspectOf(context: Context, path: String): Float? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(path).use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth > 0 && o.outHeight > 0) o.outHeight.toFloat() / o.outWidth else null
    }.getOrNull()

    private class Holder(val b: ExtrasWhatsNewPageBinding) : RecyclerView.ViewHolder(b.root)

    private class PageAdapter(
        private val pages: List<Page>,
        private val aspects: Map<String, Float>,
        private val maxImage: Int,
        private val screenWidth: Int,
        private val light: Boolean,
    ) : RecyclerView.Adapter<Holder>() {

        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ExtrasWhatsNewPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = bind(holder.b, pages[position], loadImage = true)

        override fun onViewRecycled(holder: Holder) = holder.b.pageImage.setImageDrawable(null)

        fun bind(b: ExtrasWhatsNewPageBinding, page: Page, loadImage: Boolean) {
            val context = b.root.context
            // Pages are as tall as the tallest one; a text-only intro sits in the middle of that space
            b.root.gravity = if (page is Intro) android.view.Gravity.CENTER_VERTICAL else android.view.Gravity.TOP
            when (page) {
                is Intro -> {
                    b.pageImage.aspect = 0f
                    b.pageImage.isVisible = false
                    b.pageTitle.isVisible = false
                    b.pageText.text = page.release.intro
                    b.pageText.isVisible = true
                    b.pageText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                }
                is FeaturePage -> {
                    val path = page.release.imagePath(page.feature, light)
                    val aspect = path?.let { aspects[it] } ?: 0f
                    b.pageImage.aspect = aspect
                    b.pageImage.maxHeightPx = maxImage
                    b.pageImage.isVisible = aspect > 0f
                    b.pageImage.setImageDrawable(null)
                    if (loadImage && path != null && aspect > 0f) {
                        decode(context, path)?.let(b.pageImage::setImageBitmap)
                    }
                    b.pageTitle.isVisible = true
                    b.pageTitle.text = page.feature.title
                    b.pageText.text = page.feature.text
                    b.pageText.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_small))
                    b.pageText.isVisible = page.feature.text.isNotBlank()
                }
            }
        }

        /** Decodes at about screen width, so a large file doesn't take more memory than it can show. */
        private fun decode(context: Context, path: String) = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= screenWidth) sample *= 2
            val o = BitmapFactory.Options().apply { inSampleSize = sample }
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, o) }
        }.getOrNull()
    }
}
