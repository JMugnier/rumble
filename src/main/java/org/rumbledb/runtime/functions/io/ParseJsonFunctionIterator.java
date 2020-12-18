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
 * Authors: Ghislain Fourny
 *
 */


package org.rumbledb.runtime.functions.io;

import java.io.StringReader;
import java.util.List;

import javax.json.Json;
import javax.json.stream.JsonParser;

import org.rumbledb.api.Item;
import org.rumbledb.context.DynamicContext;
import org.rumbledb.exceptions.ExceptionMetadata;
import org.rumbledb.exceptions.IteratorFlowException;
import org.rumbledb.expressions.ExecutionMode;
import org.rumbledb.items.parsing.ItemParser;
import org.rumbledb.runtime.RuntimeIterator;
import org.rumbledb.runtime.functions.base.LocalFunctionCallIterator;

public class ParseJsonFunctionIterator extends LocalFunctionCallIterator {

    private static final long serialVersionUID = 1L;
    private RuntimeIterator iterator;

    private transient Item string;

    public ParseJsonFunctionIterator(
            List<RuntimeIterator> arguments,
            ExecutionMode executionMode,
            ExceptionMetadata iteratorMetadata
    ) {
        super(arguments, executionMode, iteratorMetadata);
        this.iterator = this.children.get(0);
    }

    @Override
    public void open(DynamicContext context) {
        super.open(context);
        this.string = this.iterator.materializeFirstItemOrNull(context);
        this.hasNext = this.string != null;
    }

    @Override
    public void reset(DynamicContext context) {
        super.reset(context);
        this.string = this.iterator.materializeFirstItemOrNull(context);
        this.hasNext = this.string != null;
    }

    @Override
    public void close() {
        super.close();
        this.iterator = null;
    }

    @Override
    public Item next() {
        if (this.hasNext) {
            this.hasNext = false;
            try {
                JsonParser object = Json.createParser(new StringReader(this.string.getStringValue()));
                return ItemParser.getItemFromObject(object, getMetadata());
            } catch (IteratorFlowException e) {
                throw new IteratorFlowException(e.getJSONiqErrorMessage(), getMetadata());
            }
        }
        throw new IteratorFlowException(RuntimeIterator.FLOW_EXCEPTION_MESSAGE + " json-parse function", getMetadata());
    }


}
