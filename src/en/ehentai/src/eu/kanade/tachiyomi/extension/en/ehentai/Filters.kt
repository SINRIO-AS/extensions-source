package eu.kanade.tachiyomi.extension.en.ehentai

import eu.kanade.tachiyomi.source.model.Filter

internal class CategoryOption(
    name: String,
    val value: Int,
    val queryTag: String? = null,
) : Filter.CheckBox(name, false)

internal class CategoryModeFilter : Filter.Select<String>(
    "Category selection",
    arrayOf("Only checked categories", "Exclude checked categories", "Show all categories"),
)

internal class CategoryFilter : Filter.Group<CategoryOption>(
    "Gallery categories",
    categories,
) {
    fun mask(mode: Int): Int {
        val selected = state.filter { it.state }.fold(0) { mask, option -> mask or option.value }
        return when (mode) {
            0 -> selected.takeIf { it != 0 }?.let { ALL_CATEGORIES_MASK and it.inv() } ?: 0
            1 -> selected
            else -> 0
        }
    }

    fun queryTags(): List<String> = state.filter { it.state }.mapNotNull { it.queryTag }

    fun hasQueryTag(): Boolean = state.any { it.state && it.queryTag != null }

    private companion object {
        const val ALL_CATEGORIES_MASK = 1023
        val categories = arrayOf(
            CategoryOption("Misc", 1),
            CategoryOption("Doujinshi", 2),
            CategoryOption("Manga", 4),
            CategoryOption("Comics", 0, "comic$"),
            CategoryOption("Artist CG", 8),
            CategoryOption("Game CG", 16),
            CategoryOption("Image Set", 32),
            CategoryOption("Cosplay", 64),
            CategoryOption("Asian Porn", 128),
            CategoryOption("Non-H", 256),
            CategoryOption("Western", 512),
        )
    }
}

internal class LanguageFilter : Filter.Select<String>(
    "Gallery language",
    languages.map { it.first }.toTypedArray(),
) {
    fun queryValue(): String? = languages[state].second

    private companion object {
        val languages = listOf(
            "All languages" to null,
            "Japanese" to "language:japanese",
            "English" to "language:english",
            "Chinese" to "language:chinese",
            "Korean" to "language:korean",
            "Spanish" to "language:spanish",
            "French" to "language:french",
            "German" to "language:german",
            "Italian" to "language:italian",
            "Portuguese" to "language:portuguese",
            "Russian" to "language:russian",
            "Thai" to "language:thai",
            "Vietnamese" to "language:vietnamese",
            "Translated" to "language:translated",
            "No language" to "language:n/a",
            "Other language" to "language:other",
        )
    }
}

internal class IncludeTagsFilter : Filter.Text("Include tags (comma separated)")

internal class ExcludeTagsFilter : Filter.Text("Exclude tags (comma separated)")

internal class SearchTitlesFilter : Filter.CheckBox("Search gallery titles", true)

internal class SearchTagsFilter : Filter.CheckBox("Search gallery tags", true)

internal class SearchDescriptionFilter : Filter.CheckBox("Search gallery descriptions", false)

internal class SearchTorrentNamesFilter : Filter.CheckBox("Search torrent file names", false)

internal class OnlyTorrentsFilter : Filter.CheckBox("Only galleries with torrents", false)

internal class ShowExpungedFilter : Filter.CheckBox("Show expunged galleries", false)

internal class LowPowerTagsFilter : Filter.CheckBox("Search low-power tags", false)

internal class DownvotedTagsFilter : Filter.CheckBox("Search downvoted tags", false)

internal class MinimumRatingFilter : Filter.Select<String>(
    "Minimum rating",
    ratings.map { it.first }.toTypedArray(),
) {
    fun value(): String? = ratings[state].second

    private companion object {
        val ratings = listOf(
            "Any rating" to null,
            "2 stars" to "2",
            "3 stars" to "3",
            "4 stars" to "4",
            "5 stars" to "5",
        )
    }
}

internal class MinimumPagesFilter : Filter.Text("Minimum pages")

internal class MaximumPagesFilter : Filter.Text("Maximum pages")

internal fun String.pageCountOrNull(): String? = trim().takeIf { it.isNotEmpty() }?.also {
    require(it.toIntOrNull()?.let { value -> value >= 0 } == true) {
        "Page count must be a non-negative whole number"
    }
}

internal fun String.searchTerms(exclude: Boolean = false): List<String> =
    split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { term ->
        if (exclude) "-$term" else term
    }
