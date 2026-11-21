package com.emofah.agencies.recognition

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Local binary vector store for L2-normalized image feature embeddings produced by
 * the bundled `emofah_vision_model.tflite` engine.
 *
 * Stored outside Room under `filesDir/recognition_embeddings/prod_<id>.vec` so Room's
 * schema version remains untouched while keeping large float arrays off SQLite rows.
 */
class EmbeddingCacheStore(
    private val storageDir: File,
) {
    constructor(context: Context) : this(File(context.filesDir, "recognition_embeddings"))

    private val memoryCache = ConcurrentHashMap<Long, FloatArray>()

    init {
        storageDir.mkdirs()
    }

    fun hasEmbedding(productId: Long): Boolean {
        if (memoryCache.containsKey(productId)) return true
        val file = vectorFile(productId)
        return file.exists() && file.length() > HEADER_BYTES
    }

    fun saveEmbedding(productId: Long, rawVector: FloatArray): FloatArray {
        val normalized = l2Normalize(rawVector)
        memoryCache[productId] = normalized
        runCatching {
            storageDir.mkdirs()
            val target = vectorFile(productId)
            val tmp = File(storageDir, "${target.name}.tmp")
            val buffer = ByteBuffer.allocate(HEADER_BYTES + normalized.size * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(MAGIC)
            buffer.putInt(normalized.size)
            for (v in normalized) {
                buffer.putFloat(v)
            }
            tmp.writeBytes(buffer.array())
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        return normalized
    }

    fun loadEmbedding(productId: Long, expectedDim: Int? = null): FloatArray? {
        memoryCache[productId]?.let { cached ->
            if (expectedDim == null || cached.size == expectedDim) return cached
        }
        val file = vectorFile(productId)
        if (!file.exists() || file.length() <= HEADER_BYTES) return null
        return runCatching {
            val bytes = RandomAccessFile(file, "r").use { raf ->
                val len = raf.length().toInt()
                ByteArray(len).also { raf.readFully(it) }
            }
            if (bytes.size <= HEADER_BYTES) return@runCatching null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            buffer.get(magic)
            if (!magic.contentEquals(MAGIC)) return@runCatching null
            val dim = buffer.int
            if (dim <= 0 || dim > 16_384) return@runCatching null
            if (expectedDim != null && dim != expectedDim) return@runCatching null
            if (bytes.size < HEADER_BYTES + dim * 4) return@runCatching null
            val vec = FloatArray(dim)
            for (i in 0 until dim) {
                vec[i] = buffer.float
            }
            val normalized = l2Normalize(vec)
            memoryCache[productId] = normalized
            normalized
        }.getOrNull()
    }

    fun loadAllEmbeddings(
        productIds: Collection<Long>,
        expectedDim: Int? = null,
    ): Map<Long, FloatArray> {
        val out = LinkedHashMap<Long, FloatArray>(productIds.size)
        for (pid in productIds) {
            val vec = loadEmbedding(pid, expectedDim)
            if (vec != null) {
                out[pid] = vec
            }
        }
        return out
    }

    fun deleteEmbedding(productId: Long) {
        memoryCache.remove(productId)
        runCatching { vectorFile(productId).delete() }
    }

    fun clear() {
        memoryCache.clear()
        runCatching {
            storageDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun vectorFile(productId: Long): File = File(storageDir, "prod_${productId}.vec")

    companion object {
        private val MAGIC = byteArrayOf('E'.code.toByte(), 'M'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
        private const val HEADER_BYTES = 8

        fun l2Normalize(vector: FloatArray): FloatArray {
            if (vector.isEmpty()) return vector
            var sumSq = 0.0
            for (v in vector) {
                val finite = if (v.isFinite()) v.toDouble() else 0.0
                sumSq += finite * finite
            }
            val norm = sqrt(sumSq)
            if (norm <= 1e-12) {
                return FloatArray(vector.size)
            }
            val out = FloatArray(vector.size)
            val inv = (1.0 / norm).toFloat()
            for (i in vector.indices) {
                val v = vector[i]
                out[i] = if (v.isFinite()) v * inv else 0f
            }
            return out
        }

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
            if (a.isEmpty() || a.size != b.size) return 0.0
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                val av = if (a[i].isFinite()) a[i].toDouble() else 0.0
                val bv = if (b[i].isFinite()) b[i].toDouble() else 0.0
                dot += av * bv
                normA += av * av
                normB += bv * bv
            }
            val denom = sqrt(normA) * sqrt(normB)
            if (denom <= 1e-12) return 0.0
            return (dot / denom).coerceIn(0.0, 1.0)
        }
    }
}
