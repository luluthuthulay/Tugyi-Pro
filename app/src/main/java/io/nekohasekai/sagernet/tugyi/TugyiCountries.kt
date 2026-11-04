package io.nekohasekai.sagernet.tugyi

/**
 * Tugyi Pro country model.
 *
 * Home shows ONLY these three countries. No node list, no IPs on home.
 * Live files are published by the GitHub engine in the Tugyi-Pro repo
 * (output/sg.txt, output/jp.txt, output/us.txt), already ranked by
 * median HTTP-over-proxy latency with dead nodes removed.
 * The phone only downloads these three small files; it does not
 * bulk-test hundreds of nodes itself.
 */
enum class TugyiCountry(
    val code: String,
    val displayName: String,
    val liveFile: String,
) {
    SG("SG", "SG", "sg.txt"),
    JP("JP", "JP", "jp.txt"),
    US("US", "US", "us.txt");

    companion object {
        const val GITHUB_RAW_BASE =
            "https://raw.githubusercontent.com/luluthuthulay/Tugyi-Pro/main/output/"

        fun liveUrl(country: TugyiCountry): String =
            GITHUB_RAW_BASE + country.liveFile

        fun all(): List<TugyiCountry> = listOf(SG, JP, US)
    }
}
