package com.nodeping.app.ui.theme

import androidx.compose.ui.graphics.Color

// Brand palette derived from the launcher icon (deep navy + signal blue).
val Navy900 = Color(0xFF0B3D5C)
val Navy700 = Color(0xFF11557E)
val SignalBlue = Color(0xFF4FC3F7)
val SkyBlue = Color(0xFFB3E5FC)

// Status colours for latency results.
val LatencyGood = Color(0xFF2E7D32)   // < 150 ms
val LatencyOk = Color(0xFFF9A825)     // 150–350 ms
val LatencyBad = Color(0xFFC62828)    // > 350 ms / failed
