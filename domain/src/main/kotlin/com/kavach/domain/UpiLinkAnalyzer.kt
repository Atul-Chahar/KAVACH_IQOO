package com.kavach.domain

/**
 * Deterministic safety check for a scanned UPI QR or a pasted payment link.
 * Pure string/URI logic, no ML — docs/ARCHITECTURE.md 6.
 *
 * Addresses the survey figures directly: 40% of UPI victims clicked a malicious
 * link, 20% scanned a malicious QR.
 */
object UpiLinkAnalyzer {
    enum class Flag {
        /** A collect request that debits the scanner, dressed up as a payment to them. */
        COLLECT_REQUEST_DISGUISED,

        /** The payee handle and the displayed merchant name disagree. */
        PAYEE_NAME_MISMATCH,

        /** A shortener or redirect chain in a payment context. */
        SHORTENER_OR_REDIRECT,

        /** Punycode or homoglyph substitution in a bank or wallet domain. */
        LOOKALIKE_DOMAIN,
    }

    data class Result(
        val flags: List<Flag>,
        val payeeVpa: String?,
        val payeeName: String?,
        val explanation: List<String>,
    ) {
        val isSuspicious: Boolean get() = flags.isNotEmpty()
    }

    private val shorteners =
        setOf(
            "bit.ly",
            "tinyurl.com",
            "t.co",
            "goo.gl",
            "ow.ly",
            "rebrand.ly",
            "cutt.ly",
            "is.gd",
            "buff.ly",
            "shorturl.at",
            "rb.gy",
            "tiny.cc",
        )

    /** Brands scammers imitate. Match is on the registrable label, not the full host. */
    private val protectedBrands =
        setOf(
            "sbi",
            "hdfc",
            "icici",
            "axis",
            "kotak",
            "paytm",
            "phonepe",
            "googlepay",
            "gpay",
            "bhim",
            "npci",
            "upi",
            "rbi",
        )

    private val homoglyphs =
        mapOf(
            '0' to 'o',
            '1' to 'l',
            '3' to 'e',
            '4' to 'a',
            '5' to 's',
            '7' to 't',
        )

    fun analyze(input: String): Result {
        val raw = input.trim()
        val flags = mutableListOf<Flag>()
        val notes = mutableListOf<String>()

        val params = queryParams(raw)
        val payeeVpa = params["pa"]
        val payeeName = params["pn"]

        if (raw.startsWith("upi://", ignoreCase = true)) {
            if (raw.contains("upi://collect", ignoreCase = true) || params["mode"] == "collect") {
                flags += Flag.COLLECT_REQUEST_DISGUISED
                notes += "This is a request for money FROM you, not a payment TO you."
            }
            if (payeeVpa != null && payeeName != null && !namesAgree(payeeVpa, payeeName)) {
                flags += Flag.PAYEE_NAME_MISMATCH
                notes += "The name shown ($payeeName) does not match the UPI ID ($payeeVpa)."
            }
        }

        val host = hostOf(raw)
        if (host != null) {
            if (host in shorteners) {
                flags += Flag.SHORTENER_OR_REDIRECT
                notes += "This is a shortened link. You cannot see where it actually goes."
            }
            if (isLookalike(host)) {
                flags += Flag.LOOKALIKE_DOMAIN
                notes += "The web address imitates a bank or wallet name."
            }
        }

        return Result(flags, payeeVpa, payeeName, notes)
    }

    /** True when the VPA's handle plausibly corresponds to the displayed name. */
    private fun namesAgree(
        vpa: String,
        displayName: String,
    ): Boolean {
        val handle = vpa.substringBefore('@').lowercase().filter { it.isLetterOrDigit() }
        val name = displayName.lowercase().filter { it.isLetterOrDigit() }
        if (handle.isEmpty() || name.isEmpty()) return true
        val firstToken =
            displayName
                .trim()
                .split(' ')
                .first()
                .lowercase()
                .filter { it.isLetterOrDigit() }
        return handle.contains(name) ||
            name.contains(handle) ||
            (firstToken.length >= MIN_TOKEN_MATCH && handle.contains(firstToken))
    }

    private fun isLookalike(host: String): Boolean {
        if (host.startsWith("xn--") || host.contains(".xn--")) return true
        val labels = host.split('.')
        return labels.any { label ->
            val folded = label.map { homoglyphs[it] ?: it }.joinToString("")
            folded != label && protectedBrands.any { folded.contains(it) }
        }
    }

    private fun hostOf(raw: String): String? {
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val afterScheme = withScheme.substringAfter("://")
        if (afterScheme.isEmpty()) return null
        val host = afterScheme.substringBefore('/').substringBefore('?').substringAfter('@')
        return host.substringBefore(':').lowercase().takeIf { it.contains('.') }
    }

    private fun queryParams(raw: String): Map<String, String> =
        raw
            .substringAfter('?', "")
            .split('&')
            .filter { it.contains('=') }
            .associate { pair ->
                pair.substringBefore('=').lowercase() to decode(pair.substringAfter('='))
            }

    private fun decode(value: String): String =
        value.replace('+', ' ').replace(Regex("%([0-9A-Fa-f]{2})")) { match ->
            match.groupValues[1]
                .toInt(HEX_RADIX)
                .toChar()
                .toString()
        }

    private const val MIN_TOKEN_MATCH = 4
    private const val HEX_RADIX = 16
}
