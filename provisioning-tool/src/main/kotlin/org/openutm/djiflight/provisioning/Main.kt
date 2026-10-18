package org.openutm.djiflight.provisioning

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.openutm.djiflight.core.provisioning.ProvisioningCodec
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * For lab admins: turns one device's provisioning JSON into what the app imports.
 *
 *   ./gradlew :provisioning-tool:run --args="device.json out/"
 *
 * Writes out/<device_name>.json (copy to a microSD card or paste: the Smart Controller has no camera)
 * and out/<device_name>.png (QR code for phones). Both contain the device's client secret: hand them
 * over directly, and delete the microSD copy after importing.
 */
fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("usage: provisioning-tool <device.json> <out-dir>")
        exitProcess(2)
    }
    val p = runCatching { ProvisioningCodec.decode(File(args[0]).readText()) }.getOrElse {
        System.err.println("cannot read ${args[0]}: ${it.message}")
        exitProcess(1)
    }
    val problems = p.problems(allowDevAuth = true)
    if (problems.isNotEmpty()) {
        System.err.println("problems: " + problems.joinToString("; "))
        if (problems.any { "debug" !in it && "https" !in it }) exitProcess(1)
        System.err.println("(continuing: acceptable for debug builds only)")
    }
    val out = File(args[1]).apply { mkdirs() }
    val base = p.deviceName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    File(out, "$base.json").writeText(ProvisioningCodec.toJson(p))
    val code = ProvisioningCodec.encode(p)
    val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 800, 800, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
    val img = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_BYTE_BINARY)
    for (y in 0 until matrix.height) for (x in 0 until matrix.width) img.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
    ImageIO.write(img, "png", File(out, "$base.png"))
    println("wrote ${File(out, "$base.json")} and ${File(out, "$base.png")} (QR payload ${code.length} chars, ${p.pilots.size} pilots, ${p.aircraft.size} aircraft)")
}
