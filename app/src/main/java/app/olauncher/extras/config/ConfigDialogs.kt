package app.olauncher.extras.config

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.databinding.DialogBaseBinding
import app.olauncher.helper.OlDialog
import app.olauncher.helper.animateChanges
import app.olauncher.helper.getColorFromAttr

/** The export and import dialogs of Extras → Backup & sharing. */
object ConfigDialogs {

    /** Asks what to include and a name, then hands the file text to [onSave] or [onShare]. */
    fun export(context: Context, onSave: (String, String) -> Unit, onShare: (String, String) -> Unit) {
        val dialog = OlDialog(context)
        val b = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
        b.tvTitle.setText(R.string.config_export)
        val body = column(context)
        val name = input(context, R.string.config_name_hint, 40)
        val description = input(context, R.string.config_description_hint, 200, multiLine = true)
        body.addView(name)
        body.addView(description)
        val boxes = mapOf(
            Part.LOOK to check(context, R.string.config_part_look, true),
            Part.SETTINGS to check(context, R.string.config_part_settings, true),
            Part.LAYOUT to check(context, R.string.config_part_layout, false),
        )
        boxes.values.forEach(body::addView)
        b.contentContainer.addView(body)
        b.contentContainer.isVisible = true
        b.tvNeutral.setText(R.string.config_share)
        b.tvNeutral.isVisible = true
        b.tvAction.setText(R.string.config_save)

        fun parts() = boxes.filterValues { it.isChecked }.keys
        fun refresh() {
            val any = parts().isNotEmpty()
            b.tvAction.isEnabled = any
            b.tvNeutral.isEnabled = any
            b.tvAction.alpha = if (any) 1f else 0.4f
            b.tvNeutral.alpha = if (any) 1f else 0.4f
        }
        boxes.values.forEach { it.setOnCheckedChangeListener { _, _ -> refresh() } }
        refresh()
        fun build(): Pair<String, String> {
            val meta = ConfigMeta(name.text.toString(), description.text.toString())
            val text = LauncherConfig.export(context, parts(), meta)
            return text to fileName(meta.name)
        }
        b.ivClose.setOnClickListener { dialog.dismiss() }
        b.tvAction.setOnClickListener { val (text, file) = build(); dialog.dismiss(); onSave(text, file) }
        b.tvNeutral.setOnClickListener { val (text, file) = build(); dialog.dismiss(); onShare(text, file) }
        dialog.setView(b.root)
        dialog.showRespectingStatusBar()
        // Dialogs block the keyboard by default; the name fields need it (as in HomeNote)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }

    /** A safe file name from the configuration's name. */
    fun fileName(name: String): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30)
        return "inilauncher-" + slug.ifEmpty { "config" } + ".json"
    }

    /**
     * The import preview: the file's name and description (as plain text), which parts to apply,
     * every change, and what's skipped. [plan] rebuilds the plan for the ticked parts; [onImport]
     * applies the shown one.
     */
    fun preview(context: Context, config: ParsedConfig, plan: (Set<Part>) -> ImportPlan, onImport: (ImportPlan) -> Unit) {
        val dialog = OlDialog(context)
        val b = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
        b.tvTitle.setText(R.string.config_import)
        val body = column(context)
        body.animateChanges() // the change list grows and shrinks with the ticks
        if (config.meta.name.isNotEmpty() || config.meta.description.isNotEmpty()) {
            body.addView(text(context, context.getString(R.string.config_from_file), faded = true))
            if (config.meta.name.isNotEmpty()) body.addView(text(context, config.meta.name, bold = true))
            if (config.meta.description.isNotEmpty()) body.addView(text(context, config.meta.description))
        }
        val boxes = linkedMapOf<Part, CheckBox>()
        for (part in listOf(Part.LOOK, Part.SETTINGS, Part.LAYOUT)) {
            if (part !in config.parts) continue
            val label = when (part) { Part.LOOK -> R.string.config_part_look; Part.SETTINGS -> R.string.config_part_settings; Part.LAYOUT -> R.string.config_part_layout_import }
            boxes[part] = check(context, label, part != Part.LAYOUT).also(body::addView)
        }
        val changes = text(context, "")
        body.addView(changes)
        b.contentContainer.addView(body)
        b.contentContainer.isVisible = true
        b.tvAction.setText(R.string.config_import_action)

        var current = plan(boxes.filterValues { it.isChecked }.keys)
        fun render() {
            current = plan(boxes.filterValues { it.isChecked }.keys)
            changes.text = describe(context, current)
            val any = !current.isEmpty
            b.tvAction.isEnabled = any
            b.tvAction.alpha = if (any) 1f else 0.4f
        }
        boxes.values.forEach { it.setOnCheckedChangeListener { _, _ -> render() } }
        render()
        b.ivClose.setOnClickListener { dialog.dismiss() }
        b.tvAction.setOnClickListener { dialog.dismiss(); onImport(current) }
        dialog.setView(b.root)
        dialog.showRespectingStatusBar()
    }

    /** The change list as plain text: no links, no markup from the file. */
    private fun describe(context: Context, plan: ImportPlan): String = buildString {
        val lines = plan.changes.map { c ->
            val online = if (c.online) "  ⚠ " + context.getString(R.string.config_online) else ""
            "${label(context, c.key)}: ${c.from} → ${c.to}$online"
        } + plan.layoutSummary
        append(context.getString(R.string.config_changes)).append('\n')
        if (lines.isEmpty()) append(context.getString(R.string.config_no_changes)) else lines.forEach { append("• ").append(it).append('\n') }
        if (plan.skips.isNotEmpty()) {
            append('\n').append(context.getString(R.string.config_skipped)).append(" (").append(plan.skips.size).append(")\n")
            plan.skips.take(30).forEach { append("• ").append(it.path).append(": ").append(it.reason).append('\n') }
            if (plan.skips.size > 30) append("…\n")
        }
    }.trimEnd()

    private val LABELS = mapOf(
        "theme" to R.string.theme_mode, "textSize" to R.string.text_size, "boldFont" to R.string.bold_font,
        "font" to R.string.font, "alignment" to R.string.home_layout_alignment, "dateTime" to R.string.show_date_time,
        "statusBar" to R.string.notification_bar, "appIcons" to R.string.icons, "blur" to R.string.background_blur,
        "eink" to R.string.force_eink, "gestureMotion" to R.string.gesture_motion, "sublist.button" to R.string.sublist_button,
        "sublist.style" to R.string.sublist_style, "notifications.dotStyle" to R.string.notification_dot_style,
        "keyboard.autoShow" to R.string.auto_show_keyboard, "gestures.swipeLeft" to R.string.swipe_left_app,
        "gestures.swipeRight" to R.string.swipe_right_app, "note.enabled" to R.string.note_on_home,
        "weather.fahrenheit" to R.string.weather_units, "events.enabled" to R.string.events, "widgets.enabled" to R.string.widgets,
        "notifications.enabled" to R.string.notifications_on_home, "notifications.line" to R.string.notifications_line,
        "notifications.dots" to R.string.notification_dots, "notifications.folders" to R.string.notification_dots_folders,
        "media.enabled" to R.string.media_controls, "sublist.enabled" to R.string.sublist, "sublist.free" to R.string.sublist_free,
        "sublist.auto" to R.string.sublist_auto, "search.contacts" to R.string.search_contacts,
        "search.calculator" to R.string.search_calculator, "search.web" to R.string.search_web,
        "search.shortcuts" to R.string.search_shortcuts, "search.holdToggle" to R.string.search_hold_toggle,
        "search.holdActions" to R.string.search_hold_actions, "az.enabled" to R.string.az_scroller, "az.side" to R.string.az_side,
        "az.height" to R.string.az_height, "suggested.enabled" to R.string.suggested_apps, "sortByUsage" to R.string.sort_by_usage,
    )

    fun label(context: Context, key: String) = LABELS[key]?.let(context::getString) ?: key

    private fun column(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 12)
            marginEnd = dp(context, 8)
        }
    }

    private fun text(context: Context, value: String, bold: Boolean = false, faded: Boolean = false) =
        TextView(context, null, 0, R.style.TextSmall).apply {
            text = value
            textSize = 15f
            setLineSpacing(dp(context, 2).toFloat(), 1f)
            setTextColor(context.getColorFromAttr(R.attr.primaryColor))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (faded) alpha = 0.6f
            setPadding(0, dp(context, 6), 0, dp(context, 6))
        }

    private fun input(context: Context, @StringRes hint: Int, max: Int, multiLine: Boolean = false) =
        // Default EditText style (focusable, editable); only the look is changed
        EditText(context).apply {
            background = null
            setHint(hint)
            textSize = 16f
            setTextColor(context.getColorFromAttr(R.attr.primaryColor))
            setHintTextColor(context.getColorFromAttr(R.attr.primaryColorTrans50))
            filters = arrayOf(InputFilter.LengthFilter(max), InputFilter { source, start, end, _, _, _ ->
                // Same rule as the schema: no control or direction characters
                if ((start until end).any { S.isUnsafeChar(source[it]) }) "" else null
            })
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                (if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            setPadding(0, dp(context, 10), 0, dp(context, 10))
        }

    private fun check(context: Context, @StringRes label: Int, checked: Boolean) = CheckBox(context).apply {
        setText(label)
        textSize = 16f
        isChecked = checked
        val color = context.getColorFromAttr(R.attr.primaryColor)
        setTextColor(color)
        buttonTintList = ColorStateList.valueOf(color)
        setPadding(dp(context, 4), dp(context, 8), 0, dp(context, 8))
    }

    private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
