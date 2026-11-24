package com.qualityverifier.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.qualityverifier.QualityVerifierApp
import com.qualityverifier.di.AppContainer
import com.qualityverifier.shop.ShopMode

/** The single dependency lookup used by every screen. */
@Composable
fun appContainer(): AppContainer = LocalContext.current.appContainer()

fun Context.appContainer(): AppContainer =
    (applicationContext as QualityVerifierApp).container

/** Kagua on a shop's tablet (row 12). Inert for a personal account. */
@Composable
fun shopMode(): ShopMode = LocalContext.current.shopMode()

fun Context.shopMode(): ShopMode = (applicationContext as QualityVerifierApp).shop
