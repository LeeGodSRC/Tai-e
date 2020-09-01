/*
 * Bamboo - A Program Analysis Framework for Java
 *
 * Copyright (C)  2020 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C)  2020 Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * This software is designed for the "Static Program Analysis" course at
 * Nanjing University, and it supports a subset of Java features.
 * Bamboo is only for educational and academic purposes, and any form of
 * commercial use is disallowed.
 */

package panda.pta.core.context;

import panda.pta.core.cs.CSCallSite;
import panda.pta.core.cs.CSMethod;
import panda.pta.core.cs.CSObj;
import panda.pta.element.Method;
import panda.pta.element.Obj;

/**
 * 1-type-sensitivity with no heap context.
 */
public class OneTypeSelector extends AbstractContextSelector {

    @Override
    public Context selectContext(CSCallSite callSite, Method callee) {
        return callSite.getContext();
    }

    @Override
    public Context selectContext(CSCallSite callSite, CSObj recv, Method callee) {
        return new OneContext<>(recv.getObject().getContainerType());
    }

    @Override
    protected Context doSelectHeapContext(CSMethod method, Obj obj) {
        return getDefaultContext();
    }
}