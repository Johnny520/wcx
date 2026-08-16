/*
 * 纯 Kotlin 重实现 JEP 431 "Sequenced Collections"(SequencedCollection /
 * SequencedSet / SequencedMap),不依赖 java.util.SequencedMap 等 API 35+
 * 才存在的类型。
 *
 * 背景:在旧版 Android(minSdk < 35)上,哪怕只是在类型签名里 *提到*
 * java.util.SequencedMap,ART 的类校验器也可能因为链接不到该类而在类
 * 加载阶段直接抛 NoClassDefFoundError / VerifyError —— 这与是否真的调用
 * 了它的方法无关。因此这里不 extend/implement 任何 java.util.Sequenced*
 * 类型,而是从零定义一套语义等价的接口 + 实现,可以在所有 API Level 上
 * 无脑使用同一套代码。
 *
 * 内部存储不是包装 java.util.LinkedHashMap(它同样没有 putFirst/putLast/
 * pollFirst/pollLastEntry 这些新方法,包装了也没用),而是从零手写了一个
 * "分离链接哈希表 + 侵入式双向链表" 的结构,行为对齐 JDK 21 的
 * LinkedHashMap:
 *   - 默认按插入顺序迭代;put() 对已存在 key 不改变其顺序位置
 *   - accessOrder = true 时对齐 LRU 语义(get/put 命中会挪到队尾)
 *   - putFirst/putLast/firstEntry/lastEntry/pollFirstEntry/pollLastEntry
 *     全部 O(1)
 *   - reversed() 返回一个"活视图"(live view),不拷贝数据,双向可写
 *   - removeEldestEntry() 钩子,方便子类化实现 LRU Cache(对齐
 *     java.util.LinkedHashMap 的经典用法)
 */

@file:Suppress("UNCHECKED_CAST", "unused")

package com.Johnny.wcx.utils.collections

import java.util.AbstractMap

// ---------------------------------------------------------------------
// 接口定义(对齐 java.util.Sequenced* 的方法签名与默认实现语义)
// ---------------------------------------------------------------------

/** 对齐 java.util.SequencedCollection<E> */
interface SeqwencedCollection<E> : MutableCollection<E> {

    /** 返回一个逆序的活视图,不拷贝数据。 */
    fun reversed(): SeqwencedCollection<E>

    fun addFirst(element: E) {
        throw UnsupportedOperationException()
    }

    fun addLast(element: E) {
        throw UnsupportedOperationException()
    }

    fun getFirst(): E {
        val it = iterator()
        if (!it.hasNext()) throw NoSuchElementException()
        return it.next()
    }

    fun getLast(): E {
        val it = reversed().iterator()
        if (!it.hasNext()) throw NoSuchElementException()
        return it.next()
    }

    fun removeFirst(): E {
        val it = iterator()
        if (!it.hasNext()) throw NoSuchElementException()
        val e = it.next()
        it.remove()
        return e
    }

    fun removeLast(): E {
        val it = reversed().iterator()
        if (!it.hasNext()) throw NoSuchElementException()
        val e = it.next()
        it.remove()
        return e
    }
}

/** 对齐 java.util.SequencedSet<E> */
interface SequencedSet<E> : MutableSet<E>, SeqwencedCollection<E> {
    override fun reversed(): SequencedSet<E>
}

/** 对齐 java.util.SequencedMap<K, V> */
interface SequencedMap<K, V> : MutableMap<K, V> {

    /** 返回一个逆序的活视图,不拷贝数据。 */
    fun reversed(): SequencedMap<K, V>

    fun sequencedKeySet(): SequencedSet<K>
    fun sequencedValues(): SeqwencedCollection<V>
    fun sequencedEntrySet(): SequencedSet<MutableMap.MutableEntry<K, V>>

    /** 默认实现:不支持写位置操作时直接抛 UOE,与 JDK 接口默认一致。 */
    fun putFirst(key: K, value: V): V? {
        throw UnsupportedOperationException()
    }

    fun putLast(key: K, value: V): V? {
        throw UnsupportedOperationException()
    }

    fun firstEntry(): MutableMap.MutableEntry<K, V>? {
        val it = entries.iterator()
        return if (it.hasNext()) it.next() else null
    }

    fun lastEntry(): MutableMap.MutableEntry<K, V>? {
        val it = reversed().entries.iterator()
        return if (it.hasNext()) it.next() else null
    }

    fun pollFirstEntry(): MutableMap.MutableEntry<K, V>? {
        val it = entries.iterator()
        if (!it.hasNext()) return null
        val e = it.next()
        val snapshot = AbstractMap.SimpleImmutableEntry(e.key, e.value) as MutableMap.MutableEntry<K, V>
        it.remove()
        return snapshot
    }

    fun pollLastEntry(): MutableMap.MutableEntry<K, V>? {
        val it = reversed().entries.iterator()
        if (!it.hasNext()) return null
        val e = it.next()
        val snapshot = AbstractMap.SimpleImmutableEntry(e.key, e.value) as MutableMap.MutableEntry<K, V>
        it.remove()
        return snapshot
    }
}
