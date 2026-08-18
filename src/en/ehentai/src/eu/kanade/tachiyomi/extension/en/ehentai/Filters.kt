package eu.kanade.tachiyomi.extension.en.ehentai


import eu.kanade.tachiyomi.source.model.Filter


internal class CategoryOption(name: String, val value: Int) : Filter.CheckBox(name, false)


internal class CategoryModeFilter : Filter.Select<String>(
    "Category selection",
    arrayOf("Show all categories", "Only checked categories", "Exclude checked categories"),
)


internal class CategoryFilter : Filter.Group<CategoryOption>(
    "Gallery categories",
    categories.map { CategoryOption(it.first, it.second) },
) {
    fun mask(mode: Int): Int {
        val selected = state.filter { it.state }.sumOf { it.value }
        return when (mode) {
            1 -> selected.takeIf { it != 0 }?.let { ALL_CATEGORIES_MASK and it.inv() } ?: 0
            2 -> selected
            else -> 0
        }
    }


    private companion object {
        const val ALL_CATEGORIES_MASK = 1023
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

