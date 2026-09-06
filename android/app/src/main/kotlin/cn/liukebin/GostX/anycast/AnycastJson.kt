package cn.liukebin.gostx.anycast

import org.json.JSONArray
import org.json.JSONObject

internal object AnycastJson {
    private fun norm(s: String): String = s.filter { it.isLetterOrDigit() }.lowercase()

    fun find(root: Any?, vararg names: String): Any? = walk(root, names.map(::norm).toSet())

    private fun walk(value: Any?, wanted: Set<String>): Any? {
        when (value) {
            null, JSONObject.NULL -> return null
            is JSONObject -> {
                val keys = value.keys().asSequence().toList()
                for (key in keys) if (norm(key) in wanted) return value.opt(key).takeUnless { it == JSONObject.NULL }
                for (key in keys) walk(value.opt(key), wanted)?.let { return it }
            }
            is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), wanted)?.let { return it }
        }
        return null
    }

    fun direct(obj: JSONObject?, vararg names: String): Any? {
        if (obj == null) return null
        val wanted = names.map(::norm).toSet()
        val keys = obj.keys().asSequence().toList()
        for (key in keys) if (norm(key) in wanted) return obj.opt(key).takeUnless { it == JSONObject.NULL }
        return null
    }

    fun asObject(v: Any?): JSONObject? = v as? JSONObject
    fun asArray(v: Any?): JSONArray? = v as? JSONArray
    fun str(v: Any?): String? = when (v) {
        null, JSONObject.NULL -> null
        else -> v.toString().takeIf { it.isNotBlank() }
    }
    fun int(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        else -> v?.toString()?.toIntOrNull()
    }
}
