package book.util

class SynchronizedLruCache<K, V>(
    private val maxSize: Int,
    private val onEvict: (V) -> Unit = {}
) {

    init {
        require(maxSize > 0) { "maxSize must be greater than 0" }
    }

    private val cache = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            val remove = size > maxSize
            if (remove && eldest != null) evict(eldest.value)
            return remove
        }
    }

    @Synchronized
    operator fun get(key: K): V? = cache[key]

    @Synchronized
    fun put(key: K, value: V): V? = cache.put(key, value).also {
        if (it != null && it !== value) evict(it)
    }

    @Synchronized
    fun getOrPut(key: K, isValid: (V) -> Boolean = { true }, defaultValue: () -> V): V {
        val existing = cache[key]
        if (existing != null && isValid(existing)) {
            return existing
        }
        if (existing != null) {
            cache.remove(key)
            evict(existing)
        }
        val value = defaultValue()
        cache[key] = value
        return value
    }

    @Synchronized
    fun getOrRemove(key: K, predicate: (V) -> Boolean): V? {
        val value = cache[key] ?: return null
        if (predicate(value)) return value
        cache.remove(key)?.let(::evict)
        return null
    }

    @Synchronized
    fun remove(key: K): V? = cache.remove(key).also { if (it != null) evict(it) }

    @Synchronized
    fun clear() {
        cache.values.forEach(::evict)
        cache.clear()
    }

    @Synchronized
    fun size(): Int = cache.size

    @Synchronized
    fun keys(): Set<K> = LinkedHashSet(cache.keys)

    private fun evict(value: V) {
        runCatching { onEvict(value) }
    }
}
