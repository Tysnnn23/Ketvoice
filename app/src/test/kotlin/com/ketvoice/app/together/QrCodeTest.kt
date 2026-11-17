package com.ketvoice.app.together

import org.junit.Assert.*
import org.junit.Test

/**
 * The QR code of a session's link, module for module against python-qrcode 7.4.2 (byte mode, level M, same mask),
 * generated once on the host: one version without alignment, a link-sized one with version information, and
 * two with several blocks (one with blocks of unequal length).
 */
class QrCodeTest {
    private val references = listOf(
        // version 1, size 21, mask 3
        Triple("KETVOICE", 3,
            "1fd47f,105241,174f5d,175d5d,17445d,104841,1fd57f,001c00,16e04b,1ba9ac,05f9e3,1a29a9,01c62d,001881,1fd8f0,105fcc,174a50,17549a,175e80,104b71,1fd320"),
        // version 8, size 49, mask 5
        Triple("https://tysnnn23.github.io/Ketvoice/listen.html?track=ABC234-QRSTUV23&title=Ketogether&artist=Code%20ABC%20234&kind=together", 5,
            "1fc8194ddd97f,105ac068c9f41,175dfd36da35d,175d3f1bfba5d,174e84ffcb85d,104f3ec424c41,1fd555555557f,001730c6fed00,105b157c41ece,0aa9c77d6edf2,1f648ed408fa7,1e97cd28f201a,0767b7355389b,19937bdb4827e,155989d915423,0f28d28599b0d,0be81d9d3e564,15be92619ccda,1569c078ccf0f,15937d8c5c3f9,14d5e9f42fdc7,13919bcb6dfed,17fcd0fe3f7fb,1b1d4f46b091b,0152c6d575d58,091caec7da91a,01f6fb7ec9df3,03167a74cee9f,0fc6121ae97d4,1f20686b40968,05ce4d6410b4d,14989c6e63329,0048e1aac1a9e,0105a713775bc,0cc56182c187b,0b3c5e5c9af38,05c3324591a60,0500d258f894a,08fcd0420d5d1,0e2887db6462f,1c5aedfccf7ff,00165445fbb18,1fc90d577f95d,1040f6457c319,1743e47c239fe,1748f6a77ccea,17463d3b9ac67,104263108dcc9,1fd6dad488ab9"),
        // version 5, size 37, mask 1
        Triple("Ketogether · écoute à plusieurs ".repeat(2), 1,
            "1fd39b857f,1048f91341,17504be95d,174119cc5d,174db49d5d,105c7c8341,1fd555557f,0001ca0200,14642b2725,1298417163,07e12de559,09a6c31492,04dae83369,0f957321eb,02536e4dfd,0296d560da,195abe7369,0b925cbe51,1374d6a2cd,14acb4054f,15fa5213ee,0325bb566d,0dcf62de31,10a6b903e8,17fcebf37a,0f288133f0,195229abf9,0486076505,1950b133f1,001d77ef19,1fddee2951,1042749118,17485ca1f9,174c5435f8,17559a6bd1,10417053a0,1fdb933159"),
        // version 9, size 53, mask 6
        Triple("x".repeat(170), 6,
            "1fd867c0777c7f,1055014aac1641,175827e71ac25d,1740f9e4777d5d,1755ce5f53e45d,10404c311ac441,1fd5555555557f,0005671106b200,13f8d25f4f9597,1eb92e82dddfd2,056211c406b164,1c2ba059b06909,16c79972dddcd2,158ab4cbf94d9b,0cdb08a9b06a09,110e287d22202d,096b293bf94e9b,121caca64f96f6,034db2cd22232d,1386d4b406b264,1fdba1d64f95f6,1627ffc3dddfd2,1ac321d606b164,079bb17bb06909,11f27b5fddddf2,07148ad1f94d1b,11557f95b06959,0b1b9f5122211d,19f7e51ff94dfb,0232358d4f9456,14d132bc22207d,02b3311606b0c5,157c366b4f96a6,03a27cac5ddd72,1af421f1c6b234,0b0b2a73b06ba9,09f69e47dddf82,0cbafee1f94f3b,095afd1cb06959,1181971722228d,1055d34ef94dcb,11391f4c4f9456,1bceadb822207d,0c11231e06b0c4,0266f77f4f97f6,00165791dddd12,1fd940b506b154,10559bd1b06919,175682dfddddf2,17533584f94c69,174b8726b068fa,1046dae1a221dd,1fd01115b94c68"))

    @Test fun matchesTheReferenceModuleForModule() {
        references.forEach { (text, mask, hex) ->
            val rows = hex.split(',')
            val qr = QrCode.encode(text, mask)!!
            assertEquals(text.take(20), rows.size, qr.size)
            rows.forEachIndexed { y, row ->
                val bits = java.math.BigInteger(row, 16).toString(2).padStart(qr.size, '0')
                for (x in 0 until qr.size) assertEquals("$text ($x,$y)", bits[x] == '1', qr[x, y])
            }
        }
    }

    @Test fun theBestMaskIsChosenAndLongTextsAreRefused() {
        val qr = QrCode.encode(TogetherLink.of("ABC234", "QRSTUV23"))!!
        assertEquals(49, qr.size)
        assertNull(QrCode.encode("x".repeat(300)))
    }
}
