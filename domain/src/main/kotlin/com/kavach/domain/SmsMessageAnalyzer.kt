package com.kavach.domain

/** Offline, deterministic analysis for message text explicitly shared with Kavach. */
class SmsMessageAnalyzer(
    lexicon: TacticLexicon,
    private val matcher: TacticMatcher = TacticMatcher(lexicon),
) {
    enum class Severity { CLEAR, CAUTION, HIGH_RISK }

    enum class Evidence {
        SUSPICIOUS_LINK,
        LINKED_ACTION,
        CREDENTIAL_REQUEST,
        PAYMENT_OR_REMOTE_ACCESS,
        URGENCY_OR_THREAT,
        IMPERSONATION,
        SECRECY,
    }

    data class Result(
        val severity: Severity,
        val evidence: List<Evidence>,
    )

    fun analyze(input: String): Result {
        val text = input.take(MAX_MESSAGE_LENGTH)
        if (text.isBlank()) return Result(Severity.CLEAR, emptyList())

        val signals = matcher.match(TranscriptWindow(text, 0, 1))
        val families = signals.filter { it.family != TacticMatcher.NEGATIVE_GUARD }.mapTo(mutableSetOf()) { it.family }
        val normalized = TranscriptNormalizer.normalize(text)
        val links =
            linkPattern
                .findAll(text)
                .take(MAX_LINKS)
                .map { trimLink(it.value) }
                .toList()
        val suspiciousLink = links.any { UpiLinkAnalyzer.analyze(it).isSuspicious || hasDangerousShape(it) }
        val evidence = collectEvidence(normalized, signals, families, links.isNotEmpty(), suspiciousLink)
        return Result(classify(evidence, suspiciousLink), evidence.toList())
    }

    private fun collectEvidence(
        normalized: String,
        signals: List<Signal>,
        families: Set<String>,
        hasLink: Boolean,
        suspiciousLink: Boolean,
    ): LinkedHashSet<Evidence> {
        val evidence = linkedSetOf<Evidence>()
        if (suspiciousLink) evidence += Evidence.SUSPICIOUS_LINK
        if (AUTHORITY in families) evidence += Evidence.IMPERSONATION
        if (URGENCY in families || urgencyPattern.containsMatchIn(normalized)) evidence += Evidence.URGENCY_OR_THREAT
        if (SECRECY in families) evidence += Evidence.SECRECY
        if (TRANSFER in families || paymentPattern.containsMatchIn(normalized)) {
            evidence += Evidence.PAYMENT_OR_REMOTE_ACCESS
        }

        if (hasCredentialRequest(normalized, signals, families)) {
            evidence += Evidence.CREDENTIAL_REQUEST
        }

        if (hasLink && evidence.any(::isActionEvidence)) evidence += Evidence.LINKED_ACTION
        return evidence
    }

    private fun hasCredentialRequest(
        normalized: String,
        signals: List<Signal>,
        families: Set<String>,
    ): Boolean {
        val guardedFamily = CREDENTIAL in families && signals.none { it.family == TacticMatcher.NEGATIVE_GUARD }
        if (guardedFamily) return true
        if (!credentialRequestPattern.containsMatchIn(normalized)) return false
        return !credentialWarningPattern.containsMatchIn(normalized) ||
            credentialInstructionPattern.containsMatchIn(normalized)
    }

    private fun isActionEvidence(evidence: Evidence): Boolean =
        evidence == Evidence.CREDENTIAL_REQUEST ||
            evidence == Evidence.PAYMENT_OR_REMOTE_ACCESS ||
            evidence == Evidence.URGENCY_OR_THREAT

    private fun classify(
        evidence: Set<Evidence>,
        suspiciousLink: Boolean,
    ): Severity {
        val strongLinkCombination =
            suspiciousLink &&
                evidence.any { it == Evidence.CREDENTIAL_REQUEST || it == Evidence.PAYMENT_OR_REMOTE_ACCESS }
        val independentFamilies = evidence.count { it != Evidence.LINKED_ACTION }
        return when {
            strongLinkCombination || independentFamilies >= HIGH_RISK_FAMILIES -> Severity.HIGH_RISK
            evidence.size >= CAUTION_SIGNALS -> Severity.CAUTION
            else -> Severity.CLEAR
        }
    }

    private fun hasDangerousShape(link: String): Boolean {
        val authority = link.substringAfter("://", "").substringBefore('/').substringBefore('?')
        val host = authority.substringAfter('@').substringBefore(':').lowercase()
        return authority.contains('@') || ipv4Pattern.matches(host) || link.startsWith("http://", ignoreCase = true)
    }

    private fun trimLink(link: String): String = link.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')

    private companion object {
        const val MAX_MESSAGE_LENGTH = 10_000
        const val MAX_LINKS = 8
        const val HIGH_RISK_FAMILIES = 3
        const val CAUTION_SIGNALS = 2

        const val AUTHORITY = "AUTHORITY_IMPERSONATION"
        const val SECRECY = "ISOLATION_AND_SECRECY"
        const val URGENCY = "URGENCY_AND_THREAT"
        const val CREDENTIAL = "CREDENTIAL_EXTRACTION"
        const val TRANSFER = "REMOTE_ACCESS_AND_TRANSFER"

        val linkPattern = Regex("(?i)(?:https?://|upi://)[^\\s<>]+")
        val ipv4Pattern = Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")
        val credentialRequestPattern =
            Regex(
                "(?i)(?:share|send|enter|provide|reply|confirm|verify|update|बताओ|बताइए|भेजें|डालें).{0,32}" +
                    "(?:otp|one time password|pin|password|cvv|kyc|ओटीपी|पिन|पासवर्ड|सीवीवी|केवाईसी)|" +
                    "(?:otp|one time password|pin|password|cvv|kyc|ओटीपी|पिन|पासवर्ड|सीवीवी|केवाईसी).{0,32}" +
                    "(?:share|send|enter|provide|reply|confirm|verify|update|बताओ|बताइए|भेजें|डालें)",
            )
        val credentialWarningPattern =
            Regex(
                "(?i)(?:do not|don't|never|कभी न|मत).{0,24}" +
                    "(?:share|send|बताएं|बताओ|भेजें).{0,16}(?:otp|pin|password|cvv|ओटीपी|पिन|पासवर्ड|सीवीवी)",
            )
        val credentialInstructionPattern =
            Regex(
                "(?i)(?:enter|reply with|provide|send us|डालें|बताइए).{0,24}" +
                    "(?:otp|pin|password|cvv|ओटीपी|पिन|पासवर्ड|सीवीवी)",
            )
        val paymentPattern =
            Regex(
                "(?i)(?:pay|payment|transfer|fee|gift card|refund|upi collect|send money|" +
                    "भुगतान|पैसे भेज|रिफंड|शुल्क)",
            )
        val urgencyPattern =
            Regex(
                "(?i)(?:act now|immediately|within \\d+ (?:minute|hour)|account (?:blocked|suspended)|" +
                    "expires? today|अभी|तुरंत|खाता बंद|आज समाप्त)",
            )
    }
}
