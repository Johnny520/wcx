package h.Hchat.utils

import com.highcapable.kavaref.KavaRef.Companion.resolve
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Project-local wrapper around KavaRef.
 *
 * Keep direct Java reflection out of feature code where possible, while still
 * returning standard Method/Field/Constructor objects for Xposed and DexKit APIs.
 */
object KavaReflector {
    @JvmStatic
    fun loadClass(name: String?, classLoader: ClassLoader?): Class<*>? {
        if (name.isNullOrEmpty()) return null
        return runCatching {
            Class.forName(name, false, classLoader)
        }.getOrNull()
    }

    @JvmStatic
    fun declaredConstructors(clazz: Class<*>?): List<Constructor<*>> {
        if (clazz == null) return emptyList()
        return runCatching {
            clazz.resolve().optional(silent = true).constructor { }.map { it.self.accessible() }
        }.getOrDefault(emptyList())
    }

    @JvmStatic
    fun declaredMethods(clazz: Class<*>?): List<Method> {
        if (clazz == null) return emptyList()
        return runCatching {
            clazz.resolve().optional(silent = true).method { }.map { it.self.accessible() }
        }.getOrDefault(emptyList())
    }

    @JvmStatic
    fun declaredFields(clazz: Class<*>?): List<Field> {
        if (clazz == null) return emptyList()
        return runCatching {
            clazz.resolve().optional(silent = true).field { }.map { it.self.accessible() }
        }.getOrDefault(emptyList())
    }

    @JvmStatic
    fun findConstructor(clazz: Class<*>?, vararg parameterTypes: Class<*>): Constructor<*>? {
        if (clazz == null) return null
        return runCatching {
            clazz.resolve().optional(silent = true).firstConstructorOrNull {
                parameters(*parameterTypes)
            }?.self?.accessible()
        }.getOrNull()
    }

    @JvmStatic
    fun findDeclaredField(clazz: Class<*>?, name: String?): Field? {
        if (clazz == null || name.isNullOrEmpty()) return null
        return runCatching {
            clazz.resolve().optional(silent = true).firstFieldOrNull {
                this.name = name
            }?.self?.accessible()
        }.getOrNull()
    }

    @JvmStatic
    fun findDeclaredMethod(clazz: Class<*>?, name: String?, vararg parameterTypes: Class<*>): Method? {
        if (clazz == null || name.isNullOrEmpty()) return null
        return runCatching {
            clazz.resolve().optional(silent = true).firstMethodOrNull {
                this.name = name
                parameters(*parameterTypes)
            }?.self?.accessible()
        }.getOrNull()
    }

    @JvmStatic
    fun findMethod(clazz: Class<*>?, name: String?, vararg parameterTypes: Class<*>): Method? {
        if (clazz == null || name.isNullOrEmpty()) return null
        return findDeclaredMethod(clazz, name, *parameterTypes)
            ?: runCatching { clazz.getMethod(name, *parameterTypes).accessible() }.getOrNull()
    }

