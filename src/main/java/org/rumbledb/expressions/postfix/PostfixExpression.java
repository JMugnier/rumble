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

package org.rumbledb.expressions.postfix;

import org.rumbledb.exceptions.ExceptionMetadata;
import org.rumbledb.exceptions.OurBadException;
import org.rumbledb.expressions.Expression;


public abstract class PostfixExpression extends Expression {

    protected Expression mainExpression;

    public PostfixExpression(Expression mainExpression, ExceptionMetadata metadata) {
        super(metadata);
        this.mainExpression = mainExpression;
        if (this.mainExpression == null) {
            throw new OurBadException("Main expression cannot be null in a postfix expression.");
        }
    }

    public Expression getMainExpression() {
        return this.mainExpression;
    }

    @Override
    public void initHighestExecutionMode() {
        this.highestExecutionMode = this.mainExpression.getHighestExecutionMode();
    }
}
