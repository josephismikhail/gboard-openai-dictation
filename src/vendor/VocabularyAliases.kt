package com.trevornk.ramblr

/** Explicit personal aliases in the vocabulary editor: "heard phrase => desired spelling". */
object VocabularyAliases {
    private data class Alias(val heard: String, val desired: String, val contextual: Boolean)
    private data class Replacement(val start: Int, val end: Int, val text: String, val contextual: Boolean = false)
    private data class Candidate(var distance: Int, val targets: MutableSet<String>)
    private val words = Regex("[\\p{L}\\p{N}]+")
    private val scope = Regex("^@when\\[([^]\\r\\n]+)]\\s+(.+)$")
    fun canonical(term: String): String = term.substringAfter("=>", term).trim()
    internal fun isContextual(term: String): Boolean = scope.matches(term.trim())
    fun activeTerms(text: String, terms: List<String>): List<String> = terms.mapNotNull { term ->
        val match = scope.matchEntire(term.trim()) ?: return@mapNotNull term.takeUnless { it.trim().startsWith("@when[") }
        val active = match.groupValues[1].split('|').any { anchor ->
            anchor.trim().takeIf { it.isNotEmpty() }?.let {
                Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text)
            } == true
        }
        match.groupValues[2].takeIf { active }
    }
    fun correct(text: String, terms: List<String>): String {
        val aliases = terms.filter { "=>" in it }.mapNotNull { term ->
            val active = activeTerms(text, listOf(term)).singleOrNull() ?: return@mapNotNull null
            val parts = active.split("=>", limit = 2).map(String::trim)
            if (parts.any(String::isBlank)) null else Alias(parts[0], parts[1], scope.matches(term.trim()))
        }.sortedByDescending { it.heard.length }
        if (aliases.isEmpty()) return text
        // One pass prevents an expansion from becoming the trigger for another alias.
        val patterns = aliases.map { alias ->
            alias.heard.split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
        }
        val pattern = Regex("(?<![\\p{L}\\p{N}])(?:${patterns.joinToString("|")})(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        val replacements = pattern.findAll(text).mapNotNull { match ->
            val index = patterns.indexOfFirst { Regex(it, RegexOption.IGNORE_CASE).matches(match.value) }
            val alias = aliases[index]
            if (alias.contextual && alias.heard.split(Regex("\\s+")).all(CommonEnglishWords::contains) &&
                match.value.firstOrNull()?.isLowerCase() == true) return@mapNotNull null
            Replacement(match.range.first, match.range.last + 1, aliases[index].desired, aliases[index].contextual)
        }.toMutableList()
        val exact = replacements.filterNot { it.contextual }
        val canonicalTerms = activeTerms(text, terms).map { canonical(it).lowercase().replace(Regex("\\s+"), " ") }.toSet()
        val tokens = words.findAll(text).toList()
        val candidates = linkedMapOf<Pair<Int, Int>, Candidate>()
        val nameVariants = (aliases + aliases.map { it.copy(heard = it.desired) }).distinct()
        for ((heard, desired, contextual) in nameVariants) {
            val parts = heard.lowercase().split(Regex("\\s+"))
            val desiredWords = desired.split(Regex("\\s+"))
            // Fuzzy aliases are only for long, multi-word names, never IDs or short phrases.
            // A team/context anchor cannot make a one-word near-match safe: ordinary words
            // such as "solid" can be one edit from a stored name mishearing. Short aliases
            // therefore require an explicitly stored spelling, even in an active context.
            if (parts.size < 2 || parts.sumOf { it.length } < 8 ||
                parts.any { p -> p.any { !it.isLetter() } } || desired.any { it.isDigit() || it == '@' } ||
                desiredWords.size < (if (contextual) 1 else 2) || desiredWords.any { it.firstOrNull()?.isUpperCase() != true }) continue
            for (i in 0..tokens.size - parts.size) {
                val window = tokens.subList(i, i + parts.size)
                val start = window.first().range.first
                val end = window.last().range.last + 1
                if (exact.any { start < it.end && end > it.start }) continue
                if (window.zipWithNext().any { (a, b) ->
                        text.substring(a.range.last + 1, b.range.first).any { !it.isWhitespace() }
                    }) continue
                val actual = window.joinToString(" ") { it.value.lowercase() }
                if (actual in canonicalTerms || actual.first() != parts.first().first()) continue
                if (window.all { CommonEnglishWords.contains(it.value.lowercase()) }) continue
                val distance = when {
                    oneEditApart(actual, parts.joinToString(" ")) -> 1
                    window.map { soundKey(it.value) } == parts.map(::soundKey) ->
                        VocabularyPostCorrector.boundedDamerauLevenshtein(actual, parts.joinToString(" "), 3)
                    else -> null
                } ?: continue
                val candidate = candidates.getOrPut(start to end) { Candidate(distance, linkedSetOf()) }
                if (distance < candidate.distance) { candidate.distance = distance; candidate.targets.clear() }
                if (distance == candidate.distance) candidate.targets.add(desired)
            }
        }
        for ((range, candidate) in candidates) {
            if (candidate.targets.size == 1) replacements += Replacement(range.first, range.second, candidate.targets.single())
        }
        if (replacements.isEmpty()) return text
        val out = StringBuilder(text.length)
        var cursor = 0
        for (r in replacements.sortedWith(compareBy<Replacement> { it.start }.thenByDescending { it.end })) {
            if (r.start < cursor) continue
            out.append(text, cursor, r.start).append(r.text)
            cursor = r.end
        }
        return out.append(text, cursor, text.length).toString()
    }

    /** Soundex consonant groups; spelling distance and the full multi-word name also must agree. */
    private fun soundKey(word: String): String {
        val letters = java.text.Normalizer.normalize(word.lowercase().replace('ø', 'o'), java.text.Normalizer.Form.NFD)
            .filter { it in 'a'..'z' }
        if (letters.isEmpty()) return ""
        fun code(c: Char): Char = when (c) {
            'b', 'f', 'p', 'v' -> '1'
            'c', 'g', 'j', 'k', 'q', 's', 'x', 'z' -> '2'
            'd', 't' -> '3'
            'l' -> '4'
            'm', 'n' -> '5'
            'r' -> '6'
            else -> '0'
        }
        val key = StringBuilder().append(letters.first())
        var previous = code(letters.first())
        for (c in letters.drop(1)) {
            if (c == 'h' || c == 'w') continue
            val current = code(c)
            if (current != '0' && current != previous) key.append(current)
            previous = current
            if (key.length == 4) break
        }
        return key.toString().padEnd(4, '0')
    }

    private fun oneEditApart(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                b.length > a.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) == 1
    }
}
