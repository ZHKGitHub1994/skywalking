/*
 * Copyright 2017, OpenSkywalking Organization All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Project repository: https://github.com/OpenSkywalking/skywalking
 */

package org.skywalking.apm.agent.core.context.trace;

import org.skywalking.apm.agent.core.dictionary.DictionaryManager;
import org.skywalking.apm.agent.core.dictionary.DictionaryUtil;
import org.skywalking.apm.agent.core.dictionary.PossibleFound;

/**
 * 基于栈的链路追踪 Span 抽象类
 *
 * The <code>StackBasedTracingSpan</code> represents a span with an inside stack construction.
 *
 * This kind of span can start and finish multi times in a stack-like invoke line.
 *
 * @author wusheng
 */
public abstract class StackBasedTracingSpan extends AbstractTracingSpan {

    /**
     * 栈深度
     */
    protected int stackDepth;

    protected StackBasedTracingSpan(int spanId, int parentSpanId, String operationName) {
        super(spanId, parentSpanId, operationName);
        this.stackDepth = 0;
    }

    protected StackBasedTracingSpan(int spanId, int parentSpanId, int operationId) {
        super(spanId, parentSpanId, operationId);
        this.stackDepth = 0;
    }

    @Override
    public boolean finish(TraceSegment owner) {
        if (--stackDepth == 0) { // 为零，成功出栈
            // 获得 操作编号
            if (this.operationId == DictionaryUtil.nullValue()) {
                this.operationId = (Integer)DictionaryManager.findOperationNameCodeSection()
                    .findOrPrepare4Register(owner.getApplicationId(), operationName)
                    .doInCondition(
                        // 找到的处理逻辑，返回操作编号
                        new PossibleFound.FoundAndObtain() {
                            @Override public Object doProcess(int value) {
                                return value;
                            }
                        },
                        // 找不到的处理逻辑，返回空值
                        new PossibleFound.NotFoundAndObtain() {
                            @Override public Object doProcess() {
                                return DictionaryUtil.nullValue();
                            }
                        }
                    );
            }
            return super.finish(owner);
        } else {
            return false;
        }
    }

}
