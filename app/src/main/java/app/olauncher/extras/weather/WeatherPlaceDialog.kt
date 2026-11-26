package app.olauncher.extras.weather

import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.databinding.DialogBaseBinding
import app.olauncher.helper.OlDialog
import app.olauncher.helper.animateChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Search a city by name (Open-Meteo geocoding) and pick one of the results. */
object WeatherPlaceDialog {

    fun show(context: Context, scope: CoroutineScope, repo: WeatherRepository, onChosen: () -> Unit) {
        val dialog = OlDialog(context)
        val binding = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
        binding.tvTitle.setText(R.string.weather_place)
        binding.ivClose.setOnClickListener { dialog.dismiss() }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val input = (LayoutInflater.from(context).inflate(R.layout.extras_folder_name_input, column, false) as EditText).apply {
            setHint(R.string.weather_place_search)
        }
        // Results slide in and the card grows smoothly when they arrive
        val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; animateChanges(changing = false) }
        column.addView(input)
        column.addView(results)
        binding.contentContainer.addView(column)
        binding.contentContainer.isVisible = true
        binding.tvAction.setText(R.string.weather_place_search)
        binding.tvAction.setOnClickListener {
            val query = input.text.toString().trim()
            if (query.length < 2) return@setOnClickListener
            scope.launch {
                val places = repo.searchPlaces(query)
                results.removeAllViews()
                if (places.isEmpty()) results.addView(row(context, context.getString(R.string.weather_place_none)))
                places.forEach { place ->
                    results.addView(row(context, listOf(place.name, place.detail).filter { it.isNotBlank() }.joinToString(", ")).apply {
                        setOnClickListener {
                            repo.setManualPlace(place)
                            dialog.dismiss()
                            onChosen()
                        }
                    })
                }
            }
        }
        dialog.setView(binding.root)
        dialog.showRespectingStatusBar()
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }

    private fun row(context: Context, value: String) = TextView(context, null, 0, R.style.TextSmall).apply {
        text = value
        val pad = (10 * context.resources.displayMetrics.density).toInt()
        setPadding(0, pad, 0, pad)
    }
}
