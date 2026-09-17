package dev.susnowy.gallery.ui.components

/**
 * Pure geometry for pinch-zoom and panning.
 *
 * Kept apart from Compose so the behaviour that makes a viewer feel broken — an image dragged
 * off screen, zoom that does not follow the fingers, panning while fitted — is unit tested
 * instead of eyeballed.
 *
 * Coordinates: the content is drawn centred in a viewport of [viewportWidth] × [viewportHeight]
 * and transformed with `graphicsLayer` around its own centre, so [offsetX]/[offsetY] are
 * translations in view pixels and the content's unscaled size is [contentWidth] × [contentHeight].
 */
data class ZoomTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    val isZoomed: Boolean get() = scale > ZoomTransform.FIT_TOLERANCE

    companion object {
        const val FIT_TOLERANCE = 1.01f
        val Fit = ZoomTransform()
    }
}

/**
 * How untouched content is placed inside its window.
 *
 * The two reading modes need different resting states, and getting this wrong is what makes a
 * viewer feel broken: a photo must show its whole frame, while a comic page must fill the width
 * so continuous reading has no side gaps.
 */
enum class ZoomPlacement {
    /** The whole content is visible at rest; the free space becomes letterboxing. */
    FIT,

    /** The content fills the window width at rest; extra height overflows and can be panned. */
    WIDTH,
}

object ZoomMath {
    const val MAX_SCALE = 6f
    const val DOUBLE_TAP_SCALE = 2.5f

    /** Below the resting scale, so a page measured slightly narrower than the window still fits. */
    const val MIN_RENDERABLE_SCALE = 0.25f

    /**
     * Resting scale for one placement.
     *
     * [ZoomPlacement.FIT] always rests at 1.0 because a fitted box is already laid out at the
     * right size. [ZoomPlacement.WIDTH] rests at `windowWidth / contentWidth`, which is 1.0 for
     * content that already fills the width and only scales otherwise.
     */
    fun baseScale(
        placement: ZoomPlacement,
        windowWidth: Float,
        windowHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
    ): Float = when {
        windowWidth <= 0f || windowHeight <= 0f || contentWidth <= 0f || contentHeight <= 0f -> 1f
        placement == ZoomPlacement.WIDTH -> windowWidth / contentWidth
        else -> 1f
    }

    /**
     * The resting transform.
     *
     * Fitted content is centred in both axes. Width-filled content is centred horizontally and
     * starts at the top of the window, so a page that is taller than the window opens at its
     * first line instead of showing its middle.
     */
    fun baseTransform(
        placement: ZoomPlacement,
        windowWidth: Float,
        windowHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
    ): ZoomTransform {
        val scale = baseScale(placement, windowWidth, windowHeight, contentWidth, contentHeight)
        val offsetY = if (placement == ZoomPlacement.WIDTH && contentHeight * scale > windowHeight) {
            windowHeight / 2f - contentHeight * scale / 2f
        } else {
            0f
        }
        return ZoomTransform(scale = scale, offsetY = offsetY)
    }

    /**
     * Keeps the content inside its window.
     *
     * A dimension that fits is pinned to the centre (no panning at all); a dimension that
     * overflows may move within its own overflow, so an edge can never be pulled away from the
     * matching window edge.
     *
     * [resting] is the transform the content returns to when it is not zoomed. Returning to it
     * re-applies its exact offset, so a width-filled page snaps back to its top instead of to a
     * centred position it never had.
     */
    fun clamp(
        transform: ZoomTransform,
        windowWidth: Float,
        windowHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
        resting: ZoomTransform = ZoomTransform.Fit,
    ): ZoomTransform {
        val scale = transform.scale.coerceIn(MIN_RENDERABLE_SCALE, MAX_SCALE)
        val scaledWidth = contentWidth * scale
        val scaledHeight = contentHeight * scale
        val limitX = ((scaledWidth - windowWidth) / 2f).coerceAtLeast(0f)
        val limitY = ((scaledHeight - windowHeight) / 2f).coerceAtLeast(0f)
        val atRest = scale <= resting.scale * ZoomTransform.FIT_TOLERANCE
        return ZoomTransform(
            scale = scale,
            offsetX = if (atRest) resting.offsetX else transform.offsetX.coerceIn(-limitX, limitX),
            offsetY = if (atRest) resting.offsetY else transform.offsetY.coerceIn(-limitY, limitY),
        )
    }

