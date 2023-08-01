import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.util.encodeBase64
import util.JsonObject
import util.LogTag
import util.atZone
import util.decodeJsonObject
import util.encodeQuery
import util.formatIso8601
import util.parseIso8601
import util.zoneTokyo
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

private val log = LogTag("Main")

val options = Options()

val help by options.boolean(
    names = listOf("-h", "--help"),
    desc = "show this help.",
)

val verbose by options.boolean(
    names = listOf("-v", "--verbose"),
    desc = "verbose console log.",
)

val optSecrets by options.string(
    names = listOf("-s", "--secret"),
    defVal = "secrets.json",
    desc = "JSON file that contains server, user, apiToken.",
    arg = "jsonFile",
)

val optDays by options.int(
    names = listOf("-d", "--days"),
    defVal = 40,
    desc = "Do not retrieve/display data older than the specified number of days",
    arg = "number",
)

val optUserName by options.string(
    names = listOf("-u", "--userName"),
    defVal = "",
    desc = "specify part of the displayName that related to Jira ticket",
    arg = "'part of the displayName'",
)

val optProject by options.string(
    names = listOf("-p", "--project"),
    defVal = "",
    desc = "comma-separated list of projects name/key to specified in JQL.",
    arg = "'projects'",
)

/**
 * HTTPリクエストを送りレスポンスを読む。
 * 成功して応答ボディがテキストならそれを返す。
 * - secretsからserverとbasicAuthを読む。
 * - queryがあればURL末尾に付与する。
 * - その他のリクエスト初期化はrequestInitializerラムダで設定可能。
 */
suspend fun HttpClient.apiRequest(
    secrets: JsonObject,
    path: String,
    query: List<Pair<String, Any?>>? = null,
    requestInitializer: HttpRequestBuilder.() -> Unit = {},
): String {
    val server = secrets.string("server") ?: error("missing server in $optSecrets")
    val basicAuth = secrets.string("basicAuth") ?: error("missing basicAuth.")

    var url = "https://$server$path"
    if (!query.isNullOrEmpty()) {
        val delm = if (url.contains("?")) "&" else "?"
        url = "$url$delm${query.encodeQuery()}"
    }

    val requestBuilder = HttpRequestBuilder().apply {
        url(url)
        header("Authorization", "Basic $basicAuth")
        requestInitializer()
    }
    val response = request(requestBuilder)

    val bodyText = try {
        response.bodyAsText()
    } catch (ex: Throwable) {
        null
    }

    if (!response.status.isSuccess() || bodyText == null) {
        error("HTTP error ${response.status} $url body=$bodyText")
    }
    return bodyText
}

val reJqlSpecialChar = """([\\"])""".toRegex()

/**
 * JQLの特殊文字をエスケープして前後に`"`を追加する
 */
fun String.quoteJql(): String {
    val escaped = reJqlSpecialChar.replace(this) {
        "\\" + it.groupValues[1]
    }
    return "\"$escaped\""
}

/**
 * Jiraのチケットを作成日降順で一定数読み、適当な条件でフィルタして表示する
 */
