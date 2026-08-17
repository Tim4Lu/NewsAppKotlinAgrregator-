package com.newsapp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val IconEdit: ImageVector
    get() = ImageVector.Builder(
        name = "Edit",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(
        fill = null,
        stroke = SolidColor(Color.White),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ) {
        moveTo(11f, 4f)
        lineTo(4f, 4f)
        arcTo(2f, 2f, 0f, false, false, 2f, 6f)
        lineTo(2f, 20f)
        arcTo(2f, 2f, 0f, false, false, 4f, 22f)
        lineTo(18f, 22f)
        arcTo(2f, 2f, 0f, false, false, 20f, 20f)
        lineTo(20f, 13f)
        moveTo(18.5f, 2.5f)
        arcTo(2.121f, 2.121f, 0f, false, true, 21.5f, 5.5f)
        lineTo(12f, 15f)
        lineTo(8f, 16f)
        lineTo(9f, 12f)
        close()
    }.build()

val IconCopy: ImageVector
    get() = ImageVector.Builder(
        name = "Copy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(
        fill = null,
        stroke = SolidColor(Color.White),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ) {
        moveTo(9f, 11f)
        arcTo(2f, 2f, 0f, false, true, 11f, 9f)
        lineTo(20f, 9f)
        arcTo(2f, 2f, 0f, false, true, 22f, 11f)
        lineTo(22f, 20f)
        arcTo(2f, 2f, 0f, false, true, 20f, 22f)
        lineTo(11f, 22f)
        arcTo(2f, 2f, 0f, false, true, 9f, 20f)
        close()
        moveTo(5f, 15f)
        lineTo(4f, 15f)
        arcTo(2f, 2f, 0f, false, true, 2f, 13f)
        lineTo(2f, 4f)
        arcTo(2f, 2f, 0f, false, true, 4f, 2f)
        lineTo(13f, 2f)
        arcTo(2f, 2f, 0f, false, true, 15f, 4f)
        lineTo(15f, 5f)
    }.build()

val IconRotate: ImageVector
    get() = ImageVector.Builder(
        name = "Rotate",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(
        fill = null,
        stroke = SolidColor(Color.White),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ) {
        moveTo(21f, 12f)
        arcTo(9f, 9f, 0f, true, true, 18f, 5.21f)
        lineTo(21f, 8f)
        moveTo(21f, 3f)
        lineTo(21f, 8f)
        lineTo(16f, 8f)
    }.build()
