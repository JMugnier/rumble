/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Authors: Stefan Irimescu, Can Berker Cikis
 *
 */

package org.rumbledb.compiler;

import java.util.ArrayList;
import java.util.List;

import org.rumbledb.context.BuiltinFunctionCatalogue;
import org.rumbledb.context.FunctionIdentifier;
import org.rumbledb.context.Name;
import org.rumbledb.context.StaticContext;
import org.rumbledb.expressions.AbstractNodeVisitor;
import org.rumbledb.expressions.ExecutionMode;
import org.rumbledb.expressions.Expression;
import org.rumbledb.expressions.Node;
import org.rumbledb.expressions.control.TypeSwitchExpression;
import org.rumbledb.expressions.control.TypeswitchCase;
import org.rumbledb.expressions.flowr.Clause;
import org.rumbledb.expressions.flowr.CountClause;
import org.rumbledb.expressions.flowr.FlworExpression;
import org.rumbledb.expressions.flowr.GroupByVariableDeclaration;
import org.rumbledb.expressions.flowr.ForClause;
import org.rumbledb.expressions.flowr.GroupByClause;
import org.rumbledb.expressions.flowr.LetClause;
import org.rumbledb.expressions.module.FunctionDeclaration;
import org.rumbledb.expressions.module.LibraryModule;
import org.rumbledb.expressions.module.MainModule;
import org.rumbledb.expressions.module.VariableDeclaration;
import org.rumbledb.expressions.primary.FunctionCallExpression;
import org.rumbledb.expressions.primary.InlineFunctionExpression;
import org.rumbledb.expressions.primary.VariableReferenceExpression;
import org.rumbledb.types.SequenceType;
import org.rumbledb.types.SequenceType.Arity;

/**
 * Static context visitor implements a multi-pass algorithm that enables function hoisting
 */
public class ExecutionModeVisitor extends AbstractNodeVisitor<StaticContext> {

    private VisitorConfig visitorConfig;

    ExecutionModeVisitor() {
        this.visitorConfig = VisitorConfig.staticContextVisitorInitialPassConfig;
    }

    void setVisitorConfig(VisitorConfig visitorConfig) {
        this.visitorConfig = visitorConfig;
    }

    @Override
    protected StaticContext defaultAction(Node node, StaticContext argument) {
        visitDescendants(node, argument);
        node.initHighestExecutionMode(this.visitorConfig);
        return argument;
    }

    @Override
    public StaticContext visitMainModule(MainModule mainModule, StaticContext argument) {
        visitDescendants(mainModule, argument);
        mainModule.initHighestExecutionMode(this.visitorConfig);
        return argument;
    }

    @Override
    public StaticContext visitLibraryModule(LibraryModule libraryModule, StaticContext argument) {
        if (libraryModule.getProlog() != null) {
            libraryModule.getProlog().initHighestExecutionMode(this.visitorConfig);
        }
        libraryModule.initHighestExecutionMode(this.visitorConfig);
        return argument;
    }

    // region primary
    @Override
    public StaticContext visitVariableReference(VariableReferenceExpression expression, StaticContext argument) {
        Name variableName = expression.getVariableName();
        ExecutionMode mode = argument.getVariableStorageMode(variableName);
        if (this.visitorConfig.setUnsetToLocal() && mode.equals(ExecutionMode.UNSET)) {
            mode = ExecutionMode.LOCAL;
        }
        expression.setHighestExecutionMode(mode);
        return argument;
    }

    private void populateFunctionDeclarationStaticContext(
            StaticContext functionDeclarationContext,
            List<ExecutionMode> modes,
            InlineFunctionExpression expression
    ) {
        int i = 0;
        for (Name name : expression.getParams().keySet()) {
            ExecutionMode mode = modes.get(i);
            SequenceType type = expression.getParams().get(name);
            if (type.isEmptySequence()) {
                mode = ExecutionMode.LOCAL;
            } else if (type.getArity().equals(Arity.OneOrZero) || type.getArity().equals(Arity.One)) {
                mode = ExecutionMode.LOCAL;
            }
            functionDeclarationContext.setVariableStorageMode(
                name,
                mode
            );
            ++i;
        }
    }

    @Override
    public StaticContext visitFunctionDeclaration(FunctionDeclaration declaration, StaticContext argument) {
        InlineFunctionExpression expression = (InlineFunctionExpression) declaration.getExpression();
        // define a static context for the function body, add params to the context and visit the body expression
        List<ExecutionMode> modes = argument.getUserDefinedFunctionsExecutionModes()
            .getParameterExecutionMode(
                expression.getFunctionIdentifier(),
                expression.getMetadata()
            );
        populateFunctionDeclarationStaticContext(expression.getStaticContext(), modes, expression);
        // visit the body first to make its execution mode available while adding the function to the catalog
        this.visit(expression.getBody(), expression.getStaticContext());
        expression.initHighestExecutionMode(this.visitorConfig);
        declaration.initHighestExecutionMode(this.visitorConfig);
        expression.registerUserDefinedFunctionExecutionMode(
            this.visitorConfig
        );
        return argument;
    }

    @Override
    public StaticContext visitInlineFunctionExpr(InlineFunctionExpression expression, StaticContext argument) {
        // define a static context for the function body, add params to the context and visit the body expression
         
        expression.getParams()
            .forEach(
                (paramName, sequenceType) -> expression.getStaticContext().setVariableStorageMode(
                    paramName,
                    ExecutionMode.LOCAL
                )
            );
        // visit the body first to make its execution mode available while adding the function to the catalog
        this.visit(expression.getBody(), expression.getStaticContext());
        expression.initHighestExecutionMode(this.visitorConfig);
        expression.registerUserDefinedFunctionExecutionMode(
            this.visitorConfig
        );
        return argument;
    }

