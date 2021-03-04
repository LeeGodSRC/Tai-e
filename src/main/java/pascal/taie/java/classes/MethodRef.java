/*
 * Tai-e: A Program Analysis Framework for Java
 *
 * Copyright (C) 2020 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020 Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * This software is designed for the "Static Program Analysis" course at
 * Nanjing University, and it supports a subset of Java features.
 * Tai-e is only for educational and academic purposes, and any form of
 * commercial use is disallowed.
 */

package pascal.taie.java.classes;

import pascal.taie.java.World;
import pascal.taie.java.types.Type;
import pascal.taie.util.HashUtils;
import pascal.taie.util.InternalCanonicalized;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@InternalCanonicalized
public class MethodRef extends MemberRef {

    private static final ConcurrentMap<Key, MethodRef> map =
            new ConcurrentHashMap<>(4096);

    // Class and method names of polymorphic signature methods.
    private static final String METHOD_HANDLE = "java.lang.invoke.MethodHandle";

    private static final Set<String> METHOD_HANDLE_METHODS = new HashSet<>(Arrays.asList(
            "invokeExact",
            "invoke",
            "invokeBasic",
            "linkToVirtual",
            "linkToStatic",
            "linkToSpecial",
            "linkToInterface"
    ));

    private static final String VAR_HANDLE = "java.lang.invoke.VarHandle";

    private static final Set<String> VAR_HANDLE_METHODS = new HashSet<>(Arrays.asList(
            "get",
            "set",
            "getVolatile",
            "setVolatile",
            "getOpaque",
            "setOpaque",
            "getAcquire",
            "setRelease",
            "compareAndSet",
            "compareAndExchange",
            "compareAndExchangeAcquire",
            "compareAndExchangeRelease",
            "weakCompareAndSetPlain",
            "weakCompareAndSet",
            "weakCompareAndSetAcquire",
            "weakCompareAndSetRelease",
            "getAndSet",
            "getAndSetAcquire",
            "getAndSetRelease",
            "getAndAdd",
            "getAndAddAcquire",
            "getAndAddRelease",
            "getAndBitwiseOr",
            "getAndBitwiseOrAcquire",
            "getAndBitwiseOrRelease",
            "getAndBitwiseAnd",
            "getAndBitwiseAndAcquire",
            "getAndBitwiseAndRelease",
            "getAndBitwiseXor",
            "getAndBitwiseXorAcquire",
            "getAndBitwiseXorRelease"
    ));

    private final List<Type> parameterTypes;

    private final Type returnType;

    private final Subsignature subsignature;

    /**
     * Cache the resolved method for this reference to avoid redundant
     * method resolution.
     */
    private JMethod method;

    public static MethodRef get(
            JClass declaringClass, String name,
            List<Type> parameterTypes, Type returnType) {
        Subsignature subsignature = Subsignature.get(
                name, parameterTypes, returnType);
        Key key = new Key(declaringClass, subsignature);
        return map.computeIfAbsent(key, k ->
                new MethodRef(k, name, parameterTypes, returnType));
    }

    public static void reset() {
        map.clear();
    }

    private MethodRef(
            Key key, String name, List<Type> parameterTypes, Type returnType) {
        super(key.declaringClass, name);
        this.parameterTypes = parameterTypes;
        this.returnType = returnType;
        this.subsignature = key.subsignature;
    }

    public List<Type> getParameterTypes() {
        return parameterTypes;
    }

    public Type getReturnType() {
        return returnType;
    }

    public Subsignature getSubsignature() {
        return subsignature;
    }

    /**
     * @return if this is a reference to polymorphic signature method.
     * See JLS (Java 13 Ed.), 15.12.3 for the definition of polymorphic signature method.
     */
    public boolean isPolymorphicSignature() {
        if (METHOD_HANDLE.equals(getDeclaringClass().getName())) {
            return METHOD_HANDLE_METHODS.contains(getName());
        }
        if (VAR_HANDLE.equals(getDeclaringClass().getName())) {
            return VAR_HANDLE_METHODS.contains(getName());
        }
        return false;
    }

    public JMethod resolve() {
        if (method == null) {
            method = World.get()
                    .getClassHierarchy()
                    .resolveMethod(this);
        }
        return method;
    }

    @Override
    public String toString() {
        return StringReps.getSignatureOf(this);
    }

    private static class Key {

        private final JClass declaringClass;

        private final Subsignature subsignature;

        private Key(JClass declaringClass, Subsignature subsignature) {
            this.declaringClass = declaringClass;
            this.subsignature = subsignature;
        }

        @Override
        public int hashCode() {
            return HashUtils.hash(declaringClass, subsignature);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Key key = (Key) o;
            return declaringClass.equals(key.declaringClass) &&
                    subsignature.equals(key.subsignature);
        }
    }
}