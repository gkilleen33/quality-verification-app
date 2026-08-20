package com.qualityverifier.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.qualityverifier.QualityVerifierApp
import com.qualityverifier.di.AppContainer

/** The single dependency lookup used by every screen. */
@Composable
fun appContainer(): AppContainer = LocalContext.current.appContainer()

fun Context.appContainer(): AppContainer =
    (applicationContext as QualityVerifierApp).container
