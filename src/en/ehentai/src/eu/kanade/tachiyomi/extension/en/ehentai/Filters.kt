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
        val categories = listOf(
            CategoryOption("Misc", 1),
            CategoryOption("Doujinshi", 2),
            CategoryOption("Manga", 4),
            CategoryOption("Comics", 0, "comic\$"),
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
