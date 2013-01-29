package com.jetbrains.python.psi.types;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.codeInsight.stdlib.PyStdlibTypeProvider;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyBuiltinCache;
import com.jetbrains.python.psi.resolve.PyResolveContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author vlan
 */
public class PyTypeChecker {
  private PyTypeChecker() {
  }

  public static boolean match(@Nullable PyType expected, @Nullable PyType actual, @NotNull TypeEvalContext context) {
    return match(expected, actual, context, null, true);
  }

  /**
   * Checks whether a type *actual* can be placed where *expected* is expected.
   * For example int matches object, while str doesn't match int.
   * Work for builtin types, classes, tuples etc.
   *
   * @param expected expected type
   * @param actual type to be matched against expected
   * @param context
   * @param substitutions
   * @return
   */
  public static boolean match(@Nullable PyType expected, @Nullable PyType actual, @NotNull TypeEvalContext context,
                              @Nullable Map<PyGenericType, PyType> substitutions) {
    return match(expected, actual, context, substitutions, true);
  }

  private static boolean match(@Nullable PyType expected, @Nullable PyType actual, @NotNull TypeEvalContext context,
                               @Nullable Map<PyGenericType, PyType> substitutions, boolean recursive) {
    // TODO: subscriptable types?, module types?, etc.
    if (expected == null || actual == null) {
      return true;
    }
    if (isObjectType(expected) || isObjectType(actual)) {
      return true;
    }
    if ((expected instanceof PyTypeReference || actual instanceof PyTypeReference) && !recursive) {
      return true;
    }
    if (expected instanceof PyTypeReference) {
      return match(((PyTypeReference)expected).resolve(null, context), actual, context, substitutions, recursive);
    }
    if (actual instanceof PyTypeReference) {
      return match(expected, ((PyTypeReference)actual).resolve(null, context), context, substitutions, false);
    }
    if (expected instanceof PyGenericType && substitutions != null) {
      final PyGenericType generic = (PyGenericType)expected;
      final PyType subst = substitutions.get(generic);
      if (subst != null) {
        if (expected.equals(actual)) {
          return true;
        }
        else if (recursive) {
          return match(subst, actual, context, substitutions, false);
        }
        else {
          return false;
        }
      }
      else {
        substitutions.put(generic, actual);
        return true;
      }
    }
    if (isUnknown(actual)) {
      return true;
    }
    if (actual instanceof PyUnionType) {
      for (PyType m : ((PyUnionType)actual).getMembers()) {
        if (!match(expected, m, context, substitutions, recursive)) {
          return false;
        }
      }
      return true;
    }
    if (expected instanceof PyUnionType) {
      for (PyType t : ((PyUnionType)expected).getMembers()) {
        if (match(t, actual, context, substitutions, recursive)) {
          return true;
        }
      }
      return false;
    }
    if (expected instanceof PyClassType && actual instanceof PyClassType) {
      final PyClass superClass = ((PyClassType)expected).getPyClass();
      final PyClass subClass = ((PyClassType)actual).getPyClass();
      if (expected instanceof PyCollectionType && actual instanceof PyCollectionType) {
        if (!matchClasses(superClass, subClass)) {
          return false;
        }
        final PyType superElementType = ((PyCollectionType)expected).getElementType(context);
        final PyType subElementType = ((PyCollectionType)actual).getElementType(context);
        return match(superElementType, subElementType, context, substitutions, recursive);
      }
      else if (expected instanceof PyTupleType && actual instanceof PyTupleType) {
        final PyTupleType superTupleType = (PyTupleType)expected;
        final PyTupleType subTupleType = (PyTupleType)actual;
        if (superTupleType.getElementCount() != subTupleType.getElementCount()) {
          return false;
        }
        else {
          for (int i = 0; i < superTupleType.getElementCount(); i++) {
            if (!match(superTupleType.getElementType(i), subTupleType.getElementType(i), context, substitutions, recursive)) {
              return false;
            }
          }
          return true;
        }
      }
      else if (matchClasses(superClass, subClass)) {
        return true;
      }
      else if (((PyClassType)actual).isDefinition() && PyNames.CALLABLE.equals(expected.getName())) {
        return true;
      }
      if (expected.equals(actual)) {
        return true;
      }
    }
    if (actual instanceof PyFunctionType && expected instanceof PyClassType) {
      final PyClass superClass = ((PyClassType)expected).getPyClass();
      if (superClass != null && PyNames.CALLABLE.equals(superClass.getName())) {
        return true;
      }
    }
    final String superName = expected.getName();
    final String subName = actual.getName();
    // TODO: No inheritance check for builtin numerics at this moment
    final boolean subIsBool = "bool".equals(subName);
    final boolean subIsInt = "int".equals(subName);
    final boolean subIsLong = "long".equals(subName);
    final boolean subIsFloat = "float".equals(subName);
    if (superName == null || subName == null ||
        superName.equals(subName) ||
        ("int".equals(superName) && subIsBool) ||
        ("long".equals(superName) && (subIsBool || subIsInt)) ||
        ("float".equals(superName) && (subIsBool || subIsInt || subIsLong)) ||
        ("complex".equals(superName) && (subIsBool || subIsInt || subIsLong || subIsFloat))) {
      return true;
    }
    return false;
  }

