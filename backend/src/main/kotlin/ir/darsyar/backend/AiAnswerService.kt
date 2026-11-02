package ir.darsyar.backend

/** Provider-neutral boundary for grounded answers about a visible session. */
data class AiAnswerCitation(val startOffset: Int, val endOffset: Int, val excerpt: String)
data class AiAnswerResult(val answer: String, val citations: List<AiAnswerCitation> = emptyList())

interface AiAnswerService {
    fun isAvailable(): Boolean
    fun answer(transcript: String, question: String, selectedText: String?): AiAnswerResult
}

class AiProviderUnavailableException : RuntimeException()

/** Safe default: no AI provider is exposed until server-side secrets and an adapter are configured. */
object DisabledAiAnswerService : AiAnswerService {
    override fun isAvailable(): Boolean = false
    override fun answer(transcript: String, question: String, selectedText: String?): AiAnswerResult =
        throw AiProviderUnavailableException()
}
