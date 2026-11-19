package com.emofah.agencies.ui.components

import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import com.emofah.agencies.camera.ImageQualityMath
import com.emofah.agencies.ui.theme.emofahColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class CachedBitmapEntry(
    val imageBitmap: ImageBitmap,
    val byteSizeKb: Int,
)

/**
 * Bounded off-main-thread local image loader & LRU cache.
 * Eliminates UI-thread BitmapFactory.decodeFile jank across Compose screens.
 */
object EmofahImageLoader {
    // 20 MiB bounded cache (sizes tracked in KiB)
    private const val MAX_CACHE_KB = 20 * 1024

    private val memoryCache = object : LruCache<String, CachedBitmapEntry>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: CachedBitmapEntry): Int {
            return value.byteSizeKb.coerceAtLeast(1)
        }
    }

    suspend fun loadLocalImage(
        path: String,
        maxDimensionPx: Int = 512,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext null
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return@withContext null

        val cacheKey = "${file.absolutePath}@$maxDimensionPx@${file.lastModified()}"
        memoryCache.get(cacheKey)?.let { return@withContext it.imageBitmap }

        val decoded = runCatching {
            ImageQualityMath.decodeUprightBitmap(file, maxTargetDim = maxDimensionPx)
        }.getOrNull() ?: return@withContext null

        val byteCountKb = (decoded.allocationByteCount / 1024).coerceAtLeast(1)
        val imageBitmap = decoded.asImageBitmap()
        memoryCache.put(cacheKey, CachedBitmapEntry(imageBitmap = imageBitmap, byteSizeKb = byteCountKb))
        imageBitmap
    }
}

private sealed interface LocalImageLoadState {
    data object Loading : LocalImageLoadState
    data class Ready(val bitmap: ImageBitmap) : LocalImageLoadState
    data object Empty : LocalImageLoadState
}

@Composable
fun EmofahLocalImage(
    path: String?,
    contentDescription: String?,
    fallbackIcon: ImageVector,
    modifier: Modifier = Modifier,
    maxDimensionPx: Int = 512,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val reducedMotion = LocalReducedMotion.current
    val loadState by produceState<LocalImageLoadState>(
        initialValue = if (path.isNullOrBlank()) LocalImageLoadState.Empty else LocalImageLoadState.Loading,
        key1 = path,
        key2 = maxDimensionPx,
    ) {
        if (path.isNullOrBlank()) {
            value = LocalImageLoadState.Empty
            return@produceState
        }
        val bitmap = EmofahImageLoader.loadLocalImage(path, maxDimensionPx = maxDimensionPx)
        value = if (bitmap != null) {
            LocalImageLoadState.Ready(bitmap)
        } else {
            LocalImageLoadState.Empty
        }
    }

    val colors = MaterialTheme.emofahColors
    Box(
        modifier = modifier.background(colors.surfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = loadState,
            animationSpec = tween(if (reducedMotion) 0 else EmofahMotion.QUICK_MS),
            label = "localImageFade",
        ) { state ->
            when (state) {
                is LocalImageLoadState.Ready -> {
                    Image(
                        bitmap = state.bitmap,
                        contentDescription = contentDescription,
                        contentScale = contentScale,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                is LocalImageLoadState.Loading -> {
                    EmofahSkeletonBox(modifier = Modifier.fillMaxSize())
                }

                is LocalImageLoadState.Empty -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = fallbackIcon,
                            contentDescription = null,
                            tint = colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}
