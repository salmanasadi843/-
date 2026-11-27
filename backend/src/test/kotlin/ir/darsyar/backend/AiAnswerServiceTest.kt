package ir.darsyar.backend

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AiAnswerServiceTest {
    @Test
    fun `default service stays unavailable until a provider is configured`() {
        assertFalse(DisabledAiAnswerService.isAvailable())
    }

    @Test
    fun `default service never fabricates an answer`() {
        assertFailsWith<AiProviderUnavailableException> {
            DisabledAiAnswerService.answer(
                transcript = "متن جلسه",
                question = "موضوع جلسه چیست؟",
                selectedText = null
            )
        }
    }

    @Test
    fun `grounded citation must exactly match transcript range`() {
        val transcript = "بحث درباره عقل عملی است."
        val start = transcript.indexOf("عقل عملی")
        val end = start + "عقل عملی".length
        val valid = AiAnswerResult(
            answer = "موضوع جلسه عقل عملی است.",
            citations = listOf(AiAnswerCitation(start, end, "عقل عملی"))
        )
        assertTrue(valid.isGroundedIn(transcript))
    }

    @Test
    fun `citation with mismatched excerpt is rejected`() {
        val transcript = "متن معتبر جلسه"
        val invalid = AiAnswerResult(
            answer = "پاسخ",
            citations = listOf(AiAnswerCitation(0, 4, "متن جعلی"))
        )
        assertFalse(invalid.isGroundedIn(transcript))
    }

    @Test
    fun `citation outside transcript is rejected`() {
        val invalid = AiAnswerResult(
            answer = "پاسخ",
            citations = listOf(AiAnswerCitation(0, 99, "متن"))
        )
        assertFalse(invalid.isGroundedIn("کوتاه"))
    }

    @Test
    fun `blank or oversized answers are rejected`() {
        assertFalse(AiAnswerResult("  ").isGroundedIn("متن"))
        assertFalse(AiAnswerResult("x".repeat(20_001)).isGroundedIn("متن"))
    }
}