suspend fun HttpClient.listTickets() {

    // apiTokenの取得方法:
    //        Jiraの右上のアカウント画像をタップ
    //        「アカウントを管理」をタップ
    //        認証をすませる
    //        セキュリティ タブをタップ
    //                API トークンの作成と管理 をタップ
    //        APiトークンを作成する をタップ
    //                ラベルを入力。何に使うかわかるような名前
    //        新しいAPIトークンがHidden表示されるので、コピーする

    // secrets ファイルを読む
    val secrets = File(optSecrets).readText().decodeJsonObject()
    val user = secrets.string("user") ?: error("missing user in $optSecrets")
    val apiToken = secrets.string("apiToken") ?: error("missing apiToken in $optSecrets")

    // user:apiToken から Basic認証用のBase64ダイジェストを計算する
    secrets["basicAuth"] = "$user:$apiToken"
        .encodeToByteArray()
        .encodeBase64()

    // 指定日数前の日時
    val limitTime = ZonedDateTime.now(zoneTokyo)
        .truncatedTo(ChronoUnit.DAYS)
        .minusDays(optDays.toLong())
    println("limitTime=${limitTime.formatIso8601()}")
    println("c/r/a mean creator, reporter, assignee")

    suspend fun loadMultiplePage(jql: String): List<String> {
        print("jql: $jql ")

        val result = ArrayList<String>()
        val step = 20
        var startAt: Int? = 0
        var issuesTotal = 0
        requestLoop@ while (startAt != null) {
            print(".")

            val root = apiRequest(
                secrets = secrets,
                path = "/rest/api/latest/search",
                query = listOf(
                    "orderBy" to "created-",
                    "startAt" to startAt,
                    "maxResults" to step,
                    "jql" to jql,
                    // "fields" to "name,summary,description,assignee",
                )
            ).decodeJsonObject()

            val items = (root.jsonArray("issues")
                ?.objectList()
                ?: error("missing issues array."))

            issuesTotal += items.size

            for (item in items) {
                val fields = item.jsonObject("fields")
                    ?: error("missing fields")

                val created = fields.string("created")
                    ?.parseIso8601()
                    ?.atZone(zoneTokyo)
                    ?: error("missing created.")

                // 期限より古いデータに遭遇したらループ脱出
                if (created < limitTime) break@requestLoop

                val roles = listOf("creator", "reporter", "assignee")
                    .mapNotNull { key ->
                        fields.jsonObject(key)
                            ?.string("displayName")
                            ?.takeIf {
                                // コマンドライン引数で指定される、ユーザのdisplayNameの一部にマッチすること
                                it.contains(optUserName)
                            }
                            ?.let { Pair(key, it) }
                    }
                    .joinToString("/") { it.first.firstOrNull().toString() }

                if (roles.isEmpty()) continue

                // TSD-1859 など
                val key = item.string("key") ?: error("missing key")

                // チケットのタイトル
                val summary = fields.string("summary")

                // status
                val status = fields.jsonObject("status")?.string("name")

                result.add("${created.formatIso8601()} $key [$status] $roles $summary")
            }

            startAt = when {
                root.boolean("isLastPage") == true -> null
                else -> startAt + items.size
            }
        }
        if (issuesTotal == 0) print(" returns 0 issues.")
        print("\n")

        return result
    }

    val projects = optProject.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }

    // project in(...) でも  project=... or  project=... でも
    // 複数プロジェクトを読めなかったので、プロジェクトごとに取得する
    val list = projects.map { project ->
        loadMultiplePage(
            jql = "project=${project.quoteJql()}"
        )
    }
        .flatten()
        .sortedDescending()

    for (it in list) {
        println(it)
    }
}

/**
 * main
 */
suspend fun main(args: Array<String>) {

    log.v("file.encoding=${System.getProperty("file.encoding")}, LANG=${System.getenv("LANG")}")
    if (System.getenv("LANG").contains("UTF-8", ignoreCase = true)) {
        // 環境変数LANGがutf-8を含むなら標準入出力のエンコーディングを変更する
        log.v("force output encoding to UTF-8")
        fun FileDescriptor.printStreamUtf8() =
            PrintStream(FileOutputStream(this), true, "UTF-8")
        System.setOut(FileDescriptor.out.printStreamUtf8())
        System.setErr(FileDescriptor.err.printStreamUtf8())
    }

    // オプション解析
    options.apply {
        parseOptions(args)
        if (help) usage(null)
        if (optSecrets.isBlank()) usage("--secret is not set.")
        if (optUserName.isBlank()) usage("--userName is not set.")
        if (optProject.isBlank()) usage("--project is not set.")
        log.v(toString())
    }

    // HTTPクライアント作成
    HttpClient(CIO) {
        expectSuccess = true
    }.use { client ->
        client.listTickets()
    }
}
