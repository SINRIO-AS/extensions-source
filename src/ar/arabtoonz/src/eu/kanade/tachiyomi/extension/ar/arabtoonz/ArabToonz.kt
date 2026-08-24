package eu.kanade.tachiyomi.extension.ar.arabtoonz

import eu.kanade.tachiyomi.multisrc.madara.Madara
import keiyoushi.annotation.Source
import java.util.Calendar

@Source
abstract class ArabToonz : Madara() {
    override val useLoadMoreRequest = LoadMoreStrategy.Always

    override val mangaDetailsSelectorDescription = ".summary-text-container .summary-text"
    override val mangaDetailsSelectorStatus = "span.status"
    override val altNameSelector = ".post-content_item:has(.summary-heading h5:contains(أسماء أخرى)) .summary-content"
    override val pageListParseSelector = ".reading-content .page-break"
    override val chapterUrlSuffix = ""

    override fun parseChapterDate(date: String?): Long {
        val value = date?.trim().orEmpty()
        if (value.isBlank()) return 0L

        val number = Regex("(\\d+)").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return super.parseChapterDate(value)
        val calendar = Calendar.getInstance()

        return when {
            value.contains("ثانية") || value.contains("ثواني") ->
                calendar.apply { add(Calendar.SECOND, -number) }.timeInMillis
            value.contains("دقيقة") || value.contains("دقائق") ->
                calendar.apply { add(Calendar.MINUTE, -number) }.timeInMillis
            value.contains("ساعة") || value.contains("ساعات") ->
                calendar.apply { add(Calendar.HOUR, -number) }.timeInMillis
            value.contains("يوم") || value.contains("أيام") ->
                calendar.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
            value.contains("أسبوع") || value.contains("أسابيع") ->
                calendar.apply { add(Calendar.DAY_OF_MONTH, -number * 7) }.timeInMillis
            value.contains("شهر") || value.contains("أشهر") ->
                calendar.apply { add(Calendar.MONTH, -number) }.timeInMillis
            value.contains("سنة") || value.contains("سنوات") ->
                calendar.apply { add(Calendar.YEAR, -number) }.timeInMillis
            else -> super.parseChapterDate(value)
        }
    }
}