    @Override
    public StaticContext visitFunctionCall(FunctionCallExpression expression, StaticContext argument) {
        visitDescendants(expression, argument);
        FunctionIdentifier identifier = expression.getFunctionIdentifier();
        if (!BuiltinFunctionCatalogue.exists(identifier)) {
            List<ExecutionMode> modes = new ArrayList<>();
            if (expression.isPartialApplication()) {
                for (@SuppressWarnings("unused")
                Expression parameter : expression.getArguments()) {
                    modes.add(ExecutionMode.LOCAL);
                }
            } else {
                for (Expression parameter : expression.getArguments()) {
                    modes.add(parameter.getHighestExecutionMode(this.visitorConfig));
                }
            }
            argument.getUserDefinedFunctionsExecutionModes()
                .setParameterExecutionMode(
                    identifier,
                    modes,
                    expression.getMetadata()
                );
        }
        expression.initFunctionCallHighestExecutionMode(this.visitorConfig);
        return argument;
    }
    // endregion

    // region FLWOR
    @Override
    public StaticContext visitFlowrExpression(FlworExpression expression, StaticContext argument) {
        Clause clause = expression.getReturnClause().getFirstClause();
        while (clause != null) {
            this.visit(clause, clause.getStaticContext());
            clause = clause.getNextClause();
        }
        expression.initHighestExecutionMode(this.visitorConfig);
        return argument;
    }

    // region FLWOR vars
    @Override
    public StaticContext visitForClause(ForClause clause, StaticContext argument) {
        this.visit(clause.getExpression(), argument);
        clause.initHighestExecutionMode(this.visitorConfig);

        StaticContext result = new StaticContext(argument);
        
        result.setVariableStorageMode(
            clause.getVariableName(),
            clause.getVariableHighestStorageMode(this.visitorConfig)
        );

        if (clause.getPositionalVariableName() != null) {
            result.setVariableStorageMode(
                clause.getPositionalVariableName(),
                ExecutionMode.LOCAL
            );
        }
        return result;
    }

    @Override
    public StaticContext visitLetClause(LetClause clause, StaticContext argument) {
        this.visit(clause.getExpression(), argument);
        clause.initHighestExecutionMode(this.visitorConfig);

        clause.getStaticContext().setVariableStorageMode(
            clause.getVariableName(),
            clause.getVariableHighestStorageMode(this.visitorConfig)
        );

        return argument;
    }

    @Override
    public StaticContext visitGroupByClause(GroupByClause clause, StaticContext argument) {
        StaticContext groupByClauseContext = new StaticContext(argument);
        for (GroupByVariableDeclaration variable : clause.getGroupVariables()) {
            // if a variable declaration takes place
            this.visit(variable.getExpression(), argument);
            groupByClauseContext.setVariableStorageMode(
                variable.getVariableName(),
                ExecutionMode.LOCAL
            );
        }
        clause.initHighestExecutionMode(this.visitorConfig);
        return groupByClauseContext;
    }

    @Override
    public StaticContext visitCountClause(CountClause expression, StaticContext argument) {
        expression.initHighestExecutionMode(this.visitorConfig);
        expression.getCountVariable().getStaticContext().setVariableStorageMode(
            expression.getCountVariable().getVariableName(),
            ExecutionMode.LOCAL
        );
        this.visit(expression.getCountVariable(), expression.getCountVariable().getStaticContext());
        return argument;
    }

    // endregion

    // region control
    @Override
    public StaticContext visitTypeSwitchExpression(TypeSwitchExpression expression, StaticContext argument) {
        this.visit(expression.getTestCondition(), argument);
        for (TypeswitchCase c : expression.getCases()) {
            Name variableName = c.getVariableName();
            if (variableName != null) {
                c.getReturnExpression().getStaticContext().setVariableStorageMode(
                    variableName,
                    ExecutionMode.LOCAL
                );
            }
            this.visit(c.getReturnExpression(), c.getReturnExpression().getStaticContext());
        }

        Name defaultCaseVariableName = expression.getDefaultCase().getVariableName();
        if (defaultCaseVariableName == null) {
            this.visit(expression.getDefaultCase().getReturnExpression(), argument);
        } else {
            // add variable to child context to visit default return expression
            StaticContext defaultCaseStaticContext = new StaticContext(argument);
            defaultCaseStaticContext.setVariableStorageMode(
                defaultCaseVariableName,
                ExecutionMode.LOCAL
            );
            this.visit(expression.getDefaultCase().getReturnExpression(), defaultCaseStaticContext);
        }
        expression.initHighestExecutionMode(this.visitorConfig);
        // return the given context unchanged as defined variables go out of scope
        return argument;
    }
    // endregion

    @Override
    public StaticContext visitVariableDeclaration(VariableDeclaration variableDeclaration, StaticContext argument) {
        if (variableDeclaration.getExpression() != null) {
            this.visit(variableDeclaration.getExpression(), argument);
        }
        variableDeclaration.initHighestExecutionMode(this.visitorConfig);
        // first pass.
        argument.setVariableStorageMode(
            variableDeclaration.getVariableName(),
            variableDeclaration.getVariableHighestStorageMode(this.visitorConfig)
        );
        return argument;
    }

}
