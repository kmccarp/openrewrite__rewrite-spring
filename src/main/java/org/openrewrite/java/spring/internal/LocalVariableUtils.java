/*
 * Copyright 2023 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.spring.internal;

import lombok.Value;
import org.openrewrite.Cursor;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

// TODO Add in some form to the `rewrite-java` module
public class LocalVariableUtils {

    public static Expression resolveExpression(Expression expression, Cursor cursor) {
        JavaType.Variable fieldType = null;
        if (expression instanceof J.Identifier identifier) {
            fieldType = identifier.getFieldType();
        } else if (expression instanceof J.FieldAccess access) {
            fieldType = access.getName().getFieldType();
        }
        if (fieldType == null) {
            return expression;
        }
        JavaType owner = getRootOwner(fieldType);
        JavaType localRootType = getRootOwner(cursor);
        if (Objects.equals(owner, localRootType)) {
            Expression resolvedVariable = resolveVariable(fieldType.getName(), cursor);
            return resolvedVariable != null ? resolvedVariable : expression;
        } else {
            return expression;
        }
    }

    @Nullable
    private static JavaType getRootOwner(Cursor cursor) {
        Cursor parent = cursor.dropParentUntil(is -> is instanceof J.MethodDeclaration || is instanceof J.ClassDeclaration || is instanceof SourceFile);
        Object parentValue = parent.getValue();
        if (parentValue instanceof SourceFile) {
            return null;
        } else if (parentValue instanceof J.MethodDeclaration declaration) {
            return getRootOwner(declaration.getMethodType());
        } else {
            return getRootOwner(((J.ClassDeclaration) parentValue).getType());
        }
    }

    private static JavaType getRootOwner(JavaType type) {
        if (type instanceof JavaType.Variable variable) {
            return getRootOwner(variable.getOwner());
        } else if (type instanceof JavaType.Method method) {
            return getRootOwner(method.getDeclaringType());
        } else if (type instanceof JavaType.FullyQualified qualified) {
            JavaType.FullyQualified owner = qualified.getOwningClass();
            return owner != null ? getRootOwner(owner) : type;
        } else {
            return type;
        }
    }

    /**
     * Resolves a variable reference (by name) to the initializer expression of the corresponding declaration, provided that the
     * variable is declared as `final`. In all other cases `null` will be returned.
     */
    @Nullable
    private static Expression resolveVariable(String name, Cursor cursor) {
        return resolveVariable0(name, cursor.getValue(), cursor.getParentTreeCursor());
    }

    @Nullable
    private static Expression resolveVariable0(String name, J prior, Cursor cursor) {
        Optional<VariableMatch> found = Optional.empty();
        J value = cursor.getValue();
        if (value instanceof SourceFile) {
            return null;
        } else if (value instanceof J.MethodDeclaration declaration) {
            found = findVariable(declaration.getParameters(), name);
        } else if (value instanceof J.Block block) {
            List<Statement> statements = block.getStatements();
            boolean checkAllStatements = cursor.getParentTreeCursor().getValue() instanceof J.ClassDeclaration;
            if (!checkAllStatements) {
                @SuppressWarnings("SuspiciousMethodCalls") int index = statements.indexOf(prior);
                statements = index != -1 ? statements.subList(0, index) : statements;
            }
            found = findVariable(statements, name);
        } else if (value instanceof J.ForLoop loop) {
            found = findVariable(loop.getControl().getInit(), name);
        } else if (value instanceof J.Try try1 && try1.getResources() != null) {
            found = findVariable(try1.getResources().stream().map(J.Try.Resource::getVariableDeclarations).collect(Collectors.toList()), name);
        } else if (value instanceof J.Lambda lambda) {
            found = findVariable(lambda.getParameters().getParameters(), name);
        } else if (value instanceof J.VariableDeclarations declarations) {
            found = findVariable(Collections.singletonList(declarations), name);
        }
        return found.map(f -> f.isFinal ? f.variable.getInitializer() : null).orElseGet(() -> resolveVariable0(name, value, cursor.getParentTreeCursor()));
    }

    private static Optional<VariableMatch> findVariable(List<? extends J> list, String name) {
        for (J j : list) {
            if (j instanceof J.VariableDeclarations declaration) {
                for (J.VariableDeclarations.NamedVariable variable : declaration.getVariables()) {
                    if (variable.getSimpleName().equals(name)) {
                        return Optional.of(new VariableMatch(variable, declaration.hasModifier(J.Modifier.Type.Final)));
                    }
                }
            }
        }
        return Optional.empty();
    }

    @Value
    private static class VariableMatch {
        J.VariableDeclarations.NamedVariable variable;
        boolean isFinal;
    }
}
