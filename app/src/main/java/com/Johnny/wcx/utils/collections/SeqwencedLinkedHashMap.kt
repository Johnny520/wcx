@file:Suppress("UNCHECKED_CAST", "unused")

package com.Johnny.wcx.utils.collections

import java.util.AbstractMap
import java.util.Arrays

private const val DEFAULT_CAPACITY = 16
private const val DEFAULT_LOAD_FACTOR = 0.75f
private const val MAXIMUM_CAPACITY = 1 shl 30

/** 与 HashMap.tableSizeFor 等价:向上取到不小于 cap 的最小 2 的幂。 */
private fun tableSizeFor(cap: Int): Int {
    var n = -1 ushr Integer.numberOfLeadingZeros((cap - 1).coerceAtLeast(0))
    if (n < 0) n = 1
    return if (n >= MAXIMUM_CAPACITY) MAXIMUM_CAPACITY else n + 1
}

/** 与 HashMap.hash 等价的扰动函数,把 hashCode 的高位也混进低位,减少碰撞。 */
private fun spreadHash(key: Any?): Int {
    val h = key?.hashCode() ?: 0
    return h xor (h ushr 16)
}

/**
 * 完全独立实现的、支持 Sequenced 操作的 LinkedHashMap 替代品。
 *
 * 底层是一个"分离链接哈希表"(bucket 数组 + 每个 bucket 一条单向链表用于
 * 解决哈希冲突) 叠加一条贯穿所有节点的"侵入式双向链表"(用于维护插入 /
 * 访问顺序,并支撑 O(1) 的 first/last 系列操作)。两条链表共用同一批
 * Node 对象,互不干扰。
 *
 * @param accessOrder false = 插入顺序(默认,行为等价 LinkedHashMap);
 *                    true  = 访问顺序(等价 LinkedHashMap 的 LRU 模式,
 *                    get/put 命中已存在的 key 会把该项挪到队尾)。
 */
