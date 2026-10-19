package com.cinecam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ManualControls - CineCam Manual Control Panel
 * MotionCam-inspired: ISO, Shutter, Focus, WB sliders with live preview
 */

@Composable
fun ManualControlPanel(
    iso: Int,
    onIsoChange: (Int) -> Unit,
    shutterNs: Long,
    onShutterChange: (Long) -> Unit,
    focusDistance: Float?,
    onFocusChange: (Float?) -> Unit,
    wbKelvin: Int,
    onWbChange: (Int) -> Unit,
    isRecording: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xCC0A0A0A), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MANUAL", color = Color(0xFFE50914), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            if (isRecording) {
                Text("● REC", color = Color.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ISO Slider
        ControlSlider(
            label = "ISO",
            value = iso.toFloat(),
            valueRange = 50f..3200f,
            displayValue = iso.toString(),
            onValueChange = { onIsoChange(it.toInt()) }
        )

        // Shutter Speed (exposure time) - map to 1/x
        val shutterMs = shutterNs / 1_000_000f
        ControlSlider(
            label = "SHUTTER",
            value = shutterMs,
            valueRange = 0.1f..100f,
            displayValue = if (shutterMs < 1) "1/${(1000/shutterMs).toInt()}" else "${shutterMs.toInt()}ms",
            onValueChange = { onShutterChange((it * 1_000_000).toLong()) }
        )

        // Focus (diopters)
        ControlSlider(
            label = "FOCUS",
            value = focusDistance ?: 0f,
            valueRange = 0f..10f,
            displayValue = focusDistance?.let { "%.1f".format(it) + " diopters" } ?: "AUTO",
            onValueChange = { onFocusChange(if (it == 0f) null else it) }
        )

        // White Balance Kelvin
        ControlSlider(
            label = "WB",
            value = wbKelvin.toFloat(),
            valueRange = 2500f..9000f,
            displayValue = "${wbKelvin}K",
            onValueChange = { onWbChange(it.toInt()) }
        )
    }
}

@Composable
private fun ControlSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFF9A9A9E), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(displayValue, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFE50914),
                activeTrackColor = Color(0xFFE50914),
                inactiveTrackColor = Color(0xFF2C2C2E)
            ),
            modifier = Modifier.height(28.dp)
        )
    }
}

@Composable
fun FpsSelector(
    selectedFps: Int,
    onFpsSelected: (Int) -> Unit,
    availableFps: List<Int> = listOf(24, 30, 60),
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        availableFps.forEach { fps ->
            val selected = fps == selectedFps
            FilterChip(
                selected = selected,
                onClick = { onFpsSelected(fps) },
                label = { Text("${fps}fps", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFFE50914),
                    selectedLabelColor = Color.White,
                    containerColor = Color(0xFF2C2C2E),
                    labelColor = Color(0xFF9A9A9E)
                )
            )
        }
    }
}

@Composable
fun RecordButton(
    isRecording: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Button(
        onClick = onToggle,
        modifier = modifier.size(72.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRecording) Color.Red else Color.White
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(Modifier.size(32.dp).background(if (isRecording) Color.White else Color.Red,
            androidx.compose.foundation.shape.RoundedCornerShape(if (isRecording) 4.dp else 16.dp)))
    }
}