  private static boolean isObjectType(@NotNull PyType type) {
    if (type instanceof PyClassType) {
      final PyClass c = ((PyClassType)type).getPyClass();
      if ("object".equals(c.getName())) {
        return true;
      }
    }
    return false;
  }

  public static boolean isUnknown(@Nullable PyType type) {
    if (type == null || type instanceof PyTypeReference || type instanceof PyGenericType) {
      return true;
    }
    if (type instanceof PyUnionType) {
      final PyUnionType union = (PyUnionType)type;
      for (PyType t : union.getMembers()) {
        if (isUnknown(t)) {
          return true;
        }
      }
    }
    return false;
  }

  public static boolean hasGenerics(@Nullable PyType type, @NotNull TypeEvalContext context) {
    final Set<PyGenericType> collected = new HashSet<PyGenericType>();
    collectGenerics(type, context, collected, new HashSet<PyType>());
    return !collected.isEmpty();
  }

  private static void collectGenerics(@Nullable PyType type, @NotNull TypeEvalContext context, @NotNull Set<PyGenericType> collected,
                                      @NotNull Set<PyType> visited) {
    if (visited.contains(type)) {
      return;
    }
    visited.add(type);
    if (type instanceof PyGenericType) {
      collected.add((PyGenericType)type);
    }
    else if (type instanceof PyUnionType) {
      final PyUnionType union = (PyUnionType)type;
      for (PyType t : union.getMembers()) {
        collectGenerics(t, context, collected, visited);
      }
    }
    else if (type instanceof PyCollectionType) {
      final PyCollectionType collection = (PyCollectionType)type;
      collectGenerics(collection.getElementType(context), context, collected, visited);
    }
    else if (type instanceof PyTupleType) {
      final PyTupleType tuple = (PyTupleType)type;
      final int n = tuple.getElementCount();
      for (int i = 0; i < n; i++) {
        collectGenerics(tuple.getElementType(i), context, collected, visited);
      }
    }
  }

  @Nullable
  public static PyType substitute(@Nullable PyType type, @NotNull Map<PyGenericType, PyType> substitutions,
                                  @NotNull TypeEvalContext context) {
    if (hasGenerics(type, context)) {
      if (type instanceof PyGenericType) {
        return substitutions.get((PyGenericType)type);
      }
      else if (type instanceof PyUnionType) {
        final PyUnionType union = (PyUnionType)type;
        final List<PyType> results = new ArrayList<PyType>();
        for (PyType t : union.getMembers()) {
          final PyType subst = substitute(t, substitutions, context);
          results.add(subst);
        }
        return PyUnionType.union(results);
      }
      else if (type instanceof PyCollectionTypeImpl) {
        final PyCollectionTypeImpl collection = (PyCollectionTypeImpl)type;
        final PyType elem = collection.getElementType(context);
        final PyType subst = substitute(elem, substitutions, context);
        return new PyCollectionTypeImpl(collection.getPyClass(), collection.isDefinition(), subst);
      }
      else if (type instanceof PyTupleType) {
        final PyTupleType tuple = (PyTupleType)type;
        final int n = tuple.getElementCount();
        final List<PyType> results = new ArrayList<PyType>();
        for (int i = 0; i < n; i++) {
          final PyType subst = substitute(tuple.getElementType(i), substitutions, context);
          results.add(subst);
        }
        return new PyTupleType((PyTupleType)type, results.toArray(new PyType[results.size()]));
      }
    }
    return type;
  }

