package com.gaius.taoconnect

import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.ArrayDeque

/** Local translation only. Bounded work, shared requests and no page text on disk. */
internal class BilingualTranslator(private val status: (String) -> Unit) : AutoCloseable {
    private val translator = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.CHINESE)
        .setTargetLanguage(TranslateLanguage.FRENCH).build())
    private val cache = LruCache<String, String>(512)
    private val pending = linkedMapOf<String, MutableList<(String?) -> Unit>>()
    private val queue = ArrayDeque<String>()
    private var ready = false
    private var preparing = false
    private var closed = false
    private var active = 0
    private var generation = 0
    var machineRequests = 0
        private set

    fun prepare() {
        if (ready || preparing || closed) return
        preparing = true
        status("Préparation du français sur cet appareil…")
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                if (closed) return@addOnSuccessListener
                preparing = false
                ready = true
                status("Chinois + français · Google Traduction")
                drain()
            }.addOnFailureListener {
                if (closed) return@addOnFailureListener
                preparing = false
                status("Modèle indisponible · vérifiez Internet puis Réessayer")
                val callbacks = pending.values.flatMap { it.toList() }
                pending.clear()
                queue.clear()
                callbacks.forEach { it(null) }
            }
    }

    fun translate(text: String, callback: (String?) -> Unit) {
        if (closed) { callback(null); return }
        val source = TranslationTextPolicy.normalizeSource(text)
        if (source.length !in 1..600 || !TranslationTextPolicy.shouldTranslate(source)) {
            callback(null); return
        }
        TranslationTextPolicy.localTranslation(source)?.let { callback(it); return }
        cache.get(source)?.let { callback(it); return }
        pending[source]?.let {
            if (it.size < 16) it.add(callback) else callback(null)
            return
        }
        if (pending.size >= 192) { callback(null); return }
        pending[source] = mutableListOf(callback)
        queue.addLast(source)
        if (ready) drain() else prepare()
    }

    private fun drain() {
        while (!closed && ready && active < 2 && queue.isNotEmpty()) {
            val source = queue.removeFirst()
            val requestGeneration = generation
            active++
            machineRequests++
            translator.translate(source).addOnCompleteListener { task ->
                active--
                if (closed) return@addOnCompleteListener
                if (requestGeneration == generation) {
                    val result = if (task.isSuccessful)
                        TranslationTextPolicy.cleanTranslation(source, task.result) else null
                    if (result != null) cache.put(source, result)
                    pending.remove(source)?.forEach { it(result) }
                }
                drain()
            }
        }
    }

    fun cancelPage() {
        generation++
        pending.clear()
        queue.clear()
    }

    fun clearCache() { cache.evictAll() }

    override fun close() {
        closed = true
        cancelPage()
        cache.evictAll()
        translator.close()
    }
}
