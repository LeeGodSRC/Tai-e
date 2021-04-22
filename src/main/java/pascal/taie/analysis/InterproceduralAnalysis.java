/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2020-- Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020-- Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * Tai-e is only for educational and academic purposes,
 * and any form of commercial use is disallowed.
 * Distribution of Tai-e is disallowed without the approval.
 */

package pascal.taie.analysis;

import pascal.taie.config.AnalysisConfig;

public abstract class InterproceduralAnalysis extends Analysis {

    protected InterproceduralAnalysis(AnalysisConfig config) {
        super(config);
    }

    /**
     * Run this analysis for the whole program.
     * @return the analysis result. The result will be stored in World.
     * If the result is not used by following analyses, then this method
     * should return null.
     */
    public abstract Object analyze();
}
