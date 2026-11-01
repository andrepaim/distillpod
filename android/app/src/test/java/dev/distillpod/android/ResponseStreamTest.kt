package dev.distillpod.android
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResponseStreamTest {
    @Test fun `requires completed event and concatenates deltas`() {
        val parser = ResponseStream()
        parser.accept("""{"type":"response.output_text.delta","delta":"hello"}""")
        parser.accept("""{"type":"response.output_text.delta","delta":" world"}""")
        assertThrows(IllegalStateException::class.java) { parser.result() }
        parser.accept("""{"type":"response.completed","response":{"status":"completed"}}""")
        assertEquals("hello world", parser.result())
    }
    @Test fun `failed response never returns partial output`() {
        val parser = ResponseStream()
        parser.accept("""{"type":"response.output_text.delta","delta":"partial"}""")
        assertThrows(IllegalStateException::class.java) { parser.accept("""{"type":"response.failed"}""") }
        assertThrows(IllegalStateException::class.java) { parser.result() }
    }
}
