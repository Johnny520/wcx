package com.Johnny.wcx.utils.reflection

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque

/**
 * Checks if this method's base method in a superclass or interface is abstract.
 * * @return [Boolean] indicating if the base method is abstract, or `null` if
 * this method is the root definition and does not override anything.
 */
val Method.isBaseMethodAbstract: Boolean?
    get() {
        val baseMethod = findBaseMethod() ?: return null
        return Modifier.isAbstract(baseMethod.modifiers)
    }

/**
 * Traverses up the type hierarchy using a Breadth-First Search (BFS) to find
 * the closest matching method definition that this method overrides.
 */
fun Method.findBaseMethod(): Method? {
    val declaringClass = this.declaringClass
    val name = this.name
    val paramTypes = this.parameterTypes

    // Queue to traverse the superclasses and interfaces level by level
    val queue = ArrayDeque<Class<*>>()

    // Initialize queue with immediate superclass and interfaces
    declaringClass.superclass?.let { queue.add(it) }
    queue.addAll(declaringClass.interfaces)

    val visited = mutableSetOf<Class<*>>()

    while (!queue.isEmpty()) {
        val current = queue.poll()!!
        if (!visited.add(current)) continue

        // Step 1: Exact Match
        // Handles standard non-generic overrides seamlessly
        try {
            return current.getDeclaredMethod(name, *paramTypes)
        } catch (_: NoSuchMethodException) {
            // Fall through to look for erased signatures caused by generics
        }

        // Step 2: Fuzzy/Erasure Match
        // If the parent uses generics (e.g. Base<T>), its compiled signature uses
        // erased types (like Object), while this subclass uses concrete types (like String).
        val compatibleMethod = current.declaredMethods.find { baseMethod ->
            !baseMethod.isBridge && // Ignore compiler-generated bridge methods
                    baseMethod.name == name &&
                    baseMethod.parameterCount == paramTypes.size &&
                    baseMethod.parameterTypes.zip(paramTypes).all { (baseParam, subParam) ->
                        baseParam.isAssignableFrom(subParam)
                    }
        }

        if (compatibleMethod != null) {
            return compatibleMethod
        }

        // Queue up the next layer of the inheritance tree
        current.superclass?.let { queue.add(it) }
        queue.addAll(current.interfaces)
    }

    return null
}
