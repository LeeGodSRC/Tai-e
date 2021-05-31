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

package pascal.taie.analysis.pta.plugin.invokedynamic;

import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.heap.MockObj;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.PointerFlowEdge;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.InvokeDynamic;
import pascal.taie.ir.exp.MethodHandle;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.StringReps;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.Type;
import pascal.taie.util.AnalysisException;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static pascal.taie.util.collection.MapUtils.addToMapMap;
import static pascal.taie.util.collection.MapUtils.addToMapSet;
import static pascal.taie.util.collection.MapUtils.getMapMap;
import static pascal.taie.util.collection.MapUtils.newMap;

public class LambdaPlugin implements Plugin {

    /**
     * Description for lambda functional objects.
     */
    private static final String LAMBDA_DESC = "LambdaObj";

    /**
     * Description for objects created by lambda constructor.
     */
    private static final String LAMBDA_NEW_DESC = "LambdaConstructedObj";

    private Solver solver;

    private ContextSelector selector;

    private ClassHierarchy hierarchy;

    private CSManager csManager;

    /**
     * Map from method to the lambda functional objects created in the method.
     */
    private final Map<JMethod, Set<MockObj>> lambdaObjs = newMap();

    /**
     * Map from Invoke (of invokedynamic) and type to mock obj to avoid mocking same objects
     */
    private final Map<Invoke, Map<ClassType, MockObj>> newObjs = newMap();

