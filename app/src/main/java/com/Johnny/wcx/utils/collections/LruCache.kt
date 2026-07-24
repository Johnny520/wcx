package com.Johnny.wcx.utils.collections

class LruCache<K, V>(
    initialCapacity: Int = 16,
    loadFactor: Float = 0.75f,
    private val maxLimit: Int = 100
) : LinkedHashMap<K, V>(initialCapacity, loadFactor, true) {

    @Synchronized
    override fun get(key: K): V? = super.get(key)

    @Synchronized
    override fun getOrDefault(key: K, defaultValue: V): V = super.getOrDefault(key, defaultValue)

    @Synchronized
    override fun put(key: K, value: V): V? = super.put(key, value)

    @Synchronized
    override fun putAll(from: Map<out K, V>) = super.putAll(from)

    @Synchronized
    override fun remove(key: K): V? = super.remove(key)

    @Synchronized
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
        return size > maxLimit
    }

    @Synchronized
    override fun clear() = super.clear()

    @Synchronized
    override val size: Int get() = super.size

    @Synchronized
    override fun isEmpty(): Boolean = super.isEmpty()

    @Synchronized
    override fun containsKey(key: K): Boolean = super.containsKey(key)

    @Synchronized
    override fun containsValue(value: V): Boolean = super.containsValue(value)
}
