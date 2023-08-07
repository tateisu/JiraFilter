package util

import java.io.File
import kotlin.reflect.KProperty
import kotlin.system.exitProcess

/**
 * オプションのベースクラス
 * - 移譲プロパティとして動作する
 * - ヘルプ表示に必要なテキストやデフォルト値を保持する
 */
abstract class OptionBase<T : Any>(
    // デフォルト値
    // data の初期化のため、ベースクラスのコンストラクタ引数にする
    val defVal: T,
) {
    // プロパティを持つ親
    var thisRef: Any? = null

    // プロパティ
    var prop: KProperty<*>? = null

    // オプション引数の名前。 -h --help などを含むリスト
    abstract val names: List<String>

    // ヘルプ表示に使うテキスト。追加引数の名称
    abstract val arg: String?

    // ヘルプ表示に使うテキスト。説明文
    abstract val desc: String

    // 現在の値
    var data = defVal

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

    fun name(separator: String = "|") = names.joinToString(separator)
}

// Boolean値を保持する移譲プロパティ
class OptionBoolean(
    override val names: List<String>,
    private val valueIfSet: Boolean,
    override val arg: String?,
    override val desc: String,
    defVal: Boolean,
) : OptionBase<Boolean>(defVal) {
    override fun flagValue() = valueIfSet
}

// String値を保持する移譲プロパティ
class OptionString(
    override val names: List<String>,
    override val arg: String,
    override val desc: String,
    defVal: String,
) : OptionBase<String>(defVal) {
    override fun parseValue(src: String) = src
}

// Int値を保持する移譲プロパティ
class OptionInt(
    override val names: List<String>,
    override val arg: String,
    override val desc: String,
    defVal: Int,
) : OptionBase<Int>(defVal) {
    override fun parseValue(src: String) = src.toInt()
}

// https://youtrack.jetbrains.com/issue/KT-17440
// provideDelegate はGenericsを使えないので、Delegate型ごとに定義する
class DelegateProviderOptionBoolean(val creator: (Any?, KProperty<*>) -> OptionBoolean) {
    operator fun provideDelegate(thisRef: Any?, prop: KProperty<*>) = creator(thisRef, prop)
}

class DelegateProviderOptionString(val creator: (Any?, KProperty<*>) -> OptionString) {
    operator fun provideDelegate(thisRef: Any?, prop: KProperty<*>) = creator(thisRef, prop)
}

class DelegateProviderOptionInt(val creator: (Any?, KProperty<*>) -> OptionInt) {
    operator fun provideDelegate(thisRef: Any?, prop: KProperty<*>) = creator(thisRef, prop)
}

/**
 * コマンドラインオプションの集合
 * - usage, parseOptions, showOptions などオプションの集合を扱う
 * - オプション変数は移譲プロパティとして働く。随時コードから変更できる。
 * - provideDelegateを使ってオプション変数のプロパティ名を覚えて、byPropName()で参照できる。
 *
 * usage:
 *
 * // (1) options変数を用意する。
 * val options = Options()
 *
 * // (2)オプション変数を移譲プロパティで定義する
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
 *
 * // (3)コマンドライン引数を渡してオプションを解析する。
 * val otherArgs = options.parseOptions(args)
 *
 * // (4)解釈後、オプション変数は普通に読める。もしオプションをvarで定義したなら変更できる。
 * if (help) usage(null)
 * if (optStr.isEmpty()) usage("--str is empty.)
 *
 * // (5)オプションの値をまとめて表示する
 * println(options.toString())
 *
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

        linePrinter("\nUsage:\n  java -jar $jarFile [options…]")
        linePrinter("\nOptions:")
        forEach { option ->
            val optionArg = when (val a = option.arg) {
                null -> ""
                else -> " $a"
            }
            when (option.names.size) {
                1 -> linePrinter("  ${option.names.first()}$optionArg")
                else -> linePrinter("  ${option.names.joinToString(" | ")}$optionArg")
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
                otherArgs.addAll(args.slice(i..<end))
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
                val k = it.prop?.name ?: it.names.first()
                val v = it.data.toString()
                Pair(k, v)
            }.sortedBy { it.first }
                .joinToString(", ") { "${it.first}=[${it.second}]" }

    /**
     * プロパティ名からOptionオブジェクトを取得する
     */
    fun byPropName(name: String): OptionBase<*>? =
        find { it.prop?.name == name }

    private fun <T : OptionBase<*>> T.register(
        thisRef: Any?,
        prop: KProperty<*>,
    ) = apply {
        add(this)
        this.thisRef = thisRef
        this.prop = prop
    }

    // 移譲プロパティを生成する
    fun boolean(
        names: List<String>,
        defVal: Boolean = false,
        valueIfSet: Boolean = true,
        arg: String? = null,
        desc: String,
    ) = DelegateProviderOptionBoolean { thisRef, prop ->
        OptionBoolean(
            names = names,
            defVal = defVal,
            valueIfSet = valueIfSet,
            arg = arg,
            desc = desc,
        ).register(thisRef, prop)
    }

    // 移譲プロパティを生成する
    fun string(
        names: List<String>,
        defVal: String = "",
        arg: String,
        desc: String,
    ) = DelegateProviderOptionString { thisRef, prop ->
        OptionString(
            names = names,
            defVal = defVal,
            arg = arg,
            desc = desc,
        ).register(thisRef, prop)
    }

    // 移譲プロパティを生成する
    fun int(
        names: List<String>,
        defVal: Int,
        arg: String,
        desc: String,
    ) = DelegateProviderOptionInt { thisRef, prop ->
        OptionInt(
            names = names,
            defVal = defVal,
            arg = arg,
            desc = desc,
        ).register(thisRef, prop)
    }
}
