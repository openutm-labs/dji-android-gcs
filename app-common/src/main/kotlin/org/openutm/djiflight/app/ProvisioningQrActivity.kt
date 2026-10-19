package org.openutm.djiflight.app

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/** DJI ships incompatible views under the same zxing_* layout names as JourneyApps. */
class ProvisioningQrActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.openutm_qr_capture)
        return findViewById(R.id.openutm_qr_scanner)
    }
}
