package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Shows a QR code that any phone camera can scan to join the Wi-Fi network. Scanning it joins
 * the network without installing GameShare on the other phone. (An app is still needed to use
 * the SOCKS5 proxy.)
 */
@Composable
fun WifiQrCode(ssid: String, passphrase: String, modifier: Modifier = Modifier) {
    val bitmap = remember(ssid, passphrase) {
        runCatching { buildWifiQrBitmap(wifiQrPayload(ssid, passphrase), QR_SIZE_PX) }.getOrNull()
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Wi-Fi QR code",
                modifier = Modifier.size(200.dp),
            )
        } else {
            Text("QR code তৈরি করা যায়নি", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Standard Wi-Fi join payload, understood by Android and MIUI camera scanners. */
internal fun wifiQrPayload(ssid: String, passphrase: String): String =
    "WIFI:T:WPA;S:${escapeWifiField(ssid)};P:${escapeWifiField(passphrase)};;"

private fun escapeWifiField(value: String): String = buildString {
    for (ch in value) {
        if (ch == '\\' || ch == ';' || ch == ',' || ch == ':' || ch == '"') append('\\')
        append(ch)
    }
}

internal fun buildWifiQrBitmap(payload: String, sizePx: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix: BitMatrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        for (x in 0 until sizePx) {
            pixels[y * sizePx + x] = if (matrix.get(x, y)) BLACK else WHITE
        }
    }
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}

private const val QR_SIZE_PX = 512
private const val BLACK = -0x1000000
private const val WHITE = -0x1
