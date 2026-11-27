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
 * Shows [payload] as a QR code. GameShare uses this for its join code, which the gameshareX app
 * reads to connect automatically.
 */
@Composable
fun QrCodeImage(payload: String, modifier: Modifier = Modifier) {
    val bitmap = remember(payload) {
        runCatching { buildWifiQrBitmap(payload, QR_SIZE_PX) }.getOrNull()
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Connection QR code",
                modifier = Modifier.size(200.dp),
            )
        } else {
            Text("QR code তৈরি করা যায়নি", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Join payload read by the gameshareX app: `GSX1;S=<ssid>;P=<pass>;H=<host>;O=<port>;A=<0|1>`.
 * The proxy password is never put in the QR; the client types it in when authentication is on.
 */
internal fun gameShareJoinPayload(ssid: String, passphrase: String, host: String, port: Int, authEnabled: Boolean): String =
    "GSX1;S=$ssid;P=$passphrase;H=$host;O=$port;A=${if (authEnabled) 1 else 0}"

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
