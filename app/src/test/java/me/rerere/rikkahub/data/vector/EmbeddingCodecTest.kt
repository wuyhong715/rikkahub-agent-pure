package me.rerere.rikkahub.data.vector

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EmbeddingCodecTest {

    @Test
    fun `round trips a vector`() {
        val vector = floatArrayOf(0f, 1f, -1f, 0.5f, 1618.7972f, -0.00001f)
        assertArrayEquals(vector, EmbeddingCodec.decode(EmbeddingCodec.encode(vector)), 0f)
    }

    @Test
    fun `round trips an empty vector`() {
        assertEquals(0, EmbeddingCodec.encode(FloatArray(0)).size)
        assertEquals(0, EmbeddingCodec.decode(ByteArray(0)).size)
    }

    @Test
    fun `writes little-endian float32`() {
        // 1.0f is 0x3F800000; little-endian means the low byte comes first.
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F), EmbeddingCodec.encode(floatArrayOf(1f)))
    }

    @Test
    fun `a 768-wide vector is 3072 bytes`() {
        // The size the index is actually built for; a mistake here would be a 4x storage
        // surprise discovered only on a full index.
        assertEquals(3072, EmbeddingCodec.encode(FloatArray(768)).size)
    }

    @Test
    fun `a truncated blob is refused rather than silently shortened`() {
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddingCodec.decode(byteArrayOf(1, 2, 3))
        }
    }
}
