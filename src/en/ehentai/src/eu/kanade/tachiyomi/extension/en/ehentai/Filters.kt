package eu.kanade.tachiyomi.extension.en.ehentai

import eu.kanade.tachiyomi.source.model.Filter

internal class CategoryOption(name: String, val value: Int) : Filter.CheckBox(name, false)

internal class CategoryFilter : Filter.Group<CategoryOption>(
    "Exclude categories",
    categories.map { CategoryOption(it.first, it.second) },
) {
    fun excludedMask(): Int = state.filter { it.state }.sumOf { it.value }

    private companion object {
        val categories = listOf(
            "Misc" to 1,
            "Doujinshi" to 2,
            "Manga" to 4,
            "Artist CG" to 8,
            "Game CG" to 16,
            "Image Set" to 32,
            "Cosplay" to 64,
            "Asian Porn" to 128,
            "Non-H" to 256,
            "Western" to 512,
        )
    }
}

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