    @JvmStatic
    fun findMethodRecursive(clazz: Class<*>?, name: String?, vararg parameterTypes: Class<*>): Method? {
        if (clazz == null || name.isNullOrEmpty()) return null
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            findDeclaredMethod(current, name, *parameterTypes)?.let { return it }
            current = current.superclass
        }
        return findMethod(clazz, name, *parameterTypes)
    }

    @JvmStatic
    fun findCompatibleMethod(clazz: Class<*>?, name: String?, vararg args: Any?): Method? {
        if (clazz == null || name.isNullOrEmpty()) return null
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            declaredMethods(current).firstOrNull { method ->
                method.name == name && areAssignable(method.parameterTypes, args)
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    @JvmStatic
    fun findField(clazz: Class<*>?, name: String?): Field? {
        if (clazz == null || name.isNullOrEmpty()) return null
        return findDeclaredField(clazz, name)
            ?: runCatching { clazz.getField(name).accessible() }.getOrNull()
    }

    @JvmStatic
    fun findFieldRecursive(clazz: Class<*>?, name: String?): Field? {
        if (clazz == null || name.isNullOrEmpty()) return null
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            findDeclaredField(current, name)?.let { return it }
            current = current.superclass
        }
        return findField(clazz, name)
    }

    @JvmStatic
    fun accessible(method: Method?): Method? = method?.accessible()

    @JvmStatic
    fun accessible(field: Field?): Field? = field?.accessible()

    @JvmStatic
    fun accessible(constructor: Constructor<*>?): Constructor<*>? = constructor?.accessible()

    @JvmStatic
    fun isStatic(method: Method?): Boolean = method != null && Modifier.isStatic(method.modifiers)

    @JvmStatic
    fun isStatic(field: Field?): Boolean = field != null && Modifier.isStatic(field.modifiers)

    @JvmStatic
    fun isPublic(method: Method?): Boolean = method != null && Modifier.isPublic(method.modifiers)

    @JvmStatic
    fun isAbstract(method: Method?): Boolean = method != null && Modifier.isAbstract(method.modifiers)

    @JvmStatic
    fun isAbstract(modifiers: Int): Boolean = Modifier.isAbstract(modifiers)

    @JvmStatic
    fun isStatic(modifiers: Int): Boolean = Modifier.isStatic(modifiers)

    @JvmStatic
    fun modifiers(method: Method?): Int = method?.modifiers ?: 0

    @JvmStatic
    fun modifiers(field: Field?): Int = field?.modifiers ?: 0

    @JvmStatic
    fun newInstance(constructor: Constructor<*>?, vararg args: Any?): Any? {
        if (constructor == null) return null
        return runCatching {
            constructor.isAccessible = true
            constructor.newInstance(*args)
        }.getOrNull()
    }

    @JvmStatic
    fun newInstanceByArgs(clazz: Class<*>?, args: Array<Any?>): Any? {
        if (clazz == null) return null
        for (constructor in declaredConstructors(clazz)) {
            val types = constructor.parameterTypes ?: continue
            if (types.size != args.size) continue
            if (!types.indices.all { isAssignableForCtor(types[it], args[it]) }) continue
            newInstance(constructor, *args)?.let { return it }
        }
        return null
    }

    @JvmStatic
    fun readField(field: Field?, receiver: Any?): Any? {
        if (field == null) return null
        return runCatching {
            field.accessible()
            field.get(receiver)
        }.getOrNull()
    }

    @JvmStatic
    fun readField(receiver: Any?, fieldName: String?): Any? {
        if (receiver == null || fieldName.isNullOrEmpty()) return null
        var current: Class<*>? = receiver.javaClass
        while (current != null && current != Any::class.java) {
            val field = findDeclaredField(current, fieldName)
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(receiver)
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    @JvmStatic
    fun writeField(field: Field?, receiver: Any?, value: Any?): Boolean {
        if (field == null) return false
        return runCatching {
            field.accessible()
            field.set(receiver, value)
            true
        }.getOrDefault(false)
    }

    @JvmStatic
    fun writeField(receiver: Any?, fieldName: String?, value: Any?): Boolean {
        if (receiver == null || fieldName.isNullOrEmpty()) return false
        return writeField(findFieldRecursive(receiver.javaClass, fieldName), receiver, value)
    }

    @JvmStatic
    fun staticInstance(clazz: Class<*>?): Any? {
        if (clazz == null) return null
        return declaredFields(clazz).firstNotNullOfOrNull { field ->
            if (!isStatic(field) || !clazz.isAssignableFrom(field.type)) {
                null
            } else {
                readField(field, null as Any?)
            }
        }
    }

    @JvmStatic
    fun invoke(method: Method?, receiver: Any?, vararg args: Any?): Any? {
        if (method == null) return null
        return runCatching {
            method.accessible()
            method.invoke(receiver, *args)
        }.getOrNull()
    }

    @JvmStatic
    @Throws(Exception::class)
    fun invokeOrThrow(method: Method?, receiver: Any?, vararg args: Any?): Any? {
        if (method == null) throw NoSuchMethodException("method is null")
        method.accessible()
        return method.invoke(receiver, *args)
    }

    @JvmStatic
    fun invokeMethod(receiver: Any?, name: String?, vararg args: Any?): Any? {
        if (receiver == null) return null
        return invoke(findCompatibleMethod(receiver.javaClass, name, *args), receiver, *args)
    }

    @JvmStatic
    fun invokeStaticMethod(clazz: Class<*>?, name: String?, vararg args: Any?): Any? {
        return invoke(findCompatibleMethod(clazz, name, *args), null, *args)
    }

    @JvmStatic
    fun invokeSuccessfully(method: Method?, receiver: Any?, vararg args: Any?): Boolean {
        if (method == null) return false
        return runCatching {
            method.accessible()
            method.invoke(receiver, *args)
            true
        }.getOrDefault(false)
    }

    private fun isAssignableForCtor(parameterType: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !parameterType.isPrimitive
        return boxType(parameterType).isAssignableFrom(arg.javaClass)
    }

    private fun areAssignable(parameterTypes: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (parameterTypes.size != args.size) return false
        return parameterTypes.indices.all { isAssignableForCtor(parameterTypes[it], args[it]) }
    }

    private fun boxType(clazz: Class<*>): Class<*> {
        if (!clazz.isPrimitive) return clazz
        return when (clazz) {
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            else -> clazz
        }
    }

    private fun <T : java.lang.reflect.AccessibleObject> T.accessible(): T {
        isAccessible = true
        return this
    }
}
