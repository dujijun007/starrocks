// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package com.starrocks.sql.optimizer.rule.transformation;

import com.starrocks.sql.optimizer.CTEContext;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalCTEProduceOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.rule.NonDeterministicVisitor;
import com.starrocks.sql.optimizer.rule.RuleType;

import java.util.Collections;
import java.util.List;

/**
 * If the opt expression contains non-deterministic function, force cte reuse to avoid producing wrong result.
 */
public class ForceCTEReuseRule extends TransformationRule {
    public ForceCTEReuseRule() {
        super(RuleType.TF_FORCE_CTE_REUSE,
                Pattern.create(OperatorType.LOGICAL_CTE_PRODUCE, OperatorType.PATTERN_LEAF));
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        if (hasNonDeterministicFunction(input)) {
            LogicalCTEProduceOperator produce = (LogicalCTEProduceOperator) input.getOp();
            CTEContext cteContext = context.getCteContext();
            int cteId = produce.getCteId();
            cteContext.addForceCTE(cteId);
        }

        return Collections.emptyList();
    }

    private boolean hasNonDeterministicFunction(OptExpression root) {
        return root.getOp().accept(new NonDeterministicVisitor(), root, null);
    }
}