  @Nullable
  public static Map<PyGenericType, PyType> unifyGenericCall(@NotNull PyFunction function,
                                                            @Nullable PyExpression receiver,
                                                            @NotNull Map<PyExpression, PyNamedParameter> arguments,
                                                            @NotNull TypeEvalContext context) {
    final Map<PyGenericType, PyType> substitutions = collectCallGenerics(function, receiver, context);
    for (Map.Entry<PyExpression, PyNamedParameter> entry : arguments.entrySet()) {
      final PyNamedParameter p = entry.getValue();
      final PyType argType = entry.getKey().getType(context);
      final PyType paramType = p.getType(context);
      if (!match(paramType, argType, context, substitutions)) {
        return null;
      }
    }
    return substitutions;
  }

  @NotNull
  public static Map<PyGenericType, PyType> collectCallGenerics(@NotNull Callable callable, @Nullable PyExpression receiver,
                                                               @NotNull TypeEvalContext context) {
    final Map<PyGenericType, PyType> substitutions = new LinkedHashMap<PyGenericType, PyType>();
    // Collect generic params of object type
    final Set<PyGenericType> generics = new LinkedHashSet<PyGenericType>();
    final PyType qualifierType = receiver != null ? receiver.getType(context) : null;
    collectGenerics(qualifierType, context, generics, new HashSet<PyType>());
    for (PyGenericType t : generics) {
      substitutions.put(t, t);
    }
    final PyClass cls = (callable instanceof PyFunction) ? ((PyFunction)callable).getContainingClass() : null;
    if (cls != null) {
      final PyFunction init = cls.findInitOrNew(true);
      // Unify generics in constructor
      if (init != null) {
        final PyType initType = init.getReturnType(context, null);
        if (initType != null) {
          match(initType, qualifierType, context, substitutions);
        }
      }
      else {
        // Unify generics in stdlib pseudo-constructor
        final PyStdlibTypeProvider stdlib = PyStdlibTypeProvider.getInstance();
        if (stdlib != null) {
          final PyType initType = stdlib.getConstructorType(cls);
          if (initType != null) {
            match(initType, qualifierType, context, substitutions);
          }
        }
      }
    }
    return substitutions;
  }

  private static boolean matchClasses(@Nullable PyClass superClass, @Nullable PyClass subClass) {
    if (superClass == null || subClass == null || subClass.isSubclass(superClass) || PyABCUtil.isSubclass(subClass, superClass)) {
      return true;
    }
    else {
      final String superName = superClass.getName();
      return superName != null && superName.equals(subClass.getName());
    }
  }

  @Nullable
  public static AnalyzeCallResults analyzeCall(@NotNull PyCallExpression call, @NotNull TypeEvalContext context) {
    final PyArgumentList args = call.getArgumentList();
    if (args != null) {
      final CallArgumentsMapping mapping = args.analyzeCall(PyResolveContext.noImplicits().withTypeEvalContext(context));
      final Map<PyExpression, PyNamedParameter> arguments = mapping.getPlainMappedParams();
      final PyCallExpression.PyMarkedCallee markedCallee = mapping.getMarkedCallee();
      if (markedCallee != null) {
        final Callable callable = markedCallee.getCallable();
        if (callable instanceof PyFunction) {
          final PyExpression callee = call.getCallee();
          final PyExpression receiver = callee instanceof PyQualifiedExpression ? ((PyQualifiedExpression)callee).getQualifier() : null;
          return new AnalyzeCallResults(callable, receiver, arguments);
        }
      }
    }
    return null;
  }

  @Nullable
  public static AnalyzeCallResults analyzeCall(@NotNull PyBinaryExpression expr, @NotNull TypeEvalContext context) {
    final PsiPolyVariantReference ref = expr.getReference(PyResolveContext.noImplicits().withTypeEvalContext(context));
    final ResolveResult[] resolveResult = ref.multiResolve(false);
    AnalyzeCallResults firstResults = null;
    for (ResolveResult result : resolveResult) {
      final PsiElement resolved = result.getElement();
      if (resolved instanceof PyTypedElement) {
        final PyTypedElement typedElement = (PyTypedElement)resolved;
        final PyType type = typedElement.getType(context);
        if (!(type instanceof PyFunctionType)) {
          return null;
        }
        final Callable callable = ((PyFunctionType)type).getCallable();
        final boolean isRight = PyNames.isRightOperatorName(typedElement.getName());
        final PyExpression arg = isRight ? expr.getLeftExpression() : expr.getRightExpression();
        final PyExpression receiver = isRight ? expr.getRightExpression() : expr.getLeftExpression();
        final PyParameter[] parameters = callable.getParameterList().getParameters();
        if (parameters.length >= 2) {
          final PyNamedParameter param = parameters[1].getAsNamed();
          if (arg != null && param != null) {
            final Map<PyExpression, PyNamedParameter> arguments = new LinkedHashMap<PyExpression, PyNamedParameter>();
            arguments.put(arg, param);
            final AnalyzeCallResults results = new AnalyzeCallResults(callable, receiver, arguments);
            if (firstResults == null) {
              firstResults = results;
            }
            if (match(param.getType(context), arg.getType(context), context)) {
              return results;
            }
          }
        }
      }
    }
    if (firstResults != null) {
      return firstResults;
    }
    return null;
  }

