package com.qualityverifier.server

import com.qualityverifier.server.db.FeedbackStore
import com.qualityverifier.server.db.TesterFeedback

/**
 * A feedback store that is never reached.
 *
 * Shared by the tests whose subject is something else, so that adding a collaborator to the
 * Chat bundle does not mean writing the same empty implementation in every file. Throws
 * rather than returning a default: a test that unexpectedly lands here should fail loudly.
 */
object NoFeedback : FeedbackStore {
    override suspend fun save(userId: String, feedback: TesterFeedback): Boolean =
        error("this test should not have written evaluator feedback")

    override suspend fun feedbackFor(sessionId: String): TesterFeedback? =
        error("this test should not have read evaluator feedback")
}
