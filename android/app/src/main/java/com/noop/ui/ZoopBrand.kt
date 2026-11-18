package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.R

// MARK: - Zoop brand (the "Loop" logo, chosen 2026-11-17)
//
// The wordmark is "zoop" in lowercase with the two o's drawn as ONE continuous infinity loop. It ships as
// two vector layers that share one viewport: the letters (z and p) and the loop. Each layer is drawn white
// and tinted here, so the letters follow the theme's primary text colour and the loop stays the brand
// mint on any accent choice: the user's accent preference recolours chrome, never the logo. Light mode
// uses the light scheme's deeper mint so the loop keeps its contrast on warm paper.

/** The wordmark's width over its height, from the vector viewport (278.9 x 89.5). */
private const val WORDMARK_ASPECT = 278.9f / 89.5f

/** The brand mint for the active scheme, independent of the user's accent preference. */
internal val brandMint: Color get() = Palette.active.accent

/**
 * The Zoop "Loop" wordmark at [height] (the width follows the logo's proportions). Decorative: callers
 * that need it announced add their own semantics, since the app name is already the window title.
 */
@Composable
fun ZoopWordmark(
    modifier: Modifier = Modifier,
    height: Dp = 22.dp,
    letterColor: Color = Palette.textPrimary,
    loopColor: Color = brandMint,
) {
    Box(modifier = modifier.height(height).aspectRatio(WORDMARK_ASPECT)) {
        Image(
            painter = painterResource(R.drawable.zoop_wordmark_letters),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(letterColor),
            modifier = Modifier.matchParentSize(),
        )
        Image(
            painter = painterResource(R.drawable.zoop_wordmark_loop),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(loopColor),
            modifier = Modifier.matchParentSize(),
        )
    }
}

// MARK: - Zoop mark (the loop on its own)
//
// The logo's mark for spots too small or too square for the wordmark: the mint loop with a small pulse
// dot on its upper-right curve. Geometry is the brand SVG's (.superdesign/brand/zoop-mark.svg), in its
// own 232 x 112 box whose origin sits at (4, 14).

/** The loop's centre line, in the mark's source coordinates. */
private const val ZOOP_LOOP_PATH =
    "M 60,30 C 90,30 110,60 120,60 C 130,60 150,30 180,30 C 205,30 220,50 220,70 C 220,90 205,110 180,110 " +
        "C 150,110 130,80 120,80 C 110,80 90,110 60,110 C 35,110 20,90 20,70 C 20,50 35,30 60,30 Z"

/** The Zoop mark at [width] (the height follows its proportions). Decorative. */
@Composable
fun ZoopMark(
    modifier: Modifier = Modifier,
    width: Dp = 64.dp,
    loopColor: Color = brandMint,
    dotColor: Color = Palette.tipCore,
) {
    val loop = remember { PathParser().parsePathString(ZOOP_LOOP_PATH).toPath() }
    Canvas(modifier = modifier.width(width).aspectRatio(232f / 112f)) {
        val unit = size.width / 232f
        scale(scale = unit, pivot = Offset.Zero) {
            translate(left = -4f, top = -14f) {
                drawPath(
                    path = loop,
                    color = loopColor,
                    style = Stroke(width = 24f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawCircle(color = dotColor, radius = 12f, center = Offset(195f, 38f))
            }
        }
    }
}