    /**
     * Scales around a focus point so the content under the fingers stays under the fingers.
     *
     * [focusX]/[focusY] are in window coordinates; the same point must map to the same content
     * position before and after the scale change.
     */
    fun zoomAround(
        transform: ZoomTransform,
        focusX: Float,
        focusY: Float,
        factor: Float,
        windowWidth: Float,
        windowHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
        resting: ZoomTransform = ZoomTransform.Fit,
    ): ZoomTransform {
        val target = (transform.scale * factor).coerceIn(MIN_RENDERABLE_SCALE, MAX_SCALE)
        val centerX = windowWidth / 2f
        val centerY = windowHeight / 2f
        // Content point currently under the focus, relative to the content centre.
        val contentX = (focusX - centerX - transform.offsetX) / transform.scale
        val contentY = (focusY - centerY - transform.offsetY) / transform.scale
        val scaled = ZoomTransform(
            scale = target,
            offsetX = focusX - centerX - contentX * target,
            offsetY = focusY - centerY - contentY * target,
        )
        return clamp(scaled, windowWidth, windowHeight, contentWidth, contentHeight, resting)
    }

    fun panBy(
        transform: ZoomTransform,
        dx: Float,
        dy: Float,
        windowWidth: Float,
        windowHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
        resting: ZoomTransform = ZoomTransform.Fit,
    ): ZoomTransform = clamp(
        transform.copy(offsetX = transform.offsetX + dx, offsetY = transform.offsetY + dy),
        windowWidth,
        windowHeight,
        contentWidth,
        contentHeight,
        resting,
    )

    /**
     * Double tap: content at its resting state zooms to [DOUBLE_TAP_SCALE] around the tapped
     * point, already zoomed content returns to rest.
     *
     * [zoomed] is the caller's view of "past the resting state of this content", because the
     * resting scale is not always 1 (see [baseScale]).
     */
    fun doubleTapTarget(
        transform: ZoomTransform,
        focusX: Float,
        focusY: Float,
        windowWidth: Float,
        windowHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
        zoomed: Boolean = transform.isZoomed,
        resting: ZoomTransform = ZoomTransform.Fit,
    ): ZoomTransform = if (zoomed) {
        resting
    } else {
        zoomAround(
            transform = transform,
            focusX = focusX,
            focusY = focusY,
            factor = DOUBLE_TAP_SCALE / transform.scale.coerceAtLeast(MIN_RENDERABLE_SCALE),
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            resting = resting,
        )
    }

    /**
     * Size of the content when fitted into the window, preserving its aspect ratio.
     * Falls back to the window when the intrinsic size is not known yet.
     */
    fun fittedSize(
        windowWidth: Float,
        windowHeight: Float,
        intrinsicWidth: Float,
        intrinsicHeight: Float,
    ): Pair<Float, Float> {
        if (intrinsicWidth <= 0f || intrinsicHeight <= 0f ||
            windowWidth <= 0f || windowHeight <= 0f
        ) {
            return windowWidth to windowHeight
        }
        val factor = minOf(windowWidth / intrinsicWidth, windowHeight / intrinsicHeight)
        return intrinsicWidth * factor to intrinsicHeight * factor
    }

    /**
     * Pans inside what is currently on screen of one page.
     *
     * Continuous reading puts every page in a scrolling list, so a page can be several times
     * taller than the window and only a slice of it is visible. Clamping against the window
     * alone would let a pan drag a page into blank space above or below the slice the reader
     * is actually looking at; clamping against the content alone would forbid moving to a part
     * of a long page that the scroll position has not reached.
     *
     * [visibleHeight] is the height of the page that is currently inside the reading window.
     * Zero means "not known", and the caller falls back to overflow clamping.
     */
    fun panWithinWindow(
        transform: ZoomTransform,
        dy: Float,
        windowHeight: Float,
        contentHeight: Float,
        visibleHeight: Float,
    ): ZoomTransform {
        val scaledHeight = contentHeight * transform.scale
        val visible = visibleHeight.coerceAtMost(windowHeight).coerceAtMost(scaledHeight)
        if (visible <= 0f) {
            return transform.copy(offsetY = transform.offsetY + dy)
        }
        // The visible slice is centred, so its range in content coordinates is symmetric. Panning
        // moves the content, which shifts that range by exactly dy.
        val limit = ((scaledHeight - visible) / 2f).coerceAtLeast(0f)
        return transform.copy(
            offsetY = (transform.offsetY + dy).coerceIn(-limit, limit),
        )
    }
}
