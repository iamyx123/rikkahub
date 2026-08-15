package me.rerere.rikkahub.ui.pages.study

import android.content.ComponentName
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val iconCache = LruCache<String, ImageBitmap>(64)

private sealed interface StudyAppIconState {
    data object Loading : StudyAppIconState
    data object Missing : StudyAppIconState
    data class Ready(val bitmap: ImageBitmap) : StudyAppIconState
}

@Suppress("DEPRECATION")
@Composable
fun StudyAppIcon(
    component: ComponentName?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 56.dp.roundToPx() }
    val iconState by produceState<StudyAppIconState>(
        if (isLoading || component != null) StudyAppIconState.Loading else StudyAppIconState.Missing,
        isLoading,
        component,
        sizePx,
    ) {
        value = when {
            isLoading -> StudyAppIconState.Loading
            component == null -> StudyAppIconState.Missing
            else -> withContext(Dispatchers.IO) {
                val packageStamp = runCatching {
                    context.packageManager.getPackageInfo(component.packageName, 0).lastUpdateTime
                }.getOrDefault(0L)
                val key = "${component.flattenToShortString()}@$sizePx@$packageStamp"
                iconCache[key]?.let(StudyAppIconState::Ready) ?: runCatching {
                    context.packageManager.getActivityIcon(component)
                        .toBitmap(sizePx, sizePx)
                        .asImageBitmap()
                }.getOrNull()?.also { iconCache.put(key, it) }
                    ?.let(StudyAppIconState::Ready)
                    ?: StudyAppIconState.Missing
            }
        }
    }

    when (val state = iconState) {
        is StudyAppIconState.Ready -> Image(
            bitmap = state.bitmap,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.colorMatrix(
                ColorMatrix().apply { setToSaturation(0f) },
            ),
        )

        StudyAppIconState.Loading -> LoadingStudyAppIcon(modifier)
        StudyAppIconState.Missing -> MissingStudyAppIcon(modifier)
    }
}

@Composable
private fun MissingStudyAppIcon(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = size.minDimension * 0.055f, cap = StrokeCap.Round)
        drawRoundRect(
            color = Color.Black,
            topLeft = Offset(size.width * 0.14f, size.height * 0.14f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.72f, size.height * 0.72f),
            style = stroke,
        )
        drawLine(
            color = Color.Black,
            start = Offset(size.width * 0.30f, size.height * 0.30f),
            end = Offset(size.width * 0.70f, size.height * 0.70f),
            strokeWidth = size.minDimension * 0.055f,
            cap = StrokeCap.Round,
        )
    }
}
@Composable
private fun LoadingStudyAppIcon(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = size.minDimension * 0.045f, cap = StrokeCap.Round)
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.32f),
            topLeft = Offset(size.width * 0.14f, size.height * 0.14f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.72f, size.height * 0.72f),
            style = stroke,
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.32f),
            radius = size.minDimension * 0.07f,
            center = center,
        )
    }
}
