import java.io.File
import kotlin.reflect.KProperty
import kotlin.system.exitProcess

/**
 * コマンドラインオプションの集合
 * - usage, parseOptions, showOptions などオプションの集合を扱う
 * - コード中でオプションを定義するとコンストラクタからリストに追加される
 * - kotlinx-cliと違って、オプション変数はコードから随時変更できる
 *
 * usage:
 * val options = Options()
 *
 * // (1)オプション変数を移譲プロパティで定義する
 * val help by options.boolean(
 *     names = listOf("-h", "--help"),
 *     desc = "show this help.",
 * )
 * val optStr by options.string(
 *     names = listOf("-s", "--str"),
 *     defVal = "(default value)",
 *     desc = "option description",
 *     arg = "string",
 * )
 * val optInt by options.int(
 *     names = listOf("-d", "--days"),
 *     defVal = 40,
 *     desc = "option description",
 *     arg = "number",
 * )
 * // (2)コマンドライン引数を解釈する
 * val otherArgs = parseOptions(args)
 *
 * // (3)オプションの値をまとめて表示する
 * println(dumpOptions())
 *
 * // (4)解釈後、オプション変数は普通に読み書きできる
 * if (help) usage(null)
 * if (optStr.isEmpty()) usage("--str is empty.)
 */
class Options : ArrayList<OptionBase<*>>() {

    /**
     * usageを表示してアプリを終了する
     */
    fun usage(
        error: String?,
        linePrinter: (String) -> Unit = { println(it) },
    ): Nothing {

        val jarFile = OptionBase::class.java
            .protectionDomain
            .codeSource
            .location
            .toURI()
            .path
            .let { File(it) }
            .takeIf { it.isFile }
            ?.name
            ?: "(???.jar)"

        linePrinter("usage: java -jar $jarFile (options...)")
        linePrinter("\nOptions:")
        forEach { option ->
            val optionArg = when (val a = option.arg) {
                null -> ""
                else -> " $a"
            }
            when (option.names.size) {
                1 -> linePrinter("  ${option.names.first()}$optionArg")
                else -> linePrinter("  ( ${option.names.joinToString(" | ")} )$optionArg")
            }
            linePrinter("    ${option.desc}")
            val defVal = option.defVal.toString()
            if (defVal.isNotEmpty()) {
                linePrinter("    default value is $defVal")
            }
        }
        if (error != null) {
            linePrinter("\nError: $error")
        }
        exitProcess(1)
    }

    /**
     * 引数リストからオプションを解釈する
     * @return オプションではない引数のリスト
     */
    fun parseOptions(args: Array<String>): List<String> {
        // オプション引数名とOptionBase<*>のマップ
        val nameMap = map { option -> option.names.map { it to option } }
            .flatten()
            .toMap()

        val otherArgs = ArrayList<String>()
        val end = args.size
        var i = 0
        while (i < end) {
            val a = args[i++]
            if (a == "--") {
                otherArgs.addAll(args.slice(i until end))
                break
            } else if (a[0] == '-') {
                (nameMap[a] ?: error("missing option $a")).updateValue {
                    args.elementAtOrNull(i++)
                        ?: usage("option $a : missing option argument.")
                }
            } else {
                otherArgs.add(a)
            }
        }
        return otherArgs
    }

    /**
     * オプションの値をまとめて文字列化
     */
    override fun toString() = "options: " +
            map {
                val k = it.names.first()
                val v = it.data.toString()
                Pair(k, v)
            }.sortedBy { it.first }
                .joinToString(", ") { "${it.first}=[${it.second}]" }

    // 移譲プロパティを生成する
    fun boolean(
        names: List<String>,
        defVal: Boolean = false,
        valueSet: Boolean = true,
        arg: String? = null,
        desc: String,
    ) = OptionBoolean(
        options = this,
        names = names,
        defVal = defVal,
        valueSet = valueSet,
        arg = arg,
        desc = desc,
    )

    // 移譲プロパティを生成する
    fun string(
        names: List<String>,
        defVal: String = "",
        arg: String,
        desc: String,
    ) = OptionString(
        options = this,
        names = names,
        defVal = defVal,
        arg = arg,
        desc = desc,
    )

    // 移譲プロパティを生成する
    fun int(
        names: List<String>,
        defVal: Int,
        arg: String,
        desc: String,
    ) = OptionInt(
        options = this,
        names = names,
        defVal = defVal,
        arg = arg,
        desc = desc,
    )
}

/**
 * オプションのベースクラス
 * - 移譲プロパティとして動作する
 * - ヘルプ表示に必要なテキストやデフォルト値を保持する
 */
abstract class OptionBase<T : Any>(
    // オプションの集合
    options: Options,
    // デフォルト値
    val defVal: T,
) {
    // オプション引数の名前。 -h --help などを含むリスト
    abstract val names: List<String>

    // ヘルプ表示に使うテキスト。追加引数の名称
    abstract val arg: String?

    // ヘルプ表示に使うテキスト。説明文
    abstract val desc: String

    // 現在の値
    var data = defVal

    init {
        @Suppress("LeakingThis")
        options.add(this)
    }

    // 移譲プロパティの実装
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T =
        data

    // 移譲プロパティの実装
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        data = value
    }

    /**
     * 必要なら派生クラスで実装する
     * - 追加引数が必要なら NotImplementedError を投げる(デフォルトの挙動)
     * - 追加引数が不要なら新しい値を返す
     */
    protected open fun flagValue(): T {
        throw NotImplementedError()
    }

    /**
     * 必要なら派生クラスで実装する
     * - 追加引数を受け取ってそれを解釈して新しい値を返す
     */
    protected open fun parseValue(src: String): T {
        throw NotImplementedError()
    }

    /**
     * parseOptionsから呼ばれる。
     * - flagValue や parseValue を使って現在の値を更新する。
     */
    fun updateValue(nextArg: () -> String) {
        data = try {
            flagValue()
        } catch (ignored: NotImplementedError) {
            parseValue(nextArg())
        }
    }
}

// Boolean値を保持する移譲プロパティ
class OptionBoolean(
    options: Options,
    override val names: List<String>,
    defVal: Boolean = false,
    private val valueSet: Boolean = true,
    override val arg: String? = null,
    override val desc: String,
) : OptionBase<Boolean>(options, defVal) {
    override fun flagValue() = valueSet
}

// String値を保持する移譲プロパティ
class OptionString(
    options: Options,
    override val names: List<String>,
    defVal: String = "",
    override val arg: String,
    override val desc: String,
) : OptionBase<String>(options, defVal) {
    override fun parseValue(src: String) = src
}

// Int値を保持する移譲プロパティ
class OptionInt(
    options: Options,
    override val names: List<String>,
    defVal: Int,
    override val arg: String,
    override val desc: String,
) : OptionBase<Int>(options, defVal) {
    override fun parseValue(src: String) = src.toInt()
}
