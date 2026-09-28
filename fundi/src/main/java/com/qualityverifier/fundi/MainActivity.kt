package com.qualityverifier.fundi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.qualityverifier.fundi.ui.FundiNav
import com.qualityverifier.ui.theme.QualityVerifierTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // The project's theme, from :design. Fundi Bora is a different app, not a
            // different brand: a maker who earns a badge here is winning buyers in Kagua,
            // and two visual identities would make that connection something the user has
            // to be told rather than something they can see.
            QualityVerifierTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    FundiNav()
                }
            }
        }
    }
}
