/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.sql;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.sql.impl.SqlContext;
import com.hazelcast.sql.impl.SqlPrepare;
import com.hazelcast.sql.impl.SqlTable;
import com.hazelcast.sql.pojos.Person;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.plan.Contexts;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.rules.ProjectFilterTransposeRule;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.schema.impl.LongSchemaVersion;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserImplFactory;
import org.apache.calcite.sql.util.ChainedSqlOperatorTable;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.SqlRexConvertletTable;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;

import java.util.Collections;
import java.util.Properties;

public final class HazelcastSql2 {

    private final SqlPrepare sqlPrepare = new SqlPrepare();

    private final HazelcastInstance instance;

    public HazelcastSql2(HazelcastInstance instance) {
        this.instance = instance;
    }

    public Enumerable<Object> execute2(String sql) throws Exception {
        // TODO: 1. Parse.
        JavaTypeFactory typeFactory = new JavaTypeFactoryImpl();

        CalciteSchema schema = CalciteSchema.createRootSchema(true);
        schema.add("persons", new SqlTable(typeFactory .createStructType(Person.class), instance.getMap("persons")));

        CalciteSchema rootSchema = schema.createSnapshot(new LongSchemaVersion(System.nanoTime()));

        SqlContext context = new SqlContext(typeFactory, schema);

        Properties properties = new Properties();

        properties.put(CalciteConnectionProperty.UNQUOTED_CASING.camelName(), Casing.UNCHANGED.toString());
        properties.put(CalciteConnectionProperty.QUOTED_CASING.camelName(), Casing.UNCHANGED.toString());
        properties.put(CalciteConnectionProperty.CASE_SENSITIVE.camelName(), Boolean.TRUE.toString());

        CalciteConnectionConfigImpl config = new CalciteConnectionConfigImpl(properties);

        CalciteCatalogReader catalogReader = new CalciteCatalogReader(
            rootSchema,
            Collections.emptyList(), // Default schema path.
            typeFactory,
            config
        );

        final VolcanoPlanner planner = new VolcanoPlanner(
            null, // Cosr factory
            Contexts.of(config)
        );

        // TODO: Add various rules (see CalcitePrepareImpl.createPlanner)

        SqlRexConvertletTable rexConvertletTable = StandardConvertletTable.INSTANCE;

        SqlParser.ConfigBuilder parserConfig = SqlParser.configBuilder();

        parserConfig.setUnquotedCasing(Casing.UNCHANGED);
        parserConfig.setQuotedCasing(Casing.UNCHANGED);
        parserConfig.setCaseSensitive(true);

        parserConfig.setQuotedCasing(config.quotedCasing());
        parserConfig.setUnquotedCasing(config.unquotedCasing());
        parserConfig.setQuoting(config.quoting());
        parserConfig.setConformance(config.conformance());
        parserConfig.setCaseSensitive(config.caseSensitive());

        SqlParserImplFactory parserFactory = config.parserFactory(SqlParserImplFactory.class, null);

        if (parserFactory != null)
            parserConfig.setParserFactory(parserFactory);

        SqlParser parser = SqlParser.create(sql, parserConfig.build());

        SqlNode node = parser.parseStmt();

        System.out.println(">>> Parsed: " + node.getClass().getSimpleName());

        // TODO: 2. Analyze.
        final SqlOperatorTable opTab0 = config.fun(SqlOperatorTable.class, SqlStdOperatorTable.instance());
        final SqlOperatorTable opTab = ChainedSqlOperatorTable.of(opTab0, catalogReader);

        SqlValidator sqlValidator = new HazelcastSqlValidator(
            opTab,
            catalogReader,
            typeFactory,
            config.conformance()
        );

        SqlNode validatedNode = sqlValidator.validate(node);

        // TODO: 3. Convert to Rel.
        final SqlToRelConverter.ConfigBuilder sqlToRelConfigBuilder =
            SqlToRelConverter.configBuilder()
                .withTrimUnusedFields(true)
                .withExpand(false)
                .withExplain(false)
                .withConvertTableAccess(false);

        RexBuilder rexBuilder = new RexBuilder(typeFactory);
        RelOptCluster cluster = RelOptCluster.create(planner, rexBuilder);

        SqlToRelConverter sqlToRelConverter = new SqlToRelConverter(
            null, // TODO: ViewExpander, see CalcitePrepareImpl which implements this interface
            sqlValidator,
            catalogReader,
            cluster,
            rexConvertletTable,
            sqlToRelConfigBuilder.build()
        );

        RelRoot root = sqlToRelConverter.convertQuery(validatedNode, false, true);

        System.out.println(">>> Converted REL: " + root);

        // TODO: 4. Optimize
        HepProgramBuilder hepBuilder = new HepProgramBuilder();

        hepBuilder.addRuleInstance(ProjectFilterTransposeRule.INSTANCE);

        HepPlanner hepPlanner = new HepPlanner(
            hepBuilder.build()
        );

        hepPlanner.setRoot(root.rel);

        RelNode optimizedRelNode = hepPlanner.findBestExp();

        System.out.println(">>> Optimized REL: " + optimizedRelNode);

        // TODO: 5. Convert to physical

        // TODO: 6. Convert to fragments

        // TODO: 7. Execute
        return null;
    }

}