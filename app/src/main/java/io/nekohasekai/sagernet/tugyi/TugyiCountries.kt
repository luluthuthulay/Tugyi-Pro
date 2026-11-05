package io.nekohasekai.sagernet.tugyi

/**
 * Tugyi Pro region model - the SINGLE list of regions (Hsu, 2026-11-05).
 *
 * Regions are NOT hardcoded in the layout: the home UI (country pill
 * list, map dots) is built from TugyiCountry.all(). To add a region
 * later, add ONE entry here (plus its engine output file) - no layout
 * or UI code changes are needed.
 *
 * Home shows ONLY these regions. No node list, no IPs on home.
 * Live files are published by the GitHub engine in the Tugyi-Pro repo
 * (output/sg.txt, output/jp.txt, output/us.txt, output/hk.txt),
 * already ranked by median HTTP-over-proxy latency with dead nodes
 * removed. HK added 2026-11-05 (Hsu's YLT notes: 4 HK Hysteria2
 * nodes work from Myanmar; Hong Kong is near Myanmar like SG/JP).
 * The phone only downloads these small files; it does not
 * bulk-test hundreds of nodes itself.
 */
enum class TugyiCountry(
    val code: String,
    val displayName: String,
    val liveFile: String,
    /** Full name shown in the country pill, e.g. "Singapore". */
    val fullName: String,
    /** Flag shown in the country pill. */
    val flag: String,
    /** Map position of this region (for the route map dot). */
    val lon: Double,
    val lat: Double,
) {
    SG("SG", "SG", "sg.txt", "Singapore", "🇸🇬", 103.85, 1.29),
    JP("JP", "JP", "jp.txt", "Japan", "🇯🇵", 139.75, 35.68),
    HK("HK", "HK", "hk.txt", "Hong Kong", "🇭🇰", 114.17, 22.32),
    US("US", "US", "us.txt", "United States", "🇺🇸", -98.0, 39.0);

    companion object {
        const val GITHUB_RAW_BASE =
            "https://raw.githubusercontent.com/luluthuthulay/Tugyi-Pro/main/output/"

        fun liveUrl(country: TugyiCountry): String =
            GITHUB_RAW_BASE + country.liveFile

        /** The whole region list, in display order. */
        fun all(): List<TugyiCountry> = entries
    }
}
