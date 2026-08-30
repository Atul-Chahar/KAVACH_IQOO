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
    ) {
        /**
         * [evidence] ordered by how much it should worry the reader, worst first.
         *
         * A notification has room for two lines, and which two decides whether
         * the warning lands. Detection order is insertion order — which is an
         * accident of how [collectEvidence] runs — so a message that both asks
         * for an OTP and sounds urgent would lead with "it uses urgency",
         * burying the part that actually costs the user money. The ranking is
         * fixed and lives here, next to the enum it orders, so the notification
         * and the detail screen cannot drift apart.
         */
        fun ranked(limit: Int = Int.MAX_VALUE): List<Evidence> =
            evidence.sortedBy { EVIDENCE_RANK.indexOf(it) }.take(limit)
    }

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

    /**
     * Whether the message is asking for a credential — as opposed to warning
     * about one, which is what a real bank does.
     *
     * The lexicon's negative guards have the final say, and that is the fix
     * this shape exists for. The regex fallback used to run whenever the
     * CREDENTIAL family had not fired cleanly, including when it had not fired
     * *because a guard subtracted it* — so a guard that had done its job was
     * then overruled by a coarser pattern. `real-bank-desk-devanagari-01`, a
     * genuine fraud desk saying "we never ask for OTP; do not tell it to
     * anyone, not even us", came out HIGH_RISK on the message path while the
     * call engine cleared it. Under the new surfaces that is a lock-screen
     * warning and a red capsule accusing a real bank.
     *
     * A guard is only overridden by an explicit instruction to hand a
     * credential over — which is the scammer's own move: recite the safety
     * warning, then ask anyway.
     */
    private fun hasCredentialRequest(
        normalized: String,
        signals: List<Signal>,
        families: Set<String>,
    ): Boolean {
        val guarded = signals.any { it.family == TacticMatcher.NEGATIVE_GUARD }
        val instructed = credentialInstructionPattern.containsMatchIn(normalized)
        if (guarded) return instructed
        if (CREDENTIAL in families) return true
        if (!credentialRequestPattern.containsMatchIn(normalized)) return false
        return !credentialWarningPattern.containsMatchIn(normalized) || instructed
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
        /**
         * Worst first. Asking for a credential or a payment is the loss itself;
         * a link is the delivery mechanism; urgency and secrecy are the pressure
         * around it. LINKED_ACTION is last because it never stands alone — it is
         * only ever emitted alongside one of the reasons above it.
         */
        val EVIDENCE_RANK =
            listOf(
                Evidence.CREDENTIAL_REQUEST,
                Evidence.PAYMENT_OR_REMOTE_ACCESS,
                Evidence.SUSPICIOUS_LINK,
                Evidence.IMPERSONATION,
                Evidence.URGENCY_OR_THREAT,
                Evidence.SECRECY,
                Evidence.LINKED_ACTION,
            )

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
