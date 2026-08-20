package com.qualityverifier.ui.items

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qualityverifier.domain.ItemType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemSelectionScreen(
    onItemChosen: (ItemType) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // Resolved once per composition: the set of drawables cannot change at runtime.
    val artwork = remember(context) {
        ItemType.entries.associateWith { context.itemDrawableOrNull(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("What are you checking?") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(ItemType.entries, key = { it.id }) { itemType ->
                ItemCard(
                    itemType = itemType,
                    imageRes = artwork[itemType],
                    onClick = { onItemChosen(itemType) },
                )
            }
        }
    }
}

@Composable
private fun ItemCard(
    itemType: ItemType,
    @DrawableRes imageRes: Int?,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.clickable(onClick = onClick)) {
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
                        imageVector = Icons.Filled.Chair,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = itemType.displayName,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 8.dp),
            )
        }
    }
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
