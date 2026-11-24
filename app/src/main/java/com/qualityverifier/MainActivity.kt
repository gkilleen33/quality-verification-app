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
        // A shop's tablet (row 12) restores no saved screen except across a rotation. Android
        // saves the open assessment as the app leaves the screen, before the end that leaving
        // causes has put the start screen back; after a process death it would open the next
        // customer on the earlier one's chat. Dropped here, before anything is restored from
        // it: the navigation's back stack, every saved field, and the view models' saved state.
        val restore = savedInstanceState?.takeIf { shopMode().mayRestoreScreens() }
        super.onCreate(restore)
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

    /**
     * Every key (a hardware keyboard, the volume keys) restarts the shop tablet's five idle
     * minutes, as every touch does below. The on-screen keyboard reaches neither: its typing is
     * caught by each text field on the tablet's screens, and a dialog's touches by the dialog.
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
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

    override fun onDestroy() {
        // Whether the next onCreate is the same customer's screen turned round (see onCreate).
        shopMode().activityDestroyed(isChangingConfigurations)
        super.onDestroy()
    }
}
