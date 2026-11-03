package com.cinesort.core.parser

import java.text.Normalizer
import java.time.Year
import java.util.Locale

/**
 * Anchor-based filename parser (spec section 6).
 *
 * Title = everything BEFORE the first anchor; everything after the anchor is ignored.
 * Series anchor = episode marker; movie anchor = release year. The noise vocabulary is
 * never used to strip words from the string — only as a year guard and fallback anchor.
 */
class AnchorMediaNameParser(
    private val currentYear: Int = Year.now().value,
    private val extraNoiseWords: Set<String> = emptySet(),
    private val titleCaseMode: TitleCaseMode = TitleCaseMode.SMART,
) : MediaNameParser {

    private data class Token(val text: String, val start: Int, val end: Int)

    private data class EpisodeHit(
        val startToken: Int,
        val endToken: Int,
        val season: Int,
        val episodes: List<Int>,
    )

    private data class Cleaned(val working: String, val tokens: List<Token>)

    @Suppress("TooGenericExceptionCaught")
    override fun parse(
        fileName: String,
        context: PathContext,
    ): ParseResult {
        return try {
            parseInternal(fileName, context)
        } catch (e: Exception) {
            // The parser must never throw (spec section 15, property tests).
            ParseResult.Unrecognized(
                fileName = fileName,
                reason = "parser failure: ${e.javaClass.simpleName}",
                trace = ParseTrace(null, null, AnchorKind.NONE, listOf("error:caught")),
            )
        }
    }

    private fun parseInternal(
        fileName: String,
        context: PathContext,
    ): ParseResult {
        val cleaned = preClean(fileName)
        val tokens = cleaned.tokens
        return if (tokens.isEmpty()) {
            unrecognized(fileName, "empty name")
        } else {
            episodeStage(fileName, context, tokens)
                ?: movieStage(fileName, tokens)
                ?: bareEpisodeStage(fileName, context, cleaned)
                ?: unrecognized(fileName, "no anchor found")
        }
    }

    // ---- Stage B: episode detection -----------------------------------------

    private fun episodeStage(
        fileName: String,
        context: PathContext,
        tokens: List<Token>,
    ): ParseResult? {
        val hit = findEpisodeMarker(tokens)
        if (hit == null) {
            // Season-only marker with no episode (spec 6.3 B): the name carries a
            // title + season but no episode number, so it is unrecognized.
            return if (hasSeasonOnlyMarker(tokens)) {
                unrecognized(fileName, "season marker without episode")
            } else {
                null
            }
        }
        val titleTokens = tokens.subList(0, hit.startToken)
        val (title, yearHint) = extractSeriesTitle(titleTokens)
        return if (title != null) {
            ParseResult.Episode(
                fileName = fileName,
                seriesTitle = applyCasing(title),
                seriesYearHint = yearHint,
                season = hit.season,
                episodes = hit.episodes,
                fromFolderContext = false,
                confidence = Confidence.HIGH,
                trace = ParseTrace(
                    titleRange = rangeOf(titleTokens),
                    anchorRange = rangeOf(tokens.subList(hit.startToken, hit.endToken + 1)),
                    anchorKind = AnchorKind.EPISODE_MARKER,
                    rules = listOf("episode:marker-in-name"),
                ),
            )
        } else {
            // Marker present but no title in the name: context must supply the title.
            episodeFromContext(fileName, context, hit.season, hit.episodes)
                ?: unrecognized(fileName, "episode marker without series title")
        }
    }

    // ---- Stage C: movie detection -------------------------------------------

    private fun movieStage(
        fileName: String,
        tokens: List<Token>,
    ): ParseResult.Movie? = detectMovie(fileName, tokens)

    // ---- Stage E: context inference for bare-episode names ------------------

    private fun bareEpisodeStage(
        fileName: String,
        context: PathContext,
        cleaned: Cleaned,
    ): ParseResult.Episode? {
        val bareEpisode = bareEpisodeNumber(cleaned.working) ?: return null
        return episodeFromContext(fileName, context, null, listOf(bareEpisode))
    }

    // ---------------------------------------------------------------- Stage B

    // Hyphenated forms (S01-E01, S01E01-E03) keep the hyphen inside the token, so
    // the episode patterns allow one optional separator (spec 6.3-B, review M14).
    // A trailing "-NN" after the episode number is a range end without the second
    // "E" (S01E01-02 = episodes 1..2, review M-H residual).
    private val combinedEpisodeRegex = Regex(
        """^S(\d{1,2})[._-]?E(\d{1,3})((?:[._-]?E\d{1,3})*)(?:-(\d{1,3}))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val seasonOnlyTokenRegex = Regex("""^S(\d{1,2})$""", RegexOption.IGNORE_CASE)
    private val episodeOnlyTokenRegex = Regex(
        """^E(\d{1,3})((?:[._-]?E\d{1,3})*)(?:-(\d{1,3}))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val xEpisodeRegex = Regex("""^(\d{1,2})x(\d{2,3})$""", RegexOption.IGNORE_CASE)
    private val extraEpisodeRegex = Regex("""^E(\d{1,3})$""", RegexOption.IGNORE_CASE)
    private val plainNumberRegex = Regex("""^\d{1,3}$""")
    private val yearTokenRegex = Regex("""^(19|20)\d{2}$""")
    private val extraEpisodeNumberRegex = Regex("""E(\d{1,3})""", RegexOption.IGNORE_CASE)
    private val seasonWordRegex = Regex("""^Season$""", RegexOption.IGNORE_CASE)
    private val whitespaceRegex = Regex("""\s+""")

    private fun findEpisodeMarker(tokens: List<Token>): EpisodeHit? {
        for (i in tokens.indices) {
            val hit = episodeHitAt(tokens, i)
            if (hit != null) return hit
        }
        return null
    }

    private fun episodeHitAt(tokens: List<Token>, i: Int): EpisodeHit? =
        combinedEpisodeHit(tokens, i)
            ?: seasonPairHit(tokens, i)
            ?: xEpisodeHit(tokens[i].text, i)
            ?: seasonWordsHit(tokens, i)

    /**
     * Expands an `S01E01-05`-style range end into [episodes]: every number from
     * the first episode to the range end is added (missing ones filled in). An
     * implausible span or a non-increasing end leaves the list untouched.
     */
    private fun expandEpisodeRange(episodes: MutableList<Int>, rangeEndText: String) {
        val rangeEnd = rangeEndText.toIntOrNull() ?: return
        val first = episodes.firstOrNull() ?: return
        if (rangeEnd <= first || rangeEnd - first > MAX_EPISODE_RANGE_SPAN) return
        for (episode in first..rangeEnd) {
            if (episode !in episodes) episodes += episode
        }
        episodes.sort()
    }

    private fun combinedEpisodeHit(tokens: List<Token>, i: Int): EpisodeHit? {
        val m = combinedEpisodeRegex.matchEntire(tokens[i].text) ?: return null
        val episodes = mutableListOf(m.groupValues[2].toInt())
        episodes += extraEpisodeNumberRegex
            .findAll(m.groupValues[3]).map { it.groupValues[1].toInt() }.toList()
        expandEpisodeRange(episodes, m.groupValues[4])
        var end = i
        // Consume following pure E## tokens (S01E01 E02 style).
        while (end + 1 < tokens.size) {
            val extra = extraEpisodeRegex.matchEntire(tokens[end + 1].text) ?: break
            episodes += extra.groupValues[1].toInt()
            end++
        }
        return EpisodeHit(i, end, m.groupValues[1].toInt(), episodes)
    }

    private fun seasonPairHit(tokens: List<Token>, i: Int): EpisodeHit? {
        val m = seasonOnlyTokenRegex.matchEntire(tokens[i].text) ?: return null
        val next = tokens.getOrNull(i + 1) ?: return null
        val e = episodeOnlyTokenRegex.matchEntire(next.text) ?: return null
        val episodes = mutableListOf(e.groupValues[1].toInt())
        episodes += extraEpisodeNumberRegex
            .findAll(e.groupValues[2]).map { it.groupValues[1].toInt() }.toList()
        expandEpisodeRange(episodes, e.groupValues[3])
        return EpisodeHit(i, i + 1, m.groupValues[1].toInt(), episodes)
    }

    private fun xEpisodeHit(text: String, i: Int): EpisodeHit? {
        val m = xEpisodeRegex.matchEntire(text) ?: return null
        // The anchored 1-2 digit season group can never match 1920x1080.
        return EpisodeHit(i, i, m.groupValues[1].toInt(), listOf(m.groupValues[2].toInt()))
    }

    private fun seasonWordsHit(tokens: List<Token>, i: Int): EpisodeHit? {
        if (!tokens[i].text.equals("Season", ignoreCase = true)) return null
        val rest = tokens.subList(i + 1, minOf(i + 4, tokens.size))
        if (rest.size < 3) return null
        val (seasonTok, labelTok, epTok) = rest
        val shapeOk = plainNumberRegex.matches(seasonTok.text) &&
            isEpisodeLabel(labelTok.text) && plainNumberRegex.matches(epTok.text)
        return if (shapeOk) {
            EpisodeHit(i, i + 3, seasonTok.text.toInt(), listOf(epTok.text.toInt()))
        } else {
            null
        }
    }

    private fun isEpisodeLabel(text: String): Boolean =
        text.equals("Episode", true) || text.equals("Ep", true) || text.equals("E", true)

    private fun hasSeasonOnlyMarker(tokens: List<Token>): Boolean =
        tokens.indices.any { i -> seasonOnlyAt(tokens, i) || seasonWordOnlyAt(tokens, i) }

    /** A bare S## token whose next token is not an episode token. */
    private fun seasonOnlyAt(tokens: List<Token>, i: Int): Boolean {
        if (!seasonOnlyTokenRegex.matches(tokens[i].text)) return false
        val next = tokens.getOrNull(i + 1)
        return next == null || !episodeOnlyTokenRegex.matches(next.text)
    }

    /** A "Season N" word pair not followed by an episode label. */
    private fun seasonWordOnlyAt(tokens: List<Token>, i: Int): Boolean {
        if (!tokens[i].text.matches(seasonWordRegex)) return false
        val next = tokens.getOrNull(i + 1)
        if (next == null || !plainNumberRegex.matches(next.text)) return false
        val after = tokens.getOrNull(i + 2)
        return after == null || !isEpisodeLabel(after.text)
    }

    /** Splits a trailing valid year off the series title tokens as a year hint (spec 6.3 B). */
    private fun extractSeriesTitle(titleTokens: List<Token>): Pair<String?, Int?> {
        if (titleTokens.isEmpty()) return null to null
        val last = titleTokens.last()
        val year = yearValue(last.text)
        return if (year != null && titleTokens.size > 1) {
            joinTokens(titleTokens.dropLast(1)) to year
        } else {
            joinTokens(titleTokens) to null
        }
    }

    // ---------------------------------------------------------------- Stage C

    /**
     * Upper bound (exclusive) for the year search: the first resolution token, or
     * the start of the first run of >= 2 consecutive release-noise tokens. A single
     * source/codec word inside a title ("Charlotte's Web", "Dark Web") must not hide
     * a year behind it (review H5).
     */
    private fun yearSearchBound(tokens: List<Token>): Int {
        var runStart = -1
        var runLength = 0
        for ((idx, token) in tokens.withIndex()) {
            if (NoiseVocabulary.isResolution(token.text)) return idx
            if (NoiseVocabulary.isYearGuardNoise(token.text)) {
                if (runLength == 0) runStart = idx
                runLength++
                if (runLength >= 2) return runStart
            } else {
                runLength = 0
                runStart = -1
            }
        }
        return Int.MAX_VALUE
    }

    private fun detectMovie(
        fileName: String,
        tokens: List<Token>,
    ): ParseResult.Movie? =
        movieByYear(fileName, tokens)
            ?: movieByResolution(fileName, tokens)
            ?: movieByNoiseRun(fileName, tokens)

    /** Primary anchor: the last valid year before the release-noise region. */
    private fun movieByYear(
        fileName: String,
        tokens: List<Token>,
    ): ParseResult.Movie? {
        val noiseStart = yearSearchBound(tokens)
        val candidates = tokens.indices.filter { idx ->
            val year = yearValue(tokens[idx].text) ?: return@filter false
            if (idx >= noiseStart) return@filter false
            // The year may not be the first token (spec 6.3 C1). A single title word
            // that happens to be release vocabulary ("Cam", "Web") still counts as a
            // preceding title token (review H5).
            idx > 0
        }
        val yearIdx = candidates.lastOrNull() ?: return null
        val title = joinTokens(tokens.subList(0, yearIdx)) ?: return null
        return ParseResult.Movie(
            fileName = fileName,
            title = applyCasing(title),
            year = yearValue(tokens[yearIdx].text),
            confidence = Confidence.HIGH,
            trace = ParseTrace(
                titleRange = rangeOf(tokens.subList(0, yearIdx)),
                anchorRange = rangeOf(listOf(tokens[yearIdx])),
                anchorKind = AnchorKind.YEAR,
                rules = listOf("movie:last-year-before-noise"),
            ),
        )
    }

    /** Fallback anchor 1: first resolution token. */
    private fun movieByResolution(
        fileName: String,
        tokens: List<Token>,
    ): ParseResult.Movie? {
        val resIdx = tokens.indexOfFirst { NoiseVocabulary.isResolution(it.text) }
        if (resIdx <= 0) return null
        val title = joinTokens(tokens.subList(0, resIdx)) ?: return null
        return ParseResult.Movie(
            fileName = fileName,
            title = applyCasing(title),
            year = null,
            confidence = Confidence.MEDIUM,
            trace = ParseTrace(
                titleRange = rangeOf(tokens.subList(0, resIdx)),
                anchorRange = rangeOf(listOf(tokens[resIdx])),
                anchorKind = AnchorKind.RESOLUTION_FALLBACK,
                rules = listOf("movie:resolution-fallback"),
            ),
        )
    }

    /** Fallback anchor 2: first run of >= 2 consecutive noise tokens. */
    private fun movieByNoiseRun(
        fileName: String,
        tokens: List<Token>,
    ): ParseResult.Movie? {
        val extraNoise = extraNoiseWords.map { it.lowercase(Locale.ROOT) }.toSet()
        var runStart = -1
        var runLength = 0
        for (i in tokens.indices) {
            if (NoiseVocabulary.isNoise(tokens[i].text, extraNoise)) {
                if (runLength == 0) runStart = i
                runLength++
                if (runLength >= 2) break
            } else {
                runLength = 0
                runStart = -1
            }
        }
        if (runLength < 2 || runStart <= 0) return null
        val title = joinTokens(tokens.subList(0, runStart)) ?: return null
        return ParseResult.Movie(
            fileName = fileName,
            title = applyCasing(title),
            year = null,
            confidence = Confidence.MEDIUM,
            trace = ParseTrace(
                titleRange = rangeOf(tokens.subList(0, runStart)),
                anchorRange = rangeOf(tokens.subList(runStart, runStart + 2)),
                anchorKind = AnchorKind.NOISE_RUN_FALLBACK,
                rules = listOf("movie:noise-run-fallback"),
            ),
        )
    }

    // ---------------------------------------------------------------- Stage E

    private val bareEpisodeRegex = Regex("""^(?:E|Ep|Episode)?[ ._-]*(\d{1,3})$""", RegexOption.IGNORE_CASE)
    // Latin "Season 2"/"S02" plus the Persian "فصل ۲" form (review L4); the number
    // may use Persian/Arabic-Indic digits and is folded before parsing.
    private val seasonFolderRegex = Regex(
        """^(?:Season|S)[ ._-]?(\d{1,2})$|^فصل[ ._-]?([0-9۰-۹٠-٩]{1,2})$""",
        RegexOption.IGNORE_CASE,
    )

    private fun bareEpisodeNumber(working: String): Int? {
        val trimmed = working.trim()
        if (trimmed.isEmpty()) return null
        val m = bareEpisodeRegex.matchEntire(trimmed) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun episodeFromContext(
        fileName: String,
        context: PathContext,
        seasonFromName: Int?,
        episodes: List<Int>,
    ): ParseResult.Episode? {
        var season = seasonFromName
        var title: String? = null
        var usedRoot = false
        for (folder in context.ancestors) {
            val scan = scanAncestorFolder(folder, season)
            season = scan.season
            if (scan.title != null) {
                title = scan.title
                break
            }
        }
        if (title == null && context.sourceRootName != null) {
            title = cleanFolderTitle(context.sourceRootName)
            usedRoot = true
            if (season == null) season = seasonFromFolderName(context.sourceRootName)
        }
        val resolvedTitle = title ?: return null
        val resolvedSeason = season ?: return null
        return ParseResult.Episode(
            fileName = fileName,
            seriesTitle = applyCasing(resolvedTitle),
            seriesYearHint = null,
            season = resolvedSeason,
            episodes = episodes,
            fromFolderContext = true,
            confidence = if (usedRoot) Confidence.LOW else Confidence.MEDIUM,
            trace = ParseTrace(
                titleRange = null,
                anchorRange = null,
                anchorKind = AnchorKind.FOLDER_CONTEXT,
                rules = listOf("episode:folder-context"),
            ),
        )
    }

    private data class AncestorScan(val season: Int?, val title: String?)

    /**
     * One ancestor folder's contribution to the context walk: a season-folder
     * match only refines the season; any other folder may supply the title
     * (which ends the walk) and, failing that, a season hint.
     */
    private fun scanAncestorFolder(folder: String, seasonIn: Int?): AncestorScan {
        val seasonFolder = seasonFolderRegex.matchEntire(folder.trim())
        if (seasonFolder != null) {
            return AncestorScan(seasonIn ?: seasonFromSeasonFolderMatch(seasonFolder), null)
        }
        var season = seasonIn
        val folderTitle = cleanFolderTitle(folder)
        if (folderTitle != null) {
            if (season == null) season = seasonFromFolderName(folder)
            return AncestorScan(season, folderTitle)
        }
        if (season == null) season = seasonFromFolderName(folder)
        return AncestorScan(season, null)
    }

    private fun seasonFromSeasonFolderMatch(match: MatchResult): Int? {
        val digits = match.groupValues[1].ifEmpty { match.groupValues[2] }
        return NormalizedKey.foldDigits(digits).toIntOrNull()
    }

    private val seasonInFolderRegex = Regex("""(?i)(?:^|[ ._-])S(\d{1,2})(?:[ ._-]|$)""")

    private fun seasonFromFolderName(folder: String): Int? {
        seasonFolderRegex.matchEntire(folder.trim())?.let { return it.groupValues[1].toInt() }
        val tokens = tokenize(folder)
        // "Season 1" style inside a longer name is rare; the S## token is the common case.
        for (i in tokens.indices) {
            seasonOnlyTokenRegex.matchEntire(tokens[i].text)?.let { return it.groupValues[1].toInt() }
        }
        return seasonInFolderRegex.find(folder)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Title from a release-style folder name: text before the S##/year/resolution cut. */
    private fun cleanFolderTitle(folder: String): String? {
        val tokens = tokenize(folder)
        if (tokens.isEmpty()) return null
        var cut = tokens.size
        for (i in tokens.indices) {
            if (isTitleCutToken(tokens[i].text, i)) {
                cut = i
                break
            }
        }
        val titleTokens = tokens.subList(0, cut)
        if (titleTokens.isEmpty()) return null
        // A season-only folder ("Season 1") yields no title.
        if (seasonFolderRegex.matches(folder.trim())) return null
        return joinTokens(titleTokens)
    }

    /** A token that ends a folder title: episode marker, later year, or resolution. */
    private fun isTitleCutToken(text: String, index: Int): Boolean =
        combinedEpisodeRegex.matches(text) || seasonOnlyTokenRegex.matches(text) ||
            (yearValue(text) != null && index > 0) || NoiseVocabulary.isResolution(text)

    // ---------------------------------------------------------------- Stage A

    private val duplicateMarkerRegex = Regex("""\s*\(\d{1,2}\)$""")
    private val copyMarkerRegex = Regex("""\s*[-_ ]\s*Copy(\s*\(\d+\))?$""", RegexOption.IGNORE_CASE)
    private val bracketPrefixRegex = Regex("""^\s*\[[^\]]{1,40}\]\s*""")
    private val domainPrefixRegex = Regex("""^\s*(?:www\.)?[\w-]+\.(?:com|net|org|info|ir|xyz|cc)\b[\s._-]*""")
    private val acronymRegex = Regex("""(?:\p{L}\.){2,}""")

    private val videoExtensions = setOf(
        "mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "mpg",
        "mpeg", "3gp",
    )

    private fun preClean(fileName: String): Cleaned {
        var name = fileName.substringAfterLast('/').substringAfterLast('\\')
        // 1. Strip the extension only if it is a known video extension.
        val dot = name.lastIndexOf('.')
        if (dot > 0) {
            val ext = name.substring(dot + 1).lowercase(Locale.ROOT)
            if (ext in videoExtensions) name = name.substring(0, dot)
        }
        // 2. Downloader duplicate markers.
        name = duplicateMarkerRegex.replace(name, "")
        name = copyMarkerRegex.replace(name, "")
        // 3. Leading promo prefixes — only if a valid anchor still exists afterwards.
        val strippedBracket = bracketPrefixRegex.replace(name, "")
        if (strippedBracket != name && hasAnyAnchor(strippedBracket)) name = strippedBracket
        name = stripDomainPrefix(name)
        // A leftover leading bracketed chunk that hid the anchor check above still goes
        // when what follows parses (covers "[Group] Movie.2020" style names).
        // 4. Unicode NFC.
        name = Normalizer.normalize(name, Normalizer.Form.NFC)
        return Cleaned(name, tokenize(name))
    }

    private val domainLikeHeadRegex = Regex("""^[\w-]+(?:\.[\w-]+)+$""")

    private companion object {
        const val WWW_PREFIX = "www."
        const val SPACED_DASH = " - "
        /** Widest plausible span for an `S01E01-05`-style episode range. */
        const val MAX_EPISODE_RANGE_SPAN = 50
    }

    /**
     * Strips a leading release-site prefix, but only in the two shapes that are
     * unambiguous (review M-H residual): (a) a `www.`-prefixed host, or (b) a
     * domain-like head token run immediately followed by a spaced dash
     * ("ExampleSite.com - Title..."). A merely domain-LIKE title ("The.Net.…")
     * is not a prefix: the old shape-based regex ate "The.Net." out of
     * "The.Net.Of.Lies.2020…" and left "Of Lies" as the title. Both shapes still
     * require a surviving title token before an anchor after the strip.
     */
    private fun stripDomainPrefix(name: String): String {
        if (name.startsWith(WWW_PREFIX, ignoreCase = true)) {
            val stripped = domainPrefixRegex.replace(name, "")
            return if (stripped != name && stripped.isNotBlank() &&
                hasAnchorAfterTitleToken(stripped)
            ) {
                stripped
            } else {
                name
            }
        }
        val dashIndex = name.indexOf(SPACED_DASH)
        if (dashIndex > 0) {
            val head = name.substring(0, dashIndex).trim()
            if (domainLikeHeadRegex.matches(head)) {
                val stripped = name.substring(dashIndex + SPACED_DASH.length)
                if (stripped.isNotBlank() && hasAnchorAfterTitleToken(stripped)) {
                    return stripped
                }
            }
        }
        return name
    }

    private fun hasAnyAnchor(s: String): Boolean {
        val tokens = tokenize(s)
        if (findEpisodeMarker(tokens) != null) return true
        return tokens.any { yearValue(it.text) != null }
    }

    /**
     * True when [s] has an anchor (episode marker or year) that is NOT the first
     * token — i.e. a title token survives before it. Guards the domain-prefix strip
     * (review H6): "The.Net.1995…" strips to "1995…", whose only anchor is the year
     * itself at index 0, so the strip must not happen.
     */
    private fun hasAnchorAfterTitleToken(s: String): Boolean {
        val tokens = tokenize(s)
        val episodeIdx = findEpisodeMarker(tokens)?.startToken
        val yearIdx = tokens.indexOfFirst { yearValue(it.text) != null }
            .takeIf { it >= 0 }
        val anchorIdx = listOfNotNull(episodeIdx, yearIdx).minOrNull() ?: return false
        return anchorIdx >= 1
    }

    /**
     * Tokenizes on `[\s._]+` plus bracket characters. " - " acts as a separator (replaced
     * by " . ", same length, so ranges stay valid); hyphens inside tokens are kept.
     * Dotted acronyms (S.W.A.T.) are protected and stay one token.
     */
    private fun tokenize(s: String): List<Token> {
        // Protect dotted acronyms: inner dots are not separators. The acronym match's
        // final dot IS a separator (S.W.A.T.2017 -> "S.W.A.T" + "2017"), so the
        // protected span excludes it.
        val protectedSpans = acronymRegex.findAll(s)
            .map { IntRange(it.range.first, it.range.last - 1) }.toList()
        val replaced = s.replace(" - ", " . ")
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < replaced.length) {
            if (isSeparatorAt(replaced, i, protectedSpans)) {
                i++
                continue
            }
            var j = i
            while (j < replaced.length && !isSeparatorAt(replaced, j, protectedSpans)) {
                j++
            }
            val text = replaced.substring(i, j)
            val trimmed = text.trim('-')
            if (trimmed.isNotEmpty()) {
                val offset = text.indexOf(trimmed)
                tokens += Token(trimmed, i + offset, i + offset + trimmed.length)
            }
            i = j
        }
        return tokens
    }

    /** Separators are whitespace/dot/underscore/bracket chars outside acronym spans. */
    private fun isSeparatorAt(s: String, pos: Int, protectedSpans: List<IntRange>): Boolean {
        val c = s[pos]
        val separatorChar = c == ' ' || c == '\t' || c == '.' || c == '_' ||
            c == '[' || c == ']' || c == '(' || c == ')' || c == '{' || c == '}'
        return separatorChar && protectedSpans.none { pos in it }
    }

    // ---------------------------------------------------------------- helpers

    private fun yearValue(token: String): Int? {
        if (!token.matches(yearTokenRegex)) return null
        val value = token.toIntOrNull() ?: return null
        return if (value in 1900..(currentYear + 1)) value else null
    }

    private fun joinTokens(tokens: List<Token>): String? {
        if (tokens.isEmpty()) return null
        val joined = tokens.joinToString(" ") { it.text }
            .replace(whitespaceRegex, " ")
            .trim(' ', '-', '–', '_', '.')
        return joined.ifEmpty { null }
    }

    private fun rangeOf(tokens: List<Token>): IntRange? {
        if (tokens.isEmpty()) return null
        return tokens.first().start..(tokens.last().end - 1)
    }

    private fun applyCasing(title: String): String = TitleCasing.apply(title, titleCaseMode)

    private fun unrecognized(
        fileName: String,
        reason: String,
    ): ParseResult.Unrecognized = ParseResult.Unrecognized(
        fileName = fileName,
        reason = reason,
        trace = ParseTrace(null, null, AnchorKind.NONE, listOf("unrecognized:$reason")),
    )
}
