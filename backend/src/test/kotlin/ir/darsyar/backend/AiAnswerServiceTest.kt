package ir.darsyar.backend

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

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
}
