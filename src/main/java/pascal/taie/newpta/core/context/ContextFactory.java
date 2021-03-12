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

package pascal.taie.newpta.core.context;

public interface ContextFactory<T> {

    /**
     * @return the default context.
     * TODO: call this root context?
     */
    Context getDefaultContext();

    /**
     * @return the context with one element.
     */
    Context get(T elem);

    /**
     * Construct a context by appending an context element to a parent context.
     * The length of the resulting context will be restricted by given limit.
     * @return the resulting context.
     */
    Context append(Context parent, T elem, int limit);
}
