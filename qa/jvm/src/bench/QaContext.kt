package com.askthecar.qa.bench

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.SensorManager
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One app sandbox on disk: files/, cache/, shared_prefs/ under [root]. Real java.io.File everywhere.
 * SharedPreferences persist to disk so a fresh JVM pointed at the same root sees a "restarted" app.
 */
class QaContext(val root: File, accelerometer: Boolean = true, gyroscope: Boolean = false, filesDirOverride: File? = null) : Context() {
    private val files = filesDirOverride ?: File(root, "files")
    private val cache = File(root, "cache")
    val sensors = SensorManager(accelerometer, gyroscope)
    val notifications = NotificationManager()
    val stoppedServices = CopyOnWriteArrayList<Class<*>>()
    private val prefs = ConcurrentHashMap<String, FilePrefs>()

    init { runCatching { files.mkdirs() }; cache.mkdirs(); File(root, "shared_prefs").mkdirs() }

    override fun getFilesDir(): File = files
    override fun getCacheDir(): File = cache
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { FilePrefs(File(root, "shared_prefs/$name.properties")) }
    @Suppress("UNCHECKED_CAST")
    override fun <T> getSystemService(serviceClass: Class<T>): T = when (serviceClass) {
        SensorManager::class.java -> sensors as T
        NotificationManager::class.java -> notifications as T
        else -> throw IllegalArgumentException("no stub service ${serviceClass.name}")
    }
    override fun stopService(service: Intent): Boolean { stoppedServices += service.component; return true }
}

/** SharedPreferences persisted as a typed properties file; commit()/apply() both write synchronously. */
class FilePrefs(private val file: File) : SharedPreferences {
    private val lock = Any()
    private val values = LinkedHashMap<String, Any>()

    init {
        if (file.exists()) Properties().apply { file.reader(Charsets.UTF_8).use(::load) }.forEach { (k, v) ->
            val s = v.toString()
            values[k.toString()] = when (s.take(2)) {
                "b:" -> s.drop(2).toBoolean(); "i:" -> s.drop(2).toInt(); "l:" -> s.drop(2).toLong(); "f:" -> s.drop(2).toFloat()
                else -> s.drop(2)
            }
        }
    }

    override fun getAll(): Map<String, *> = synchronized(lock) { LinkedHashMap(values) }
    override fun getString(key: String, defValue: String?): String? = synchronized(lock) { values[key] as? String ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(lock) { values[key] as? Boolean ?: defValue }
    override fun getInt(key: String, defValue: Int): Int = synchronized(lock) { values[key] as? Int ?: defValue }
    override fun getLong(key: String, defValue: Long): Long = synchronized(lock) { values[key] as? Long ?: defValue }
    override fun getFloat(key: String, defValue: Float): Float = synchronized(lock) { values[key] as? Float ?: defValue }
    override fun contains(key: String): Boolean = synchronized(lock) { key in values }
    override fun edit(): SharedPreferences.Editor = Editor()

    private fun persist() {
        val p = Properties()
        values.forEach { (k, v) ->
            p[k] = when (v) { is Boolean -> "b:$v"; is Int -> "i:$v"; is Long -> "l:$v"; is Float -> "f:$v"; else -> "s:$v" }
        }
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writer(Charsets.UTF_8).use { p.store(it, null) }
        if (!tmp.renameTo(file)) error("prefs rename failed")
    }

    private inner class Editor : SharedPreferences.Editor {
        private val puts = LinkedHashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun remove(key: String) = apply { puts[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            synchronized(lock) {
                if (clear) values.clear()
                puts.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                persist()
            }
            return true
        }
        override fun apply() { commit() }
    }
}
