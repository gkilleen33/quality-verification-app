package com.qualityverifier.fundi.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.qualityverifier.di.AppContainer
import com.qualityverifier.fundi.FundiBoraApp

/**
 * The single dependency lookup used by every screen here.
 *
 * Deliberately not shared with Kagua's identical-looking one: that reads *Kagua's*
 * Application subclass, and the subclass is the one thing about the container that cannot
 * be common. A shared version would have to be told which class to cast to, which is more
 * machinery than the three lines it would save.
 */
@Composable
fun fundiContainer(): AppContainer = LocalContext.current.fundiContainer()

fun Context.fundiContainer(): AppContainer =
    (applicationContext as FundiBoraApp).container
