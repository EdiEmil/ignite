/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ignite.internal.processors.query.calcite.sql;

import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.jetbrains.annotations.NotNull;

/** An AST node representing option to create table with. */
public class IgniteSqlCreateTableOption extends IgniteSqlOption<IgniteSqlCreateTableOptionEnum> {
    /** */
    private static final SqlOperator OPERATOR =
        new SqlSpecialOperator("TableOption", SqlKind.OTHER);

    /** Creates IgniteSqlCreateTableOption. */
    public IgniteSqlCreateTableOption(SqlLiteral key, SqlNode value, SqlParserPos pos) {
        super(key, value, pos, IgniteSqlCreateTableOptionEnum.class);
    }

    /** {@inheritDoc} */
    @NotNull @Override public SqlOperator getOperator() {
        return OPERATOR;
    }

    /** */
    public static SqlNodeList parseOptionList(String opts, SqlParserPos pos) {
        return IgniteSqlOption.parseOptionList(opts, pos, IgniteSqlCreateTableOption::new,
            IgniteSqlCreateTableOptionEnum.class);
    }
}
