package com.example.ariacastreceiver

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Bundle
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import coil.load
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var server: AriaCastServer
    private var currentArtUrl: String? = null

    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Make app full screen
        hideSystemUI()

        server = AriaCastServer(applicationContext)
        server.start()

        val rootLayout = findViewById<ConstraintLayout>(R.id.root_layout)
        val artwork = findViewById<ImageView>(R.id.artwork)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        progressBar = findViewById(R.id.progress_bar)
        currentTime = findViewById(R.id.current_time)
        totalTime = findViewById(R.id.total_time)

        // Set initial icon
        artwork.setImageResource(R.drawable.ic_ariacast)

        lifecycleScope.launch {
            server.metadata.collectLatest { meta ->
                if (meta.artworkUrl != currentArtUrl) {
                    currentArtUrl = meta.artworkUrl
                    if (meta.artworkUrl != null) {
                        artwork.load(meta.artworkUrl) {
                            crossfade(true)
                            placeholder(R.drawable.ic_ariacast)
                            error(R.drawable.ic_ariacast)
                            allowHardware(false)
                            listener(onSuccess = { _, result ->
                                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                                if (bitmap != null) {
                                    updateBackgroundFromArtwork(bitmap, rootLayout)
                                }
                            })
                        }
                    } else {
                        artwork.setImageResource(R.drawable.ic_ariacast)
                    }
                }

                title.text = meta.title ?: "AriaCast Receiver"
                artist.text = meta.artist ?: "Ready to cast"

                val durationSeconds = (meta.durationMs / 1000).toInt()
                progressBar.max = if (durationSeconds > 0) durationSeconds else 100
                totalTime.text = formatTime(meta.durationMs)

                val positionSeconds = (meta.positionMs / 1000).toInt()
                progressBar.progress = positionSeconds
                currentTime.text = formatTime(meta.positionMs)
            }
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun updateBackgroundFromArtwork(bitmap: Bitmap, rootLayout: ConstraintLayout) {
        Palette.from(bitmap).generate { palette ->
            val defaultStartColor = ContextCompat.getColor(this, R.color.default_gradient_start)
            val defaultEndColor = ContextCompat.getColor(this, R.color.default_gradient_end)

            val swatches = palette?.swatches?.sortedByDescending { it.population } ?: listOf()
            val startColor = swatches.getOrNull(0)?.rgb ?: defaultStartColor
            val endColor = swatches.getOrNull(1)?.rgb ?: palette?.getDarkVibrantColor(defaultEndColor) ?: defaultEndColor

            val newGradient = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(startColor, endColor)
            )

            val oldDrawable = rootLayout.background
            val transition = TransitionDrawable(arrayOf(oldDrawable, newGradient))
            rootLayout.background = transition
            transition.startTransition(500)

            // Adjust text and UI colors based on background luminance
            updateUIColors(startColor)
        }
    }

    private fun updateUIColors(backgroundColor: Int) {
        // Calculate luminance: 0 is black, 1 is white
        val luminance = 0.2126 * Color.red(backgroundColor) / 255 +
                        0.7152 * Color.green(backgroundColor) / 255 +
                        0.0722 * Color.blue(backgroundColor) / 255
        
        val primaryColor = if (luminance > 0.5) Color.BLACK else Color.WHITE
        val secondaryColor = if (luminance > 0.5) Color.argb(180, 0, 0, 0) else Color.argb(180, 255, 255, 255)

        title.setTextColor(primaryColor)
        artist.setTextColor(secondaryColor)
        currentTime.setTextColor(secondaryColor)
        totalTime.setTextColor(secondaryColor)
        
        // Update progress bar color
        progressBar.progressTintList = ColorStateList.valueOf(primaryColor)
        progressBar.progressBackgroundTintList = ColorStateList.valueOf(secondaryColor)
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        server.stop()
    }
}
