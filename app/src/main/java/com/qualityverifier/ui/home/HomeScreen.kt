package com.qualityverifier.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.BuildConfig
import com.qualityverifier.R
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.UpdatePrompt
import com.qualityverifier.text.KaguaScreenLabels
import com.qualityverifier.ui.appContainer
import com.qualityverifier.ui.parts.CardRow
import com.qualityverifier.ui.parts.Parts
import com.qualityverifier.ui.parts.RowCard
import com.qualityverifier.ui.parts.StatusBox
import com.qualityverifier.ui.parts.StatusKind
import com.qualityverifier.ui.parts.Wordmark
import com.qualityverifier.ui.parts.lineStyle
import com.qualityverifier.ui.rememberAuthLabels
import com.qualityverifier.ui.rememberKaguaLabels
import com.qualityverifier.ui.rememberUpdateLabels
import com.qualityverifier.ui.settings.AccountMenu
import com.qualityverifier.ui.settings.AccountMenuWords
import com.qualityverifier.ui.shop.OwnerPasswordDialog
import com.qualityverifier.ui.update.UpdateBanner

/**
 * Kagua's Home (v0.7.1, row 57; Kagua v2 mockup): the wordmark and tagline, the gear that opens
 * the Settings menu, "Karibu. What are you looking at today?", and each kind of furniture as a
 * row in one card, English with its Kiswahili under it. A tap starts an assessment of that kind.
 *
 * No "My reports" card: the tab at the bottom opens it (P25). When the server cannot be reached,
 * a red box says so and stays until it can (P19).
 *
 * A shop's tablet (row 12) does not use this screen: it keeps v0.7.0's grid ([ShopHomeScreen]).
 * [shopMode] still holds this screen to the tablet's rules (no gear, no sync, the owner's row)
 * should anything draw it there.
 */
@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onItemChosen: (ItemType) -> Unit,
    shopMode: Boolean = false,
    /** The owner typed the right password (shop tablet only). */
    onOwnerUnlocked: () -> Unit = {},
    onChangePassword: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onAccountDeleted: () -> Unit = {},
) {
    val container = appContainer()
    val words = rememberKaguaLabels()
    val authLabels = rememberAuthLabels()
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container, shopMode))
    val offline by viewModel.offline.collectAsState()
    val update by container.appVersionCheck.state.collectAsState()
    val updateLabels = rememberUpdateLabels()
    var askingOwner by remember { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val chrome = HomeChrome.of(shopMode)

    if (askingOwner) {
        OwnerPasswordDialog(
            labels = authLabels,
            check = container.account::checkPassword,
            onOpened = {
                askingOwner = false
                onOwnerUnlocked()
            },
            onDismiss = { askingOwner = false },
        )
    }

    if (menuOpen && chrome.gear) {
        AccountMenu(
            container = container,
            labels = authLabels,
            words = accountMenuWords(words),
            onDismiss = { menuOpen = false },
            onChangePassword = {
                menuOpen = false
                onChangePassword()
            },
            onSignOut = onSignOut,
            onAccountDeleted = onAccountDeleted,
            // What the Profile screen said under "Account", and its version line.
            foot = {
                Text(authLabels.humanReviewNotice, style = lineStyle, color = Parts.Muted)
                Spacer(Modifier.height(8.dp))
                Text(words.version(BuildConfig.VERSION_NAME), style = lineStyle, color = Parts.Muted)
            },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Parts.Page),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 18.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Wordmark(
                    stringResource(R.string.app_name).uppercase(),
                    Modifier.weight(1f),
                    tagline = stringResource(R.string.app_tagline),
                )
                if (chrome.gear) {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = words.settings, tint = Parts.Brand, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }
        // Above everything else, so it is seen; dismissible, so it is not in the way.
        if (update.prompt == UpdatePrompt.AVAILABLE) {
            item { UpdateBanner(labels = updateLabels, downloadUrl = update.downloadUrl, onDismiss = container.appVersionCheck::dismissBanner) }
        }
        if (offline) {
            item {
                StatusBox(StatusKind.FAILURE, words.offlineTitle, words.offlineLine, words.tryAgain, onButton = viewModel::refresh)
            }
        }
        item {
            Text(words.homeQuestion, color = Parts.Ink, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
        }
        item {
            RowCard {
                ItemType.homeChoices.forEachIndexed { i, itemType ->
                    CardRow(
                        itemType.homeLabel,
                        line = itemType.swahiliName,
                        onClick = { onItemChosen(itemType) },
                        divider = i > 0,
                    )
                }
            }
        }
        // A shop's tablet shows no earlier customer's assessment, not even a count.
        if (chrome.ownerControls) {
            item {
                TextButton(onClick = { askingOwner = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(authLabels.ownerControls)
                }
            }
        }
    }
}

/** The Settings menu's words in Kagua (row 57): the phone's language. */
internal fun accountMenuWords(words: KaguaScreenLabels) = AccountMenuWords(
    title = words.settings,
    changePassword = words.changePassword,
    location = words.recordGps,
    locationLine = words.recordGpsLine,
    on = words.on,
    off = words.off,
    signOut = words.signOut,
    deleteAccount = words.deleteAccount,
    close = words.close,
)
