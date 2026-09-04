package com.example.familywatch

/**
 * OCR結果テキストに対する条件式を扱う。
 *
 * 書式:
 *   "文字列"           そのテキストが含まれていればtrue(単純な部分一致)
 *   reg("正規表現")     正規表現にマッチすればtrue
 *   A && B             AとBの両方を満たす
 *   A || B             AかBのどちらかを満たす
 *   ! A                Aを満たさない
 *   ( ... )            グループ化(優先順位の変更)
 *
 * 優先順位: !  >  &&  >  ||  (括弧で自由に変更可)
 *
 * 例:
 *   "柏市" && ("位置情報を更新しています" || "更新: 1分前" || "更新: 2分前") && (!"新柏")
 *   "柏市" && reg("[1-9]分前")
 */
object ConditionMatcher {

    sealed class Node {
        data class Contains(val text: String) : Node()
        data class RegexMatch(val compiled: Regex) : Node()
        data class Not(val node: Node) : Node()
        data class And(val nodes: List<Node>) : Node()
        data class Or(val nodes: List<Node>) : Node()
    }

    private sealed class Token {
        object LParen : Token()
        object RParen : Token()
        object And : Token()
        object Or : Token()
        object Not : Token()
        data class Str(val value: String) : Token()
        data class Ident(val name: String) : Token()
    }

    private class Tokenizer(private val src: String) {
        private var pos = 0

        fun tokenize(): List<Token> {
            val tokens = mutableListOf<Token>()
            while (pos < src.length) {
                val c = src[pos]
                when {
                    c.isWhitespace() -> pos++
                    c == '"' -> tokens.add(readString())
                    c == '(' -> { tokens.add(Token.LParen); pos++ }
                    c == ')' -> { tokens.add(Token.RParen); pos++ }
                    c == '!' -> { tokens.add(Token.Not); pos++ }
                    c == '&' && peek(1) == '&' -> { tokens.add(Token.And); pos += 2 }
                    c == '|' && peek(1) == '|' -> { tokens.add(Token.Or); pos += 2 }
                    c.isLetter() -> tokens.add(readIdent())
                    else -> throw IllegalArgumentException(
                        "予期しない文字「$c」があります(${pos + 1}文字目)。" +
                            "文字列は必ず \" で囲んでください。"
                    )
                }
            }
            return tokens
        }

        private fun peek(offset: Int): Char? =
            if (pos + offset < src.length) src[pos + offset] else null

        private fun readIdent(): Token.Ident {
            val sb = StringBuilder()
            while (pos < src.length && (src[pos].isLetterOrDigit())) {
                sb.append(src[pos])
                pos++
            }
            return Token.Ident(sb.toString())
        }

        private fun readString(): Token.Str {
            val start = pos
            pos++ // 開始の "
            val sb = StringBuilder()
            while (pos < src.length && src[pos] != '"') {
                if (src[pos] == '\\' && peek(1) == '"') {
                    sb.append('"')
                    pos += 2
                } else {
                    sb.append(src[pos])
                    pos++
                }
            }
            if (pos >= src.length) {
                throw IllegalArgumentException(
                    "${start + 1}文字目の \" に対応する閉じの \" が見つかりません"
                )
            }
            pos++ // 終了の "
            return Token.Str(sb.toString())
        }
    }

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0
        private fun peek(): Token? = tokens.getOrNull(pos)
        private fun consume(): Token = tokens[pos++]

        fun parse(): Node {
            if (tokens.isEmpty()) throw IllegalArgumentException("条件式が空です")
            val node = parseOr()
            if (pos != tokens.size) {
                throw IllegalArgumentException("式の終わり付近に余分な記号があります")
            }
            return node
        }

        private fun parseOr(): Node {
            val nodes = mutableListOf(parseAnd())
            while (peek() is Token.Or) {
                consume()
                nodes.add(parseAnd())
            }
            return if (nodes.size == 1) nodes[0] else Node.Or(nodes)
        }

        private fun parseAnd(): Node {
            val nodes = mutableListOf(parseNot())
            while (peek() is Token.And) {
                consume()
                nodes.add(parseNot())
            }
            return if (nodes.size == 1) nodes[0] else Node.And(nodes)
        }

        private fun parseNot(): Node {
            return if (peek() is Token.Not) {
                consume()
                Node.Not(parseNot())
            } else {
                parseAtom()
            }
        }

        private fun parseAtom(): Node {
            val tok = peek() ?: throw IllegalArgumentException(
                "式が途中で終わっています(文字列か「(」が必要です)"
            )
            return when (tok) {
                is Token.LParen -> {
                    consume()
                    val inner = parseOr()
                    if (peek() !is Token.RParen) {
                        throw IllegalArgumentException("閉じ括弧「)」がありません")
                    }
                    consume()
                    inner
                }
                is Token.Str -> {
                    consume()
                    Node.Contains(tok.value)
                }
                is Token.Ident -> {
                    consume()
                    if (tok.name != "reg") {
                        throw IllegalArgumentException(
                            "不明な関数「${tok.name}」です(使えるのは reg(\"...\") のみです)"
                        )
                    }
                    if (peek() !is Token.LParen) {
                        throw IllegalArgumentException("reg の後には ( が必要です。例: reg(\"[1-9]分前\")")
                    }
                    consume()
                    val strTok = peek()
                    if (strTok !is Token.Str) {
                        throw IllegalArgumentException("reg(...) の中には \"正規表現\" を書いてください")
                    }
                    consume()
                    if (peek() !is Token.RParen) {
                        throw IllegalArgumentException("reg(...) の閉じ括弧「)」がありません")
                    }
                    consume()
                    val compiled = try {
                        Regex(strTok.value, RegexOption.IGNORE_CASE)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("正規表現が不正です: ${e.message}")
                    }
                    Node.RegexMatch(compiled)
                }
                else -> throw IllegalArgumentException(
                    "「\"文字列\"」か「reg(...)」か「(」が来るべき場所に別の記号があります"
                )
            }
        }
    }

    fun parse(expr: String): Node {
        val tokens = Tokenizer(expr).tokenize()
        return Parser(tokens).parse()
    }

    /** 構文エラーがなければnull、あればエラーメッセージを返す(画面表示用)。 */
    fun validate(expr: String): String? {
        return try {
            parse(expr)
            null
        } catch (e: Exception) {
            e.message ?: "条件式が正しくありません"
        }
    }

    fun evaluate(node: Node, text: String): Boolean = when (node) {
        is Node.Contains -> text.contains(node.text, ignoreCase = true)
        is Node.RegexMatch -> node.compiled.containsMatchIn(text)
        is Node.Not -> !evaluate(node.node, text)
        is Node.And -> node.nodes.all { evaluate(it, text) }
        is Node.Or -> node.nodes.any { evaluate(it, text) }
    }
}
