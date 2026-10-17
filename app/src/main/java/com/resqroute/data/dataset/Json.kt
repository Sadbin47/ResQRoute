package com.resqroute.data.dataset

/**
 * Minimal, dependency-free JSON reader.
 *
 * The bundled manifest is small and machine-generated, but the data layer must
 * stay free of Android framework types so dataset loading can be unit-tested on
 * the JVM (and reused off-device). This parser covers the full JSON value
 * grammar; it is not intended as a general-purpose streaming parser for large
 * documents.
 */
internal sealed interface JsonValue {
    data class ObjectValue(val values: Map<String, JsonValue>) : JsonValue
    data class ArrayValue(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val value: Double) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

internal class JsonSyntaxException(message: String) : Exception(message)

internal object JsonParser {

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.isAtEnd) {
            throw JsonSyntaxException("unexpected trailing content at position ${parser.position}")
        }
        return value
    }

    private class Parser(private val text: String) {

        var position: Int = 0
            private set

        val isAtEnd: Boolean get() = position >= text.length

        fun skipWhitespace() {
            while (!isAtEnd && text[position].isWhitespace()) position++
        }

        fun readValue(): JsonValue {
            if (isAtEnd) throw JsonSyntaxException("unexpected end of input")
            return when (val c = text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.StringValue(readString())
                't' -> { expect("true"); JsonValue.BooleanValue(true) }
                'f' -> { expect("false"); JsonValue.BooleanValue(false) }
                'n' -> { expect("null"); JsonValue.NullValue }
                else -> if (c == '-' || c in '0'..'9') {
                    JsonValue.NumberValue(readNumber())
                } else {
                    throw JsonSyntaxException("unexpected character '$c' at position $position")
                }
            }
        }

        private fun readObject(): JsonValue.ObjectValue {
            expectChar('{')
            skipWhitespace()
            val values = LinkedHashMap<String, JsonValue>()
            if (peek() == '}') {
                position++
                return JsonValue.ObjectValue(values)
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expectChar(':')
                skipWhitespace()
                values[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return JsonValue.ObjectValue(values)
                    }
                    else -> throw JsonSyntaxException("expected ',' or '}' at position $position")
                }
            }
        }

        private fun readArray(): JsonValue.ArrayValue {
            expectChar('[')
            skipWhitespace()
            val values = ArrayList<JsonValue>()
            if (peek() == ']') {
                position++
                return JsonValue.ArrayValue(values)
            }
            while (true) {
                skipWhitespace()
                values.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return JsonValue.ArrayValue(values)
                    }
                    else -> throw JsonSyntaxException("expected ',' or ']' at position $position")
                }
            }
        }

        private fun readString(): String {
            expectChar('"')
            val builder = StringBuilder()
            while (true) {
                if (isAtEnd) throw JsonSyntaxException("unterminated string at position $position")
                when (val c = text[position]) {
                    '"' -> {
                        position++
                        return builder.toString()
                    }
                    '\\' -> {
                        position++
                        if (isAtEnd) throw JsonSyntaxException("unterminated escape at position $position")
                        when (val escape = text[position]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (position + 4 >= text.length) {
                                    throw JsonSyntaxException("incomplete \\u escape at position $position")
                                }
                                val hex = text.substring(position + 1, position + 5)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonSyntaxException("invalid \\u escape '$hex' at position $position")
                                builder.append(code.toChar())
                                position += 4
                            }
                            else -> throw JsonSyntaxException("invalid escape '\\$escape' at position $position")
                        }
                        position++
                    }
                    else -> {
                        builder.append(c)
                        position++
                    }
                }
            }
        }

        private fun readNumber(): Double {
            val start = position
            if (peek() == '-') position++
            while (!isAtEnd && text[position] in '0'..'9') position++
            if (!isAtEnd && text[position] == '.') {
                position++
                while (!isAtEnd && text[position] in '0'..'9') position++
            }
            if (!isAtEnd && (text[position] == 'e' || text[position] == 'E')) {
                position++
                if (!isAtEnd && (text[position] == '+' || text[position] == '-')) position++
                while (!isAtEnd && text[position] in '0'..'9') position++
            }
            val token = text.substring(start, position)
            return token.toDoubleOrNull()
                ?: throw JsonSyntaxException("invalid number '$token' at position $start")
        }

        private fun expect(word: String) {
            if (position + word.length > text.length || text.substring(position, position + word.length) != word) {
                throw JsonSyntaxException("expected '$word' at position $position")
            }
            position += word.length
        }

        private fun expectChar(expected: Char) {
            if (isAtEnd || text[position] != expected) {
                throw JsonSyntaxException("expected '$expected' at position $position")
            }
            position++
        }

        private fun peek(): Char {
            if (isAtEnd) throw JsonSyntaxException("unexpected end of input")
            return text[position]
        }
    }
}
