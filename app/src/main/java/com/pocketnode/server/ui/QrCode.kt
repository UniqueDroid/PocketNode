package com.pocketnode.server.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR-Code als Matrix, `true` = dunkles Modul. Ohne Rand, Fehlerkorrektur M. */
fun qrMatrix(text: String): Array<BooleanArray> {
    val bits = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    return Array(bits.height) { y -> BooleanArray(bits.width) { x -> bits[x, y] } }
}
