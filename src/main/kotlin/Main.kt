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
import util.Options
import util.atZone
import util.decodeJsonObject
import util.encodeQuery
import util.formatTime2
import util.notEmpty
import util.notZero
import util.parseIso8601
import util.toTsvLine
import util.zoneTokyo
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.*

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
    desc = "JSON file that contains server, user, apiToken. see also: secrets.json.sample .",
    arg = "jsonFile",
)

val optDays by options.int(
    names = listOf("-d", "--days"),
    defVal = 20,
    desc = "Do not retrieve/display data older than the specified number of days",
    arg = "(number)",
)

val optUserName by options.string(
    names = listOf("-u", "--userName"),
    defVal = "",
    desc = "specify part of the displayName that related to user of ticket",
    arg = "name",
)

val optProject by options.string(
    names = listOf("-p", "--project"),
    defVal = "",
    desc = "comma-separated list of projects name/key to specified in JQL.",
    arg = "project[,…]",
)

val optSubtaskParents by options.string(
    names = listOf("--epic", "-s", "--optSubtaskParents"),
    defVal = "",
    desc = "comma-separated list of parent of subtask.",
    arg = "issueIdOrKey[,…]",
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

class Ticket(
    val json: JsonObject,
    val time: ZonedDateTime,
    val key: String,
    val otherCols: Array<String?>,
) : Comparable<Ticket> {
    val line = listOf(time.formatTime2(), key, *otherCols).toTsvLine()

    override fun toString() = line
    override fun compareTo(other: Ticket) =
        time.compareTo(other.time)
            .notZero() ?: key.compareTo(other.key)
            .notZero() ?: line.compareTo(other.line)
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

    println("limitTime=${limitTime.formatTime2()}")

    fun filterAndFormatIssue(item: JsonObject): Pair<ZonedDateTime, Ticket?> {

        val fields = item.jsonObject("fields")
            ?: error("missing fields")

        val time = fields.string("updated")
            ?.parseIso8601()
            ?.atZone(zoneTokyo)
            ?: error("missing updated.")

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

        val line = if (roles.isEmpty()) {
            null
        } else {
            // TSD-1859 など
            val key = item.string("key") ?: error("missing key")

            // チケットのタイトル
            // 親チケットも読む
            val summaries = LinkedList<String>()
            var node: JsonObject? = fields
            while (node != null) {
                val summary = node.string("summary") ?: break
                summaries.addFirst(summary)
                node = node.jsonObject("parent")?.jsonObject("fields")
            }
            val summary = summaries.joinToString(" / ")

            // status
            val status = fields.jsonObject("status")?.string("name")
            Ticket(
                json = item,
                time = time,
                key = key,
                otherCols = arrayOf(
                    status,
                    roles,
                    summary
                )
            )
        }

        return Pair(time, line)
    }

    suspend fun loadMultiplePage(
        jql: String,
    ): List<Ticket> {
        log.i("loadMultiplePage: $jql")

        val result = ArrayList<Ticket>()
        val step = 20
        var startAt: Int? = 0
        var issuesTotal = 0

        requestLoop@ while (startAt != null) {

            val root = apiRequest(
                secrets = secrets,
                path = "/rest/api/3/search",
                query = listOf(
                    "orderBy" to "updated desc",
                    "startAt" to startAt,
                    "maxResults" to step,
                    "jql" to jql + "order by updated desc",
                    // "fields" to "name,summary,description,assignee",
                )
            ).decodeJsonObject()

            val items = (root.jsonArray("issues")
                ?.objectList()
                ?: error("missing issues array."))
            if (items.isEmpty()) {
                break@requestLoop
            }

            issuesTotal += items.size
            val a = items.mapNotNull {
                val (_, ticket) = filterAndFormatIssue(it)
                ticket
            }
            if (a.isNotEmpty()) {
                val timeMin = a.minOfOrNull { it.time }
                val timeMax = a.maxOfOrNull { it.time }
                log.i("items size=${a.size} tMin=$timeMin tMax=$timeMax")
                result.addAll(a)

                if(a.any { it.time < limitTime } ){
                    log.i("old limit exceeded.")
                    break
                }
            }

            startAt = when {
                root.boolean("isLastPage") == true -> {
                    log.i("isLastPage is true.")
                    null
                }
                else -> startAt + items.size
            }
        }
        if (issuesTotal == 0) log.w("… returns 0 issues.")
        return result
    }

    suspend fun loadSubtask(parent: String): List<Ticket> {
        print("loadSubtask $parent ")
        val jsonRoot = apiRequest(
            secrets = secrets,
            path = "/rest/api/latest/issue/$parent"
        ).decodeJsonObject()
        return jsonRoot
            .jsonObject("fields")
            ?.jsonArray("subtasks")
            ?.objectList()
            ?.also {
                print("${it.size},")
            }
            ?.mapNotNull {
                print(".")
                val item = apiRequest(
                    secrets = secrets,
                    path = "/rest/api/latest/issue/${it.string("id")}",
                ).decodeJsonObject()
                val (_, line) = filterAndFormatIssue(item)
                line
            }.also {
                print("\n")
            }
            ?: error("missing fields.subtasks in /rest/api/latest/issue/$parent")
    }

    // project in(...) でも  project=... or  project=... でも
    // 複数プロジェクトを読めなかったので、プロジェクトごとに取得する
    val listIssues = optProject.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { project ->
            loadMultiplePage(
                jql = "project=${project.quoteJql()}",
            )
        }
        .flatten()

    val listSubtask = optSubtaskParents.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { parent ->
            loadSubtask(parent).notEmpty() ?: loadMultiplePage(
                jql = "parent=${parent.quoteJql()}",
            )
        }.flatten()

    val list = (listIssues + listSubtask).sorted().distinctBy { it.key }

    if (list.isEmpty()) {
        log.w("empty result.")
    } else {
        println("# updated, ticket#, status, role(creator, reporter, assignee), title")
        list.forEach { println(it) }
    }
}

/**
 * main
 */
suspend fun main(args: Array<String>) {
    // オプション解析
    options.apply {
        parseOptions(args)
        LogTag.verbose = verbose

        log.v("file.encoding=${System.getProperty("file.encoding")}, LANG=${System.getenv("LANG")}")
        if (System.getenv("LANG")?.contains("UTF-8", ignoreCase = true) == true) {
            // 環境変数LANGがutf-8を含むなら標準入出力のエンコーディングを変更する
            log.v("force output encoding to UTF-8")
            fun FileDescriptor.printStreamUtf8() =
                PrintStream(FileOutputStream(this), true, "UTF-8")
            System.setOut(FileDescriptor.out.printStreamUtf8())
            System.setErr(FileDescriptor.err.printStreamUtf8())
        }

        if (help) usage(null)

        arrayOf(
            "optSecrets" to optSecrets,
            "optUserName" to optUserName,
        ).forEach { (propName, value) ->
            val meta = options.byPropName(propName) ?: error("missing option propName=$propName")
            if (value.isBlank()) {
                usage("option is not set. ${meta.name()}")
            }
        }

        log.v(toString())
    }

    // HTTPクライアント作成
    HttpClient(CIO) {
        expectSuccess = true
    }.use { client ->
        client.listTickets()
    }
}
