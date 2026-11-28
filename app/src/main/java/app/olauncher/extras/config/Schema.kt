package app.olauncher.extras.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * A small zod-style schema for configuration files (docs/configuration.md). Every value read from a
 * file goes through one of these types: it checks the JSON type and the limits, and returns a clean
 * Kotlin value, or throws [SchemaError] naming the path and what's wrong. Nothing else reads the file.
 * Each type also describes itself as JSON Schema, so other tools (a future style gallery) can check
 * files with the same limits.
 */
class SchemaError(val path: String, val reason: String) : Exception("$path: $reason")

abstract class Type<T> {
    /** [value] is what org.json produced (Boolean, Number, String, JSONObject, JSONArray, JSONObject.NULL). */
    abstract fun parse(value: Any?, path: String): T
    abstract fun encode(value: T): Any
    abstract fun jsonSchema(): JSONObject
}

object S {

    /** Java/Kotlin identifiers joined by dots, like Android package and class names. */
    private const val IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*"
    const val PACKAGE_PATTERN = "^$IDENTIFIER(\\.$IDENTIFIER)+$"
    const val CLASS_PATTERN = "^$IDENTIFIER(\\.$IDENTIFIER)*(\\$$IDENTIFIER)*$"

    fun bool() = object : Type<Boolean>() {
        override fun parse(value: Any?, path: String) = value as? Boolean ?: throw SchemaError(path, "must be true or false")
        override fun encode(value: Boolean) = value
        override fun jsonSchema() = JSONObject().put("type", "boolean")
    }

    fun int(min: Int, max: Int) = object : Type<Int>() {
        override fun parse(value: Any?, path: String): Int {
            val n = value as? Number ?: throw SchemaError(path, "must be a whole number from $min to $max")
            val d = n.toDouble()
            if (d.isNaN() || d != Math.floor(d) || d < min || d > max) throw SchemaError(path, "must be a whole number from $min to $max")
            return d.toInt()
        }
        override fun encode(value: Int) = value
        override fun jsonSchema() = JSONObject().put("type", "integer").put("minimum", min).put("maximum", max)
    }

    fun decimal(min: Double, max: Double) = object : Type<Double>() {
        override fun parse(value: Any?, path: String): Double {
            val d = (value as? Number)?.toDouble() ?: throw SchemaError(path, "must be a number from $min to $max")
            if (d.isNaN() || d.isInfinite() || d < min || d > max) throw SchemaError(path, "must be a number from $min to $max")
            return d
        }
        override fun encode(value: Double) = value
        override fun jsonSchema() = JSONObject().put("type", "number").put("minimum", min).put("maximum", max)
    }

    fun choice(vararg values: String) = object : Type<String>() {
        override fun parse(value: Any?, path: String): String {
            val s = value as? String
            if (s == null || s !in values) throw SchemaError(path, "must be one of ${values.joinToString(", ")}")
            return s
        }
        override fun encode(value: String) = value
        override fun jsonSchema() = JSONObject().put("type", "string").put("enum", JSONArray(values.toList()))
    }

    /**
     * Plain text: trimmed, at most [maxLength] characters, no control characters and no invisible
     * direction overrides (they can make text read differently than it is). [pattern] narrows it further.
     */
    fun text(maxLength: Int, pattern: String? = null, minLength: Int = 0) = object : Type<String>() {
        private val regex = pattern?.let(::Regex)
        override fun parse(value: Any?, path: String): String {
            val s = (value as? String ?: throw SchemaError(path, "must be text")).trim()
            if (s.codePointCount(0, s.length) > maxLength) throw SchemaError(path, "must be at most $maxLength characters")
            if (s.length < minLength) throw SchemaError(path, "must not be empty")
            if (s.any(::isUnsafeChar)) throw SchemaError(path, "must not contain control or direction characters")
            if (regex != null && !regex.matches(s)) throw SchemaError(path, "isn't in the expected format")
            return s
        }
        override fun encode(value: String) = value
        override fun jsonSchema() = JSONObject().put("type", "string").put("maxLength", maxLength).apply {
            if (minLength > 0) put("minLength", minLength)
            pattern?.let { put("pattern", it) }
        }
    }

    fun packageName() = text(255, PACKAGE_PATTERN, minLength = 3)
    fun className() = text(255, CLASS_PATTERN, minLength = 1)

    fun <T> list(item: Type<T>, max: Int) = object : Type<List<T>>() {
        override fun parse(value: Any?, path: String): List<T> {
            val array = value as? JSONArray ?: throw SchemaError(path, "must be a list")
            if (array.length() > max) throw SchemaError(path, "must have at most $max items")
            return (0 until array.length()).map { item.parse(array.opt(it), "$path[$it]") }
        }
        override fun encode(value: List<T>) = JSONArray(value.map { item.encode(it) })
        override fun jsonSchema() = JSONObject().put("type", "array").put("maxItems", max).put("items", item.jsonSchema())
    }

    /** Characters a file may not use in text: control characters and bidi/invisible formatting. */
    fun isUnsafeChar(c: Char): Boolean = Character.isISOControl(c) || c.code in 0x200B..0x200F ||
        c.code in 0x202A..0x202E || c.code in 0x2066..0x2069 || c.code == 0xFEFF
}

/** Reads one object field through a [Type]; absent or null → null. */
fun <T> JSONObject.field(name: String, type: Type<T>, path: String): T? {
    if (!has(name) || isNull(name)) return null
    return type.parse(opt(name), "$path.$name")
}

/**
 * Limits checked on the raw text before it's parsed, so a hostile file can't make the parser use
 * lots of memory or recurse deeply: total size, nesting depth and the number of values.
 */
object JsonGuard {
    const val MAX_BYTES = 256 * 1024
    const val MAX_DEPTH = 8
    const val MAX_VALUES = 5000

    /** Throws [SchemaError] (path "file") when [text] is over a limit. */
    fun check(text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw SchemaError("file", "is larger than ${MAX_BYTES / 1024} KB")
        var depth = 0
        var values = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> { depth++; values++; if (depth > MAX_DEPTH) throw SchemaError("file", "is nested more than $MAX_DEPTH levels deep") }
                '}', ']' -> depth--
                ',', ':' -> { values++; if (values > MAX_VALUES) throw SchemaError("file", "has more than $MAX_VALUES values") }
            }
        }
    }
}