open class SequencedLinkedHashMap<K, V> @JvmOverloads constructor(
    initialCapacity: Int = DEFAULT_CAPACITY,
    private val loadFactor: Float = DEFAULT_LOAD_FACTOR,
    private val accessOrder: Boolean = false
) : SequencedMap<K, V> {

    init {
        require(initialCapacity >= 0) { "Illegal initial capacity: $initialCapacity" }
        require(loadFactor > 0f && !loadFactor.isNaN()) { "Illegal load factor: $loadFactor" }
    }

    /** 用一个已有 Map 的内容构造(行为对齐 LinkedHashMap(Map) 拷贝构造器)。 */
    constructor(original: Map<out K, V>) : this(
        initialCapacity = (original.size / DEFAULT_LOAD_FACTOR).toInt().coerceAtLeast(DEFAULT_CAPACITY - 1) + 1,
        loadFactor = DEFAULT_LOAD_FACTOR,
        accessOrder = false
    ) {
        putAllInternal(original)
    }

    // -------------------------------------------------------------
    // 节点定义:同时承担 (a) bucket 内哈希冲突链 (hashNext)
    //                  (b) 全局顺序双向链 (before / after)
    // -------------------------------------------------------------
    private class Node<K, V>(
        @JvmField val hash: Int,
        override val key: K,
        override var value: V,
        @JvmField var hashNext: Node<K, V>?
    ) : MutableMap.MutableEntry<K, V> {

        @JvmField
        var before: Node<K, V>? = null
        @JvmField
        var after: Node<K, V>? = null

        override fun setValue(newValue: V): V {
            val old = value
            value = newValue
            return old
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            val e = other as? Map.Entry<*, *> ?: return false
            return key == e.key && value == e.value
        }

        override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)

        override fun toString(): String = "$key=$value"
    }

    private var table: Array<Node<K, V>?> = arrayOfNulls(tableSizeFor(initialCapacity).coerceAtLeast(1))
    private var head: Node<K, V>? = null // 最早插入 / 最久未访问
    private var tail: Node<K, V>? = null // 最近插入 / 最近访问
    private var mapSize = 0
    private var threshold = (table.size * loadFactor).toInt()

    /** 结构性修改计数器,用于迭代器 fail-fast。 putFirst/putLast/access-order
     *  引发的顺序调整也算作一次结构性修改,因为它会让正在进行中的迭代
     *  跳过或重复元素。 */
    private var modCount = 0

    // -------------------------------------------------------------
    // 基础 Map 读操作
    // -------------------------------------------------------------

    override val size: Int get() = mapSize

    override fun isEmpty(): Boolean = mapSize == 0

    private fun findNode(key: K): Node<K, V>? {
        val h = spreadHash(key)
        var n = table[h and table.size - 1]
        while (n != null) {
            if (n.hash == h && n.key == key) return n
            n = n.hashNext
        }
        return null
    }

    override fun containsKey(key: K): Boolean = findNode(key) != null

    override fun containsValue(value: V): Boolean {
        var n = head
        while (n != null) {
            if (n.value == value) return true
            n = n.after
        }
        return false
    }

    override fun get(key: K): V? {
        val node = findNode(key) ?: return null
        if (accessOrder) moveToLast(node)
        return node.value
    }

    // -------------------------------------------------------------
    // 双向链表维护(顺序链,不涉及 bucket)
    // -------------------------------------------------------------

    private fun linkFirst(node: Node<K, V>) {
        val h = head
        node.before = null
        node.after = h
        if (h == null) tail = node else h.before = node
        head = node
    }

    private fun linkLast(node: Node<K, V>) {
        val t = tail
        node.after = null
        node.before = t
        if (t == null) head = node else t.after = node
        tail = node
    }

    private fun unlinkOrder(node: Node<K, V>) {
        val b = node.before
        val a = node.after
        if (b == null) head = a else b.after = a
        if (a == null) tail = b else a.before = b
        node.before = null
        node.after = null
    }

    private fun moveToFirst(node: Node<K, V>) {
        if (head === node) return
        unlinkOrder(node)
        linkFirst(node)
        modCount++
    }

    private fun moveToLast(node: Node<K, V>) {
        if (tail === node) return
        unlinkOrder(node)
        linkLast(node)
        modCount++
    }

    // -------------------------------------------------------------
    // 扩容
    // -------------------------------------------------------------

    private fun resize() {
        val oldCap = table.size
        if (oldCap >= MAXIMUM_CAPACITY) {
            threshold = Int.MAX_VALUE
            return
        }
        val doubled = oldCap shl 1
        val newCap = if (doubled !in 1..MAXIMUM_CAPACITY) MAXIMUM_CAPACITY else doubled
        val newTable = arrayOfNulls<Node<K, V>?>(newCap)
        // 沿着顺序链重新分桶,天然保持插入/访问顺序不受影响。
        var n = head
        while (n != null) {
            val idx = n.hash and newCap - 1
            n.hashNext = newTable[idx]
            newTable[idx] = n
            n = n.after
        }
        table = newTable
        threshold = (newCap * loadFactor).toInt()
    }

    // -------------------------------------------------------------
    // 插入 / 删除节点(同时维护 bucket 链与顺序链)
    // -------------------------------------------------------------

    private fun addNewNode(key: K, value: V, atFront: Boolean) {
        if (mapSize + 1 > threshold) resize()
        val h = spreadHash(key)
        val idx = h and table.size - 1
        val node = Node(h, key, value, table[idx])
        table[idx] = node
        if (atFront) linkFirst(node) else linkLast(node)
        mapSize++
        modCount++
        afterNodeInsertion()
    }

    private fun removeNode(node: Node<K, V>): V {
        val idx = node.hash and table.size - 1
        var prev: Node<K, V>? = null
        var cur = table[idx]
        while (cur != null) {
            if (cur === node) {
                if (prev == null) table[idx] = cur.hashNext else prev.hashNext = cur.hashNext
                break
            }
            prev = cur
            cur = cur.hashNext
        }
        unlinkOrder(node)
        mapSize--
        modCount++
        return node.value
    }

    /**
     * 对齐 java.util.LinkedHashMap#removeEldestEntry 钩子:每次插入新节点后
     * 都会用当前最老的条目(head)调用一次。默认返回 false(不淘汰),
     * 子类可以覆盖它来实现一个 LRU Cache(通常搭配 accessOrder = true)。
     */
    protected open fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = false

    private fun afterNodeInsertion() {
        val first = head
        if (first != null && removeEldestEntry(first)) {
            removeNode(first)
        }
    }

    // -------------------------------------------------------------
    // 基础 Map 写操作
    // -------------------------------------------------------------

    override fun put(key: K, value: V): V? {
        val node = findNode(key)
        if (node != null) {
            val old = node.value
            node.value = value
            if (accessOrder) moveToLast(node)
            return old
        }
        addNewNode(key, value, atFront = false)
        return null
    }

    override fun remove(key: K): V? {
        val node = findNode(key) ?: return null
        return removeNode(node)
    }

    private fun putAllInternal(from: Map<out K, V>) {
        for (e in from.entries) put(e.key, e.value)
    }

    override fun putAll(from: Map<out K, V>) = putAllInternal(from)

    override fun clear() {
        Arrays.fill(table, null)
        head = null
        tail = null
        mapSize = 0
        modCount++
    }

    // -------------------------------------------------------------
    // Sequenced 系列:O(1) 显式定位操作
    // -------------------------------------------------------------

    override fun putFirst(key: K, value: V): V? {
        val node = findNode(key)
        if (node != null) {
            val old = node.value
            node.value = value
            moveToFirst(node)
            return old
        }
        addNewNode(key, value, atFront = true)
        return null
    }

    override fun putLast(key: K, value: V): V? {
        val node = findNode(key)
        if (node != null) {
            val old = node.value
            node.value = value
            moveToLast(node)
            return old
        }
        addNewNode(key, value, atFront = false)
        return null
    }

    override fun firstEntry(): MutableMap.MutableEntry<K, V>? = head

    override fun lastEntry(): MutableMap.MutableEntry<K, V>? = tail

    override fun pollFirstEntry(): MutableMap.MutableEntry<K, V>? {
        val h = head ?: return null
        val snapshot = AbstractMap.SimpleImmutableEntry(h.key, h.value) as MutableMap.MutableEntry<K, V>
        removeNode(h)
        return snapshot
    }

    override fun pollLastEntry(): MutableMap.MutableEntry<K, V>? {
        val t = tail ?: return null
        val snapshot = AbstractMap.SimpleImmutableEntry(t.key, t.value) as MutableMap.MutableEntry<K, V>
        removeNode(t)
        return snapshot
    }

    // -------------------------------------------------------------
    // 顺序敏感的迭代器基础设施
    // -------------------------------------------------------------

    /** forward = true 时沿 head->after 走(正序);否则沿 tail->before 走(逆序)。 */
    private inner class NodeWalker(start: Node<K, V>?, private val forward: Boolean) {
        private var nextNode: Node<K, V>? = start
        private var lastReturned: Node<K, V>? = null
        private var expectedModCount = modCount

        fun hasNext(): Boolean = nextNode != null

        fun nextNode(): Node<K, V> {
            checkForComodification()
            val n = nextNode ?: throw NoSuchElementException()
            lastReturned = n
            nextNode = if (forward) n.after else n.before
            return n
        }

        fun remove() {
            val c = lastReturned ?: throw IllegalStateException("next() has not been called, or remove() already called after the last call to next()")
            checkForComodification()
            lastReturned = null
            removeNode(c)
            expectedModCount = modCount
        }

        private fun checkForComodification() {
            if (modCount != expectedModCount) throw ConcurrentModificationException()
        }
    }

    private inner class KeyIterator(forward: Boolean) : MutableIterator<K> {
        private val base = NodeWalker(if (forward) head else tail, forward)
        override fun hasNext() = base.hasNext()
        override fun next(): K = base.nextNode().key
        override fun remove() = base.remove()
    }

    private inner class ValueIterator(forward: Boolean) : MutableIterator<V> {
        private val base = NodeWalker(if (forward) head else tail, forward)
        override fun hasNext() = base.hasNext()
        override fun next(): V = base.nextNode().value
        override fun remove() = base.remove()
    }

    private inner class EntryIterator(forward: Boolean) : MutableIterator<MutableMap.MutableEntry<K, V>> {
        private val base = NodeWalker(if (forward) head else tail, forward)
        override fun hasNext() = base.hasNext()
        override fun next(): MutableMap.MutableEntry<K, V> = base.nextNode()
        override fun remove() = base.remove()
    }

    // -------------------------------------------------------------
    // keySet() / values() / entrySet() 活视图,同时也是 Sequenced* 视图
    // -------------------------------------------------------------

    private inner class KeySetView(private val forward: Boolean) :
        AbstractMutableSet<K>(), SequencedSet<K> {

        override val size: Int get() = mapSize
        override fun iterator(): MutableIterator<K> = KeyIterator(forward)
        override fun add(element: K): Boolean = throw UnsupportedOperationException("keySet() does not support add")
        override fun contains(element: K): Boolean = containsKey(element)
        override fun remove(element: K): Boolean {
            val node = findNode(element) ?: return false
            removeNode(node)
            return true
        }

        override fun clear() = this@SequencedLinkedHashMap.clear()
        override fun reversed(): SequencedSet<K> = KeySetView(!forward)
    }

    private inner class ValuesView(private val forward: Boolean) :
        AbstractMutableCollection<V>(), SeqwencedCollection<V> {

        override val size: Int get() = mapSize
        override fun iterator(): MutableIterator<V> = ValueIterator(forward)
        override fun add(element: V): Boolean = throw UnsupportedOperationException("values() does not support add")
        override fun contains(element: V): Boolean = containsValue(element)
        override fun remove(element: V): Boolean {
            var n = if (forward) head else tail
            while (n != null) {
                if (n.value == element) {
                    removeNode(n)
                    return true
                }
                n = if (forward) n.after else n.before
            }
            return false
        }

        override fun clear() = this@SequencedLinkedHashMap.clear()
        override fun reversed(): SeqwencedCollection<V> = ValuesView(!forward)
    }

    private inner class EntrySetView(private val forward: Boolean) :
        AbstractMutableSet<MutableMap.MutableEntry<K, V>>(),
        SequencedSet<MutableMap.MutableEntry<K, V>> {

        override val size: Int get() = mapSize
        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> = EntryIterator(forward)
        override fun add(element: MutableMap.MutableEntry<K, V>): Boolean =
            throw UnsupportedOperationException("entrySet() does not support add")

        override fun contains(element: MutableMap.MutableEntry<K, V>): Boolean {
            val node = findNode(element.key) ?: return false
            return node.value == element.value
        }

        override fun remove(element: MutableMap.MutableEntry<K, V>): Boolean {
            val node = findNode(element.key) ?: return false
            if (node.value != element.value) return false
            removeNode(node)
            return true
        }

        override fun clear() = this@SequencedLinkedHashMap.clear()
        override fun reversed(): SequencedSet<MutableMap.MutableEntry<K, V>> = EntrySetView(!forward)
    }

    private var keySetView: KeySetView? = null
    private var valuesView: ValuesView? = null
    private var entrySetView: EntrySetView? = null

    override val keys: MutableSet<K>
        get() = keySetView ?: KeySetView(true).also { keySetView = it }

    override val values: MutableCollection<V>
        get() = valuesView ?: ValuesView(true).also { valuesView = it }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = entrySetView ?: EntrySetView(true).also { entrySetView = it }

    override fun sequencedKeySet(): SequencedSet<K> = keys as SequencedSet<K>
    override fun sequencedValues(): SeqwencedCollection<V> = values as SeqwencedCollection<V>
    override fun sequencedEntrySet(): SequencedSet<MutableMap.MutableEntry<K, V>> = entries as SequencedSet<MutableMap.MutableEntry<K, V>>

    // -------------------------------------------------------------
    // reversed():活视图,不拷贝
    // -------------------------------------------------------------

    private inner class ReversedMapView : SequencedMap<K, V> {
        private inline val outer get() = this@SequencedLinkedHashMap

        override val size: Int get() = outer.size
        override fun isEmpty(): Boolean = outer.isEmpty()
        override fun containsKey(key: K): Boolean = outer.containsKey(key)
        override fun containsValue(value: V): Boolean = outer.containsValue(value)
        override fun get(key: K): V? = outer[key]
        override fun put(key: K, value: V): V? = outer.put(key, value)
        override fun remove(key: K): V? = outer.remove(key)
        override fun putAll(from: Map<out K, V>) = outer.putAll(from)
        override fun clear() = outer.clear()

        override val keys: MutableSet<K> get() = outer.sequencedKeySet().reversed()
        override val values: MutableCollection<V> get() = outer.sequencedValues().reversed()
        override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
            get() = outer.sequencedEntrySet().reversed()

        override fun sequencedKeySet(): SequencedSet<K> = outer.sequencedKeySet().reversed()
        override fun sequencedValues(): SeqwencedCollection<V> = outer.sequencedValues().reversed()
        override fun sequencedEntrySet(): SequencedSet<MutableMap.MutableEntry<K, V>> = outer.sequencedEntrySet().reversed()

        override fun firstEntry(): MutableMap.MutableEntry<K, V>? = outer.lastEntry()
        override fun lastEntry(): MutableMap.MutableEntry<K, V>? = outer.firstEntry()
        override fun pollFirstEntry(): MutableMap.MutableEntry<K, V>? = outer.pollLastEntry()
        override fun pollLastEntry(): MutableMap.MutableEntry<K, V>? = outer.pollFirstEntry()
        override fun putFirst(key: K, value: V): V? = outer.putLast(key, value)
        override fun putLast(key: K, value: V): V? = outer.putFirst(key, value)

        override fun reversed(): SequencedMap<K, V> = outer

        override fun equals(other: Any?): Boolean = outer == other
        override fun hashCode(): Int = outer.hashCode()

        override fun toString(): String {
            if (outer.isEmpty()) return "{}"
            val sb = StringBuilder("{")
            var n = outer.tail
            var first = true
            while (n != null) {
                if (!first) sb.append(", ")
                first = false
                sb.append(if (n.key === this) "(this Map)" else n.key.toString())
                sb.append('=')
                sb.append(if (n.value === this) "(this Map)" else n.value.toString())
                n = n.before
            }
            sb.append('}')
            return sb.toString()
        }
    }

    private var reversedViewCache: ReversedMapView? = null

    override fun reversed(): SequencedMap<K, V> =
        reversedViewCache ?: ReversedMapView().also { reversedViewCache = it }

    // -------------------------------------------------------------
    // Object 契约:equals / hashCode / toString(语义对齐 java.util.Map)
    // -------------------------------------------------------------

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val m = other as? Map<*, *> ?: return false
        if (m.size != size) return false
        var n = head
        while (n != null) {
            val k = n.key
            if (!m.containsKey(k)) return false
            if (m[k] != n.value) return false
            n = n.after
        }
        return true
    }

    override fun hashCode(): Int {
        var h = 0
        var n = head
        while (n != null) {
            h += n.hashCode()
            n = n.after
        }
        return h
    }

    override fun toString(): String {
        if (head == null) return "{}"
        val sb = StringBuilder("{")
        var n = head
        var first = true
        while (n != null) {
            if (!first) sb.append(", ")
            first = false
            sb.append(if (n.key === this) "(this Map)" else n.key.toString())
            sb.append('=')
            sb.append(if (n.value === this) "(this Map)" else n.value.toString())
            n = n.after
        }
        sb.append('}')
        return sb.toString()
    }
}

// ---------------------------------------------------------------------
// 便捷工厂函数,风格对齐 kotlin.collections.linkedMapOf / mutableMapOf
// ---------------------------------------------------------------------

fun <K, V> sequencedMapOf(): SequencedMap<K, V> = SequencedLinkedHashMap()

fun <K, V> sequencedMapOf(vararg pairs: Pair<K, V>): SequencedMap<K, V> =
    SequencedLinkedHashMap<K, V>(initialCapacity = (pairs.size / 0.75f).toInt() + 1).apply {
        for ((k, v) in pairs) put(k, v)
    }
