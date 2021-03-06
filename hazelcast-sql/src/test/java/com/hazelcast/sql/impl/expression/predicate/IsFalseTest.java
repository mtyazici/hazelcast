/*
 * Copyright (c) 2008-2020, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.sql.impl.expression.predicate;

import com.hazelcast.sql.SqlService;
import com.hazelcast.sql.impl.SqlDataSerializerHook;
import com.hazelcast.sql.impl.expression.ColumnExpression;
import com.hazelcast.sql.impl.expression.ExpressionTestBase;
import com.hazelcast.sql.impl.expression.SimpleExpressionEvalContext;
import com.hazelcast.sql.impl.type.QueryDataType;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.ParallelJVMTest;
import com.hazelcast.test.annotation.QuickTest;
import org.apache.calcite.rel.type.RelDataType;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import static com.hazelcast.sql.impl.calcite.validate.HazelcastSqlOperatorTable.IS_FALSE;
import static org.apache.calcite.sql.type.SqlTypeName.BOOLEAN;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelJVMTest.class})
public class IsFalseTest extends ExpressionTestBase {

    @Test
    public void testEndToEnd() {
        SqlService sql = createEndToEndRecords();
        assertRows(query(sql, "select __key from records where boolean1 is false"), keyRange(0, 500));
        assertRows(query(sql, "select __key from records where boolean1 is false and __key < 100"), keyRange(0, 100));
        assertRows(query(sql, "select __key, boolean1 is false from records"), keyRange(0, 1000, 5000, 6000),
                k -> k >= 0 && k < 500);
        assertQueryThrows(sql, "select __key, string1 is false from records", "Cannot convert VARCHAR to BOOLEAN");
    }

    @Test
    public void verify() {
        verify(IS_FALSE, IsFalseTest::expectedTypes, IsFalseTest::expectedValues, "%s IS FALSE", ALL);
    }

    @Test
    public void testCreationAndEval() {
        IsFalsePredicate predicate = IsFalsePredicate.create(ColumnExpression.create(0, QueryDataType.BOOLEAN));

        assertTrue(predicate.eval(row(false), SimpleExpressionEvalContext.create()));
        assertFalse(predicate.eval(row(true), SimpleExpressionEvalContext.create()));
        assertFalse(predicate.eval(row(new Object[]{null}), SimpleExpressionEvalContext.create()));
    }

    @Test
    public void testEquality() {
        ColumnExpression<?> column1 = ColumnExpression.create(1, QueryDataType.BOOLEAN);
        ColumnExpression<?> column2 = ColumnExpression.create(2, QueryDataType.BOOLEAN);

        checkEquals(IsFalsePredicate.create(column1), IsFalsePredicate.create(column1), true);
        checkEquals(IsFalsePredicate.create(column1), IsFalsePredicate.create(column2), false);
    }

    @Test
    public void testSerialization() {
        IsFalsePredicate original = IsFalsePredicate.create(ColumnExpression.create(1, QueryDataType.BOOLEAN));
        IsFalsePredicate restored = serializeAndCheck(original, SqlDataSerializerHook.EXPRESSION_IS_FALSE);

        checkEquals(original, restored, true);
    }

    private static RelDataType[] expectedTypes(Operand[] operands) {
        Operand operand = operands[0];
        RelDataType type = operand.type;

        if (operand.isParameter()) {
            type = TYPE_FACTORY.createSqlType(BOOLEAN, true);
        } else {
            assert operand.type != UNKNOWN_TYPE;

            switch (operand.typeName()) {
                case NULL:
                    type = TYPE_FACTORY.createSqlType(BOOLEAN, true);
                    break;

                case BOOLEAN:
                    // do nothing
                    break;

                case VARCHAR:
                    if (operand.isLiteral()) {
                        Boolean booleanValue = operand.booleanValue();
                        if (booleanValue == INVALID_BOOLEAN_VALUE) {
                            return null;
                        }
                    }

                    type = TYPE_FACTORY.createSqlType(BOOLEAN, operand.type.isNullable());
                    break;

                default:
                    return null;
            }
        }

        return new RelDataType[]{type, TYPE_FACTORY.createSqlType(BOOLEAN)};
    }

    private static Object expectedValues(Operand[] operands, RelDataType[] types, Object[] args) {
        Object arg = args[0];

        if (arg == INVALID_VALUE) {
            return INVALID_VALUE;
        }

        return TernaryLogic.isFalse((Boolean) arg);
    }

}
