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
    val isZoomed: Boolean get() = scale > FIT_TOLERANCE

    companion object {
        const val FIT_TOLERANCE = 1.01f
        val Fit = ZoomTransform()
    }
}

object ZoomMath {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 6f
    const val DOUBLE_TAP_SCALE = 2.5f

    /**
     * Keeps the content inside the viewport.
     *
     * A dimension that fits is pinned to the centre (no panning at all); a dimension that
     * overflows may move within its own overflow, so an edge can never be pulled away from the
     * matching viewport edge.
     */
    fun clamp(
        transform: ZoomTransform,
        viewportWidth: Float,
        viewportHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
    ): ZoomTransform {
        val scale = transform.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val scaledWidth = contentWidth * scale
        val scaledHeight = contentHeight * scale
        val limitX = ((scaledWidth - viewportWidth) / 2f).coerceAtLeast(0f)
        val limitY = ((scaledHeight - viewportHeight) / 2f).coerceAtLeast(0f)
        return ZoomTransform(
            scale = scale,
            offsetX = if (scale <= ZoomTransform.FIT_TOLERANCE) 0f else transform.offsetX.coerceIn(-limitX, limitX),
            offsetY = if (scale <= ZoomTransform.FIT_TOLERANCE) 0f else transform.offsetY.coerceIn(-limitY, limitY),
        )
    }

    /**
     * Scales around a focus point so the content under the fingers stays under the fingers.
     *
     * [focusX]/[focusY] are in view coordinates; the same point must map to the same content
     * position before and after the scale change.
     */
    fun zoomAround(
        transform: ZoomTransform,
        focusX: Float,
        focusY: Float,
        factor: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
    ): ZoomTransform {
        val target = (transform.scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val centerX = viewportWidth / 2f
        val centerY = viewportHeight / 2f
        // Content point currently under the focus, relative to the content centre.
        val contentX = (focusX - centerX - transform.offsetX) / transform.scale
        val contentY = (focusY - centerY - transform.offsetY) / transform.scale
        val scaled = ZoomTransform(
            scale = target,
            offsetX = focusX - centerX - contentX * target,
            offsetY = focusY - centerY - contentY * target,
        )
        return clamp(scaled, viewportWidth, viewportHeight, contentWidth, contentHeight)
    }

    fun panBy(
        transform: ZoomTransform,
        dx: Float,
        dy: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
    ): ZoomTransform = clamp(
        transform.copy(offsetX = transform.offsetX + dx, offsetY = transform.offsetY + dy),
        viewportWidth,
        viewportHeight,
        contentWidth,
        contentHeight,
    )

    /**
     * Double tap: fitted content zooms to [DOUBLE_TAP_SCALE] around the tapped point, zoomed
     * content returns to fit.
     */
    fun doubleTapTarget(
        transform: ZoomTransform,
        focusX: Float,
        focusY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
    ): ZoomTransform = if (transform.isZoomed) {
        ZoomTransform.Fit
    } else {
        zoomAround(
            transform = transform,
            focusX = focusX,
            focusY = focusY,
            factor = DOUBLE_TAP_SCALE / transform.scale.coerceAtLeast(MIN_SCALE),
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )
    }

    /**
     * Size of the content when fitted into the viewport, preserving its aspect ratio.
     * Falls back to the viewport when the intrinsic size is not known yet.
     */
    fun fittedSize(
        viewportWidth: Float,
        viewportHeight: Float,
        intrinsicWidth: Float,
        intrinsicHeight: Float,
    ): Pair<Float, Float> {
        if (intrinsicWidth <= 0f || intrinsicHeight <= 0f ||
            viewportWidth <= 0f || viewportHeight <= 0f
        ) {
            return viewportWidth to viewportHeight
        }
        val factor = minOf(viewportWidth / intrinsicWidth, viewportHeight / intrinsicHeight)
        return intrinsicWidth * factor to intrinsicHeight * factor
    }
}
