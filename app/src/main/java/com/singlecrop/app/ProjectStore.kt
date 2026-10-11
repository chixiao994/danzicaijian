package com.singlecrop.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CropRecord(val x: Int, val y: Int, val w: Int, val h: Int)

class Project {
    var inputTreeUri: String? = null
    var outputTreeUri: String? = null
    var lastIndex: Int = 0
    val records = LinkedHashMap<String, CropRecord>()

    fun reset(input: String?) {
        inputTreeUri = input
        outputTreeUri = null
        lastIndex = 0
        records.clear()
    }

    fun toJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("inputTreeUri", inputTreeUri ?: "")
        root.put("outputTreeUri", outputTreeUri ?: "")
        root.put("lastIndex", lastIndex)
        val arr = JSONArray()
        for ((name, v) in records) {
            val o = JSONObject()
            o.put("name", name)
            o.put("x", v.x)
            o.put("y", v.y)
            o.put("w", v.w)
            o.put("h", v.h)
            arr.put(o)
        }
        root.put("records", arr)
        return root.toString()
    }

    companion object {
        fun fromJson(s: String): Project {
            val p = Project()
            try {
                val root = JSONObject(s)
                p.inputTreeUri = root.optString("inputTreeUri").ifEmpty { null }
                p.outputTreeUri = root.optString("outputTreeUri").ifEmpty { null }
                p.lastIndex = root.optInt("lastIndex", 0)
                val arr = root.optJSONArray("records") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name")
                    if (name.isEmpty()) continue
                    p.records[name] = CropRecord(
                        o.optInt("x"),
                        o.optInt("y"),
                        o.optInt("w"),
                        o.optInt("h")
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return p
        }
    }
}

object ProjectStore {

    private const val FILE_NAME = "singlecrop_project.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    fun load(ctx: Context): Project {
        return try {
            val f = file(ctx)
            if (!f.exists()) return Project()
            Project.fromJson(f.readText())
        } catch (e: Exception) {
            e.printStackTrace()
            Project()
        }
    }

    fun write(ctx: Context, json: String) {
        try {
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json)
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
