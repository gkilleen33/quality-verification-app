package com.qualityverifier.ui.home

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.ChairAlt
import androidx.compose.material.icons.filled.DoorSliding
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TableBar
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.R
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.UpdatePrompt
import com.qualityverifier.ui.appContainer
import com.qualityverifier.ui.rememberAuthLabels
import com.qualityverifier.ui.rememberUpdateLabels
import com.qualityverifier.ui.shop.OwnerPasswordDialog
import com.qualityverifier.ui.update.UpdateBanner

/**
 * A shop's tablet's start screen (row 12), kept as v0.7.0 drew it: the wordmark, the question,
 * the grid of furniture with their photos, and the owner's row behind the account's password.
 * Kagua v2's Home (v0.7.1, row 57) is a personal account's; a tablet keeps working exactly as
 * it did (the v0.7.1 build prompt).
 */
@Composable
fun ShopHomeScreen(
    contentPadding: PaddingValues,
    onItemChosen: (ItemType) -> Unit,
    /** The owner typed the right password. */
    onOwnerUnlocked: () -> Unit,
) {
    val container = appContainer()
    var askingOwner by remember { mutableStateOf(false) }
    if (askingOwner) {
        OwnerPasswordDialog(
            labels = rememberAuthLabels(),
            check = container.account::checkPassword,
            onOpened = {
                askingOwner = false
                onOwnerUnlocked()
            },
            onDismiss = { askingOwner = false },
        )
    }
    // Built for what it does once per launch, pruning abandoned photos, as v0.7.0's Home did.
    // A tablet's never syncs (HomeViewModel's shopMode).
    viewModel<HomeViewModel>(factory = HomeViewModel.factory(container, shopMode = true))
    val update by container.appVersionCheck.state.collectAsState()
    val updateLabels = rememberUpdateLabels()

    val context = LocalContext.current
    // Resolved once per composition: the set of drawables cannot change at runtime.
    val artwork = remember(context) {
        ItemType.homeChoices.associateWith { context.itemDrawableOrNull(it) }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Above everything, so it is seen; dismissible, so it is not in the way.
        if (update.prompt == UpdatePrompt.AVAILABLE) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                UpdateBanner(
                    labels = updateLabels,
                    downloadUrl = update.downloadUrl,
                    onDismiss = container.appVersionCheck::dismissBanner,
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(
                    text = stringResource(R.string.app_name).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Karibu. What are you looking at today?",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        items(ItemType.homeChoices, key = { it.id }) { itemType ->
            ItemCard(
                itemType = itemType,
                imageRes = artwork[itemType],
                onClick = { onItemChosen(itemType) },
            )
        }

        // A shop's tablet shows no earlier customer's assessment, not even a count.
        item(span = { GridItemSpan(maxLineSpan) }) {
            OwnerRow(label = rememberAuthLabels().ownerControls, onClick = { askingOwner = true })
        }
    }
}

/** The way into the account on a shop's tablet, quieter than the grid a customer uses. */
@Composable
private fun OwnerRow(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(56.dp),
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ItemCard(
    itemType: ItemType,
    @DrawableRes imageRes: Int?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (imageRes != null) {
                    Image(
                        painter = painterResource(imageRes),
                        contentDescription = itemType.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // No photo supplied yet: a neutral placeholder rather than a broken-image box.
                    Icon(
                        imageVector = itemType.placeholderIcon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp, horizontal = 8.dp),
            ) {
                Text(
                    text = itemType.homeLabel,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Rendered even when absent, so the row keeps its height.
                Text(
                    text = itemType.swahiliName.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    minLines = 1,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Distinct glyph per category, so the grid is scannable before real photos exist. */
private fun ItemType.placeholderIcon(): ImageVector = when (this) {
    ItemType.WOODEN_TABLE -> Icons.Filled.TableRestaurant
    ItemType.WOODEN_CHAIR -> Icons.Filled.ChairAlt
    ItemType.WOODEN_STOOL -> Icons.Filled.TableBar
    ItemType.WOODEN_BED -> Icons.Filled.Bed
    ItemType.WOODEN_CABINET -> Icons.Filled.DoorSliding
    ItemType.UPHOLSTERED_CHAIR -> Icons.Filled.Chair
    ItemType.UPHOLSTERED_SOFA -> Icons.Filled.Weekend
    ItemType.OTHER -> Icons.Filled.Category
}

/** `res/drawable/item_<slug>` by name, or null when the photo has not been added yet. */
@SuppressLint("DiscouragedApi")
private fun Context.itemDrawableOrNull(itemType: ItemType): Int? =
    resources.getIdentifier(itemType.drawableName, "drawable", packageName)
        .takeIf { it != 0 }
