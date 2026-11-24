package com.qualityverifier

import android.app.KeyguardManager
import android.os.Bundle
import android.os.PowerManager
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.qualityverifier.shop.endOnLeaving
import com.qualityverifier.ui.nav.AppNav
import com.qualityverifier.ui.shopMode
import com.qualityverifier.ui.theme.QualityVerifierTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QualityVerifierTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNav()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // On a shop's tablet (row 12) the screen is never captured: no screenshot, and no
        // picture of the last customer's result in the recent-apps list. Set on every resume,
        // because signing in is what makes an account a shop's.
        if (this.shopMode().active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        this.shopMode().idle.touched()
    }

    /** Every touch restarts the shop tablet's five idle minutes. Costs nothing elsewhere. */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        this.shopMode().idle.touched()
        return super.dispatchTouchEvent(ev)
    }

    /**
     * The app has left the screen. On a shop's tablet that ends the customer's assessment:
     * the screen going off or locking is one end, anything else (Home, another app, the
     * update link) the other. Not on a rotation, which stops and restarts this activity.
     */
    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        val shop = this.shopMode()
        if (!shop.active) return
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        shop.end(
            endOnLeaving(
                screenInteractive = power?.isInteractive != false,
                keyguardLocked = keyguard?.isKeyguardLocked == true,
            ),
        )
    }
}
