package com.mg.camera

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * M&G Cam OEM bridge.
 *
 * We intentionally do not re-sign/replace the Transsion system camera. Doing that would
 * break the OEM signature/privilege chain that unlocks Tecno/MediaTek high-resolution,
 * remosaic, HDR and night processing. Instead this activity keeps M&G as the launcher/task
 * identity and starts the factory camera package in the same task.
 */
class OemBridgeActivity : AppCompatActivity() {

    private var launchedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.BLACK)
        }

        val title = TextView(this).apply {
            text = "M&G CAM"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 18)
        }

        val subtitle = TextView(this).apply {
            text = "OEM CAMERA ENGINE\nTecno / MediaTek processing"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 42)
        }

        val open = Button(this).apply {
            text = "OEM KAMERAYI AÇ"
            isAllCaps = false
            setOnClickListener { launchFactoryCamera() }
        }

        val lab = Button(this).apply {
            text = "M&G LAB"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@OemBridgeActivity, MainActivity::class.java))
            }
        }

        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            subtitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            open,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            lab,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
        )

        setContentView(root)

        if (savedInstanceState == null) {
            launchedOnce = true
            launchFactoryCamera()
        }
    }

    override fun onResume() {
        super.onResume()
        // Do not loop when the user presses Back from the OEM camera.
        // The bridge screen remains available with explicit reopen/lab buttons.
    }

    private fun launchFactoryCamera() {
        val explicitLauncher = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(
                "com.transsion.camera",
                "com.android.camera.CameraLauncher"
            )
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        try {
            startActivity(explicitLauncher)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }

        val transsionCamera = Intent("com.transsion.camera.action.START_SPECIFY_MODE").apply {
            setPackage("com.transsion.camera")
        }

        try {
            startActivity(transsionCamera)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }

        try {
            startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        } catch (t: Throwable) {
            Toast.makeText(
                this,
                "OEM kamera açılamadı: ${t.message ?: t.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