  @Nullable
  public static AnalyzeCallResults analyzeCall(@NotNull PySubscriptionExpression expr, @NotNull TypeEvalContext context) {
    final PsiReference ref = expr.getReference(PyResolveContext.noImplicits().withTypeEvalContext(context));
    final PsiElement resolved = ref.resolve();
    if (resolved instanceof PyTypedElement) {
      final PyType type = ((PyTypedElement)resolved).getType(context);
      if (type instanceof PyFunctionType) {
        final Callable callable = ((PyFunctionType)type).getCallable();
        final PyParameter[] parameters = callable.getParameterList().getParameters();
        if (parameters.length == 2) {
          final PyNamedParameter param = parameters[1].getAsNamed();
          if (param != null) {
            final Map<PyExpression, PyNamedParameter> arguments = new LinkedHashMap<PyExpression, PyNamedParameter>();
            final PyExpression arg = expr.getIndexExpression();
            if (arg != null) {
              arguments.put(arg, param);
              return new AnalyzeCallResults(callable, expr.getOperand(), arguments);
            }
          }
        }
      }
    }
    return null;
  }

  @Nullable
  public static AnalyzeCallResults analyzeCallSite(@Nullable PyQualifiedExpression callSite, @NotNull TypeEvalContext context) {
    if (callSite == null) {
      return null;
    }
    PsiElement parent = callSite.getParent();
    while (parent instanceof PyParenthesizedExpression) {
      parent = ((PyParenthesizedExpression)parent).getContainedExpression();
    }
    if (parent instanceof PyCallExpression) {
      return analyzeCall((PyCallExpression)parent, context);
    }
    else if (callSite instanceof PyBinaryExpression) {
      return analyzeCall((PyBinaryExpression)callSite, context);
    }
    else if (callSite instanceof PySubscriptionExpression) {
      return analyzeCall((PySubscriptionExpression)callSite, context);
    }
    return null;
  }

  @Nullable
  public static Boolean isCallable(@Nullable PyType type) {
    if (type == null || type instanceof PyTypeReference) {
      return null;
    }
    else if (type instanceof PyClassType) {
      final PyClassType classType = (PyClassType)type;
      if (classType.isDefinition()) {
        return true;
      }
      if (isMethodType(classType)) {
        return true;
      }
      final PyClass cls = classType.getPyClass();
      if (PyABCUtil.isSubclass(cls, PyNames.CALLABLE)) {
        return true;
      }
    }
    else if (type instanceof PyUnionType) {
      Boolean result = true;
      for (PyType member : ((PyUnionType)type).getMembers()) {
        final Boolean callable = isCallable(member);
        if (callable == null) {
          return null;
        }
        else if (!callable) {
          result = false;
        }
      }
      return result;
    }
    else if (type instanceof PyCallableType) {
      return true;
    }
    return false;
  }

  private static boolean isMethodType(@NotNull PyClassType type) {
    final PyBuiltinCache builtinCache = PyBuiltinCache.getInstance(type.getPyClass());
    return type.equals(builtinCache.getClassMethodType()) || type.equals(builtinCache.getStaticMethodType());
  }

  public static class AnalyzeCallResults {
    @NotNull private final Callable myCallable;
    @Nullable private final PyExpression myReceiver;
    @NotNull private final Map<PyExpression, PyNamedParameter> myArguments;

    public AnalyzeCallResults(@NotNull Callable callable, @Nullable PyExpression receiver,
                              @NotNull Map<PyExpression, PyNamedParameter> arguments) {
      myCallable = callable;
      myReceiver = receiver;
      myArguments = arguments;
    }

    @NotNull
    public Callable getCallable() {
      return myCallable;
    }

    @Nullable
    public PyExpression getReceiver() {
      return myReceiver;
    }

    @NotNull
    public Map<PyExpression, PyNamedParameter> getArguments() {
      return myArguments;
    }
  }
}
