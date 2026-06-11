package pl.einarthesad.opendrone

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var imageView: ImageView
    private lateinit var statusView: TextView
    private lateinit var recButton: Button
    private lateinit var shotButton: Button

    private var receiver: DroneVideoReceiver? = null
    private var recorder: RecorderController? = null

    @Volatile
    private var latestJpeg: ByteArray? = null

    private val decoding = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        keepScreenOn()
        bindToWifiIfPossible()

        buildUi()
        fullscreen()

        recorder = RecorderController(
            context = this,
            onStatus = { text ->
                runOnUiThread {
                    statusView.text = text
                }
            }
        )

        receiver = DroneVideoReceiver(
            onFrame = { jpg ->
                latestJpeg = jpg
                recorder?.addFrame(jpg)
                decodeForPreview(jpg)
            },
            onStatus = { text ->
                runOnUiThread {
                    statusView.text = text
                }
            }
        )

        receiver?.start()
    }

    override fun onDestroy() {
        receiver?.stop()
        recorder?.discard()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            fullscreen()
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        imageView = ImageView(this)
        imageView.setBackgroundColor(Color.BLACK)
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        imageView.adjustViewBounds = false

        root.addView(
            imageView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusView = TextView(this)
        statusView.setTextColor(Color.WHITE)
        statusView.setBackgroundColor(0x66000000)
        statusView.textSize = 14f
        statusView.text = "Starting..."
        statusView.setPadding(dp(10), dp(6), dp(10), dp(6))

        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        statusParams.gravity = Gravity.TOP or Gravity.START
        statusParams.setMargins(dp(12), dp(12), dp(12), dp(12))

        root.addView(statusView, statusParams)

        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.VERTICAL
        controls.gravity = Gravity.CENTER
        controls.setPadding(dp(8), dp(8), dp(8), dp(8))
        controls.setBackgroundColor(0x44000000)

        recButton = Button(this)
        recButton.text = "REC"
        recButton.textSize = 16f
        recButton.setOnClickListener {
            toggleRecording()
        }

        shotButton = Button(this)
        shotButton.text = "SHOT"
        shotButton.textSize = 16f
        shotButton.setOnClickListener {
            takeSnapshot()
        }

        val buttonParams = LinearLayout.LayoutParams(
            dp(116),
            dp(58)
        )
        buttonParams.setMargins(0, dp(6), 0, dp(6))

        controls.addView(recButton, buttonParams)
        controls.addView(shotButton, buttonParams)

        val controlsParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        controlsParams.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        controlsParams.setMargins(dp(8), dp(8), dp(20), dp(8))

        root.addView(controls, controlsParams)

        setContentView(root)
    }

    private fun toggleRecording() {
        val rec = recorder ?: return

        if (rec.isRecording) {
            recButton.text = "REC"
            rec.stopAndSave()
        } else {
            rec.start(latestJpeg)

            if (rec.isRecording) {
                recButton.text = "STOP"
                statusView.text = "Recording..."
            }
        }
    }

    private fun takeSnapshot() {
        val jpg = latestJpeg

        if (jpg == null) {
            statusView.text = "No frame yet"
            return
        }

        thread(name = "SaveSnapshot") {
            try {
                val name = MediaStoreSaver.saveSnapshot(this, jpg)

                runOnUiThread {
                    statusView.text = "Saved $name"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = "Snapshot error: ${e.message}"
                }
            }
        }
    }

    private fun decodeForPreview(jpg: ByteArray) {
        if (!decoding.compareAndSet(false, true)) {
            return
        }

        thread(name = "JpegDecode") {
            try {
                val bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size)

                if (bmp != null) {
                    runOnUiThread {
                        imageView.setImageBitmap(bmp)

                        val rec = recorder?.isRecording == true
                        val recText = if (rec) " REC" else ""
                        statusView.text = "${bmp.width}x${bmp.height}$recText"
                    }
                }
            } finally {
                decoding.set(false)
            }
        }
    }

    private fun keepScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun fullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.decorView.windowInsetsController ?: return

            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun bindToWifiIfPossible() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)

            for (network: Network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue

                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    cm.bindProcessToNetwork(network)
                    return
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