    /**
     * Map from receiver variable to the information about the related
     * instance invocation sites. When new objects reach the receiver variable,
     * these information will be used to build lambda call edges.
     */
    private final Map<CSVar, Set<InstanceInvoInfo>> invoInfos = newMap();

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
        this.selector = solver.getContextSelector();
        this.hierarchy = solver.getHierarchy();
        this.csManager = solver.getCSManager();
    }

    @Override
    public void onNewMethod(JMethod method) {
        extractLambdaMetaFactories(method.getIR()).forEach(invoke -> {
            InvokeDynamic indy = (InvokeDynamic) invoke.getInvokeExp();
            Type type = indy.getMethodType().getReturnType();
            JMethod container = invoke.getContainer();
            // record lambda meta factories of new discovered methods
            addToMapSet(lambdaObjs, container,
                    new MockObj(LAMBDA_DESC, invoke, type, container));
        });
    }

    private static Stream<Invoke> extractLambdaMetaFactories(IR ir) {
        return ir.getStmts()
                .stream()
                .filter(s -> s instanceof Invoke)
                .map(s -> (Invoke) s)
                .filter(LambdaPlugin::isLambdaMetaFactory);
    }

    static boolean isLambdaMetaFactory(Invoke invoke) {
        if (invoke.getInvokeExp() instanceof InvokeDynamic) {
            JMethod bsm = ((InvokeDynamic) invoke.getInvokeExp())
                    .getBootstrapMethodRef().resolve();
            String bsmSig = bsm.getSignature();
            return bsmSig.equals(StringReps.LAMBDA_METAFACTORY) ||
                    bsmSig.equals(StringReps.LAMBDA_ALTMETAFACTORY);
        }
        return false;
    }

    @Override
    public void onNewCSMethod(CSMethod csMethod) {
        JMethod method = csMethod.getMethod();
        Set<MockObj> lambdas = lambdaObjs.get(method);
        if (lambdas != null) {
            Context context = csMethod.getContext();
            lambdas.forEach(lambdaObj -> {
                // propagate lambda functional objects
                Invoke invoke = (Invoke) lambdaObj.getAllocation();
                Var ret = invoke.getResult();
                assert ret != null;
                // here we use full method context as the heap context of
                // lambda object, so that it can be directly used to obtain
                // captured values.
                solver.addVarPointsTo(context, ret, context, lambdaObj);
            });
        }
    }

    @Override
    public void onUnresolvedCall(CSObj recv, Context context, Invoke invoke) {
        if (!isLambdaObj(recv.getObject())) {
            return;
        }
        MockObj lambdaObj = (MockObj) recv.getObject();
        Invoke indyInvoke = (Invoke) lambdaObj.getAllocation();
        InvokeDynamic indy = (InvokeDynamic) indyInvoke.getInvokeExp();
        if (!indy.getMethodName().equals(invoke.getMethodRef().getName())) {
            // Use method name to filter out mismatched (caused by imprecision
            // of pointer analysis) lambda objects and actual invocation sites.
            // TODO: use more information to filter out mismatches?
            return;
        }
        Context indyCtx = recv.getContext();
        CSCallSite csCallSite = csManager.getCSCallSite(context, invoke);
        MethodHandle mh = getMethodHandle(indy);
        final JMethod target = getMethodHandle(indy).getMethodRef().resolve();

        switch (mh.getKind()) {
            case REF_newInvokeSpecial: { // target is constructor
                ClassType type = target.getDeclaringClass().getType();
                // Create mock object (if absent) which represents
                // the newly-allocated object. Note that here we use the
                // *invokedynamic* to represent the *allocation site*,
                // instead of the actual invocation site of the constructor.
                MockObj newObj = getMapMap(newObjs, indyInvoke, type);
                if (newObj == null) {
                    // TODO: use heapModel to process mock obj?
                    newObj = new MockObj(LAMBDA_NEW_DESC, indyInvoke, type,
                            indyInvoke.getContainer());
                    addToMapMap(newObjs, indyInvoke, type, newObj);
                }
                // Pass the mock object to result variable (if present)
                // TODO: double-check if the heap context is proper
                CSObj csNewObj = csManager.getCSObj(context, newObj);
                Var result = invoke.getResult();
                if (result != null) {
                    solver.addVarPointsTo(context, result, csNewObj);
                }
                // Add call edge to constructor
                addLambdaCallEdge(csCallSite, csNewObj, target, indy, indyCtx);
                break;
            }
            case REF_invokeInterface:
            case REF_invokeVirtual:
            case REF_invokeSpecial: { // target is instance method
                List<Var> capturedArgs = indy.getArgs();
                List<Var> actualArgs = invoke.getInvokeExp().getArgs();
                // Obtain receiver variable and context
                Var recvVar;
                Context recvCtx;
                if (!capturedArgs.isEmpty()) {
                    recvVar = capturedArgs.get(0);
                    recvCtx = indyCtx;
                } else {
                    recvVar = actualArgs.get(0);
                    recvCtx = context;
                }
                CSVar csRecvVar = csManager.getCSVar(recvCtx, recvVar);
                solver.getPointsToSetOf(csRecvVar).forEach(recvObj -> {
                    // Handle receiver objects
                    Type rectType = recvObj.getObject().getType();
                    JMethod callee = hierarchy.dispatch(rectType, target.getRef());
                    if (callee != null) {
                        addLambdaCallEdge(csCallSite, recvObj, callee, indy, indyCtx);
                    }
                });
                // New objects may reach csRecvVar later, thus we store it
                // together with information about the related Lambda invocation.
                addToMapSet(invoInfos, csRecvVar,
                        new InstanceInvoInfo(csCallSite, indy, indyCtx));
                break;
            }
            case REF_invokeStatic: { // target is static method
                addLambdaCallEdge(csCallSite, null, target, indy, indyCtx);
                break;
            }
            default:
                throw new AnalysisException(mh.getKind() + " is not supported");
        }
    }

    private static boolean isLambdaObj(Obj obj) {
        return obj instanceof MockObj &&
                ((MockObj) obj).getDescription().equals(LAMBDA_DESC);
    }

    private static MethodHandle getMethodHandle(InvokeDynamic indy) {
        return (MethodHandle) indy.getBootstrapArgs().get(1);
    }

    private void addLambdaCallEdge(
            CSCallSite csCallSite, @Nullable CSObj recvObj, JMethod callee,
            InvokeDynamic indy, Context indyCtx) {
        Context calleeCtx;
        if (recvObj != null) {
            calleeCtx = selector.selectContext(csCallSite, recvObj, callee);
            // Pass receiver object to 'this' variable of callee
            solver.addVarPointsTo(calleeCtx, callee.getIR().getThis(), recvObj);
        } else {
            calleeCtx = selector.selectContext(csCallSite, callee);
        }
        LambdaCallEdge callEdge = new LambdaCallEdge(csCallSite,
                csManager.getCSMethod(calleeCtx, callee), indy, indyCtx);
        solver.addCallEdge(callEdge);
    }

    @Override
    public void onNewCallEdge(Edge<CSCallSite, CSMethod> edge) {
        if (edge instanceof LambdaCallEdge) {
            LambdaCallEdge lambdaCallEdge = (LambdaCallEdge) edge;
            CSCallSite csCallSite = lambdaCallEdge.getCallSite();
            CSMethod csCallee = lambdaCallEdge.getCallee();
            List<Var> capturedArgs = lambdaCallEdge.getCapturedArgs();
            Context calleeContext = csCallee.getContext();
            Context lambdaContext = lambdaCallEdge.getLambdaContext();

            JMethod target = csCallee.getMethod();
            // Pass arguments captured at invokedynamic
            int shiftC; // shift of captured arguments
            if (capturedArgs.isEmpty()) {
                shiftC = 0;
            } else if (target.isStatic() || target.isConstructor()) {
                shiftC = 0;
            } else {
                shiftC = 1;
            }
            List<Var> targetParams = target.getIR().getParams();
            int j = 0;
            for (int i = shiftC; i < capturedArgs.size(); ++i, ++j) {
                solver.addPFGEdge(
                        csManager.getCSVar(lambdaContext, capturedArgs.get(i)),
                        csManager.getCSVar(calleeContext, targetParams.get(j)),
                        PointerFlowEdge.Kind.PARAMETER_PASSING);
            }
            // Pass arguments from actual invocation site
            int shiftA; // shift of actual arguments
            if (capturedArgs.isEmpty() &&
                    !target.isStatic() && !target.isConstructor()) {
                shiftA = 1;
            } else {
                shiftA = 0;
            }
            Invoke invoke = csCallSite.getCallSite();
            List<Var> actualArgs = invoke.getInvokeExp().getArgs();
            Context callerContext = csCallSite.getContext();
            for (int i = shiftA; i < actualArgs.size(); ++i, ++j) {
                solver.addPFGEdge(
                        csManager.getCSVar(callerContext, actualArgs.get(i)),
                        csManager.getCSVar(calleeContext, targetParams.get(j)),
                        PointerFlowEdge.Kind.PARAMETER_PASSING);
            }
            // Pass return value
            Var result = invoke.getResult();
            if (result != null && !target.isConstructor()) {
                CSVar csResult = csManager.getCSVar(callerContext, result);
                target.getIR().getReturnVars().forEach(ret -> {
                    CSVar csRet = csManager.getCSVar(calleeContext, ret);
                    solver.addPFGEdge(csRet, csResult, PointerFlowEdge.Kind.RETURN);
                });
            }
        }
    }

    @Override
    public void onNewPointsToSet(CSVar csVar, PointsToSet pts) {
        Set<InstanceInvoInfo> infos = invoInfos.get(csVar);
        if (infos == null) {
            return;
        }
        infos.forEach(info -> {
            InvokeDynamic indy = info.getLambdaIndy();
            MethodRef targetRef = getMethodHandle(indy).getMethodRef();
            pts.forEach(recvObj -> {
                Type recvType = recvObj.getObject().getType();
                JMethod callee = hierarchy.dispatch(recvType, targetRef);
                if (callee != null) {
                    addLambdaCallEdge(info.getCSCallSite(), recvObj,
                            callee, indy, info.getLambdaContext());
                }
            });
        });
    }
}
