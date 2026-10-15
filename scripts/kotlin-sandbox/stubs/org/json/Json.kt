// Sandbox-only stand-ins, see ../../../../android/android_content.kt.
package org.json

/** Structure-only stand-ins for the JSON surface Entry/DatabaseHelper serialize through. */
class JSONException(message: String?) : Exception(message)

class JSONArray {
    constructor()
    constructor(json: String?)

    fun length(): Int = 0
    fun getJSONObject(index: Int): JSONObject = JSONObject()
    fun getString(index: Int): String = ""
    fun put(value: Any?): JSONArray = this
}

class JSONObject {
    constructor()
    constructor(json: String?)

    fun has(name: String?): Boolean = false
    fun getString(name: String?): String = ""
    fun optString(name: String?): String? = null
    fun optString(name: String?, fallback: String?): String? = fallback
    fun optInt(name: String?): Int = 0
    fun optInt(name: String?, fallback: Int): Int = fallback
    fun optBoolean(name: String?): Boolean = false
    fun optBoolean(name: String?, fallback: Boolean): Boolean = fallback
    fun optLong(name: String?): Long = 0
    fun optLong(name: String?, fallback: Long): Long = fallback
    fun getJSONArray(name: String?): JSONArray = JSONArray()
    fun put(name: String?, value: Any?): JSONObject = this
    fun keys(): Iterator<String> = emptyList<String>().iterator()
}
