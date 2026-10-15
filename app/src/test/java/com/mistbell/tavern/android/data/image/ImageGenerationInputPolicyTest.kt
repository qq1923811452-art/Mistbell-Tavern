package com.mistbell.tavern.android.data.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationInputPolicyTest {
    @Test
    fun `encoded base64 length is bounded before decoding`() {
        val maxEncodedBytes = 4L * ((30L * 1024L * 1024L + 2L) / 3L)

        assertFalse(ImageGenerationService.isEncodedImageLengthAllowed(0L))
        assertTrue(ImageGenerationService.isEncodedImageLengthAllowed(4L))
        assertTrue(ImageGenerationService.isEncodedImageLengthAllowed(maxEncodedBytes))
        assertFalse(ImageGenerationService.isEncodedImageLengthAllowed(maxEncodedBytes + 1L))
    }
}
