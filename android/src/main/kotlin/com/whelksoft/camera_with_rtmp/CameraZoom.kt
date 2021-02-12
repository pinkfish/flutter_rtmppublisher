// Copyright 2019 The Chromium Authors. All rights reserved.

package com.whelksoft.camera_with_rtmp;

import android.graphics.Rect
import androidx.core.math.MathUtils

class CameraZoom(private val sensorSize: Rect?, maxZoom: Float?) {
  private val cropRegion = Rect()
  var maxZoom: Float
  var hasSupport: Boolean

  fun computeZoom(zoom: Float): Rect? {
    if (sensorSize == null || !hasSupport) {
      return null
    }
    val newZoom = MathUtils.clamp(zoom, DEFAULT_ZOOM_FACTOR, maxZoom)
    val centerX = sensorSize.width() / 2
    val centerY = sensorSize.height() / 2
    val deltaX = (0.5f * sensorSize.width() / newZoom).toInt()
    val deltaY = (0.5f * sensorSize.height() / newZoom).toInt()
    cropRegion[centerX - deltaX, centerY - deltaY, centerX + deltaX] = centerY + deltaY
    return cropRegion
  }

  companion object {
    const val DEFAULT_ZOOM_FACTOR = 1.0f
  }

  init {
    if (sensorSize == null) {
      this.maxZoom = DEFAULT_ZOOM_FACTOR
      hasSupport = false
    }
    this.maxZoom = if (maxZoom == null || maxZoom < DEFAULT_ZOOM_FACTOR) DEFAULT_ZOOM_FACTOR else maxZoom
    hasSupport = this.maxZoom.compareTo(DEFAULT_ZOOM_FACTOR) > 0
  }
}