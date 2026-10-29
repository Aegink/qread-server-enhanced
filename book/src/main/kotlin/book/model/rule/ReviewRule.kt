package book.model.rule

import book.util.ruleJsonDeserializer

data class ReviewRule(
    var reviewUrl: String? = null,
    var avatarRule: String? = null,
    var contentRule: String? = null,
    var postTimeRule: String? = null,
    var reviewQuoteUrl: String? = null,
    var voteUpUrl: String? = null,
    var voteDownUrl: String? = null,
    var postReviewUrl: String? = null,
    var postQuoteUrl: String? = null,
    var deleteUrl: String? = null,
) {
    companion object {
        val jsonDeserializer = ruleJsonDeserializer(ReviewRule::class.java) {
            ReviewRule(contentRule = it)
        }
    }
}
