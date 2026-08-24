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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.ChairAlt
import androidx.compose.material.icons.filled.DoorSliding
import androidx.compose.material.icons.filled.TableBar
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.qualityverifier.ui.appContainer

/**
 * The starting point: the wordmark, and the question the whole app answers.
 *
 * The category grid lives here rather than behind a button because choosing what you are
 * looking at is the first thing that happens in a shop, and one fewer tap matters when
 * the phone is in one hand and a stool is in the other.
 */
@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onItemChosen: (ItemType) -> Unit,
    onOpenReports: () -> Unit,
) {
    val container = appContainer()
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val reportCount by viewModel.reportCount.collectAsState()

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

        item(span = { GridItemSpan(maxLineSpan) }) {
            MyReportsRow(count = reportCount, onClick = onOpenReports)
        }
    }
}

@Composable
private fun MyReportsRow(count: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (count == 0) "My reports" else "My reports  $count",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
                    // No photo supplied yet — a neutral placeholder rather than a
                    // broken-image box. Drop `item_<slug>.jpg` into res/drawable to fill it.
                    Icon(
                        imageVector = itemType.placeholderIcon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            // The Swahili line is rendered whether or not it has a term, so a card
            // without one does not sit shorter than its neighbours. Height differences
            // from a wrapping English label are absorbed by the card filling its grid
            // row instead — reserving a second title line here would leave a visible
            // gap under every one-line label.
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
                // Rendered even when absent, so the row keeps its height. A term is only
                // shown where one could be sourced — see ItemType.swahiliName.
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

/**
 * Looks up `res/drawable/item_<slug>` by name, returning null when the photo has not
 * been added yet.
 *
 * Resolving by name rather than through `R.drawable.*` is deliberate: the project
 * compiles with no item photos present, and adding one later is a pure asset drop with
 * no code change.
 */
@SuppressLint("DiscouragedApi")
private fun Context.itemDrawableOrNull(itemType: ItemType): Int? =
    resources.getIdentifier(itemType.drawableName, "drawable", packageName)
        .takeIf { it != 0 }
