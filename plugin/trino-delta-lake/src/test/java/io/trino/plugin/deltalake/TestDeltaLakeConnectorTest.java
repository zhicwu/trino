/*
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
 */
package io.trino.plugin.deltalake;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import io.airlift.units.DataSize;
import io.trino.Session;
import io.trino.execution.QueryInfo;
import io.trino.plugin.hive.containers.HiveMinioDataLake;
import io.trino.testing.BaseConnectorTest;
import io.trino.testing.DistributedQueryRunner;
import io.trino.testing.MaterializedResult;
import io.trino.testing.MaterializedResultWithQueryId;
import io.trino.testing.MaterializedRow;
import io.trino.testing.QueryRunner;
import io.trino.testing.TestingConnectorBehavior;
import io.trino.testing.sql.TestTable;
import org.intellij.lang.annotations.Language;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.union;
import static io.trino.plugin.deltalake.DeltaLakeCdfPageSink.CHANGE_DATA_FOLDER_NAME;
import static io.trino.plugin.deltalake.DeltaLakeMetadata.CHANGE_DATA_FEED_COLUMN_NAMES;
import static io.trino.plugin.deltalake.DeltaLakeQueryRunner.DELTA_CATALOG;
import static io.trino.plugin.deltalake.transactionlog.TransactionLogUtil.TRANSACTION_LOG_DIRECTORY;
import static io.trino.plugin.tpch.TpchMetadata.TINY_SCHEMA_NAME;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.testing.DataProviders.toDataProvider;
import static io.trino.testing.MaterializedResult.resultBuilder;
import static io.trino.testing.QueryAssertions.assertEqualsIgnoreOrder;
import static io.trino.testing.QueryAssertions.copyTpchTables;
import static io.trino.testing.TestingAccessControlManager.TestingPrivilegeType.EXECUTE_FUNCTION;
import static io.trino.testing.TestingAccessControlManager.TestingPrivilegeType.SELECT_COLUMN;
import static io.trino.testing.TestingAccessControlManager.privilege;
import static io.trino.testing.TestingConnectorBehavior.SUPPORTS_CREATE_SCHEMA;
import static io.trino.testing.TestingNames.randomNameSuffix;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

public class TestDeltaLakeConnectorTest
        extends BaseConnectorTest
{
    private static final String SCHEMA = "test_schema";
    private static final String BUCKET_NAME = "trino-ci-test";

    protected HiveMinioDataLake hiveMinioDataLake;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        hiveMinioDataLake = closeAfterClass(new HiveMinioDataLake(BUCKET_NAME));
        hiveMinioDataLake.start();
        QueryRunner queryRunner = DeltaLakeQueryRunner.createS3DeltaLakeQueryRunner(
                DELTA_CATALOG,
                SCHEMA,
                ImmutableMap.of(
                        "delta.enable-non-concurrent-writes", "true",
                        "delta.register-table-procedure.enabled", "true"),
                hiveMinioDataLake.getMinio().getMinioAddress(),
                hiveMinioDataLake.getHiveHadoop());
        queryRunner.execute("CREATE SCHEMA " + SCHEMA + " WITH (location = 's3://" + BUCKET_NAME + "/" + SCHEMA + "')");
        copyTpchTables(queryRunner, "tpch", TINY_SCHEMA_NAME, queryRunner.getDefaultSession(), REQUIRED_TPCH_TABLES);
        return queryRunner;
    }

    @SuppressWarnings("DuplicateBranchesInSwitch")
    @Override
    protected boolean hasBehavior(TestingConnectorBehavior connectorBehavior)
    {
        switch (connectorBehavior) {
            case SUPPORTS_TRUNCATE:
                return false;

            case SUPPORTS_PREDICATE_PUSHDOWN:
            case SUPPORTS_LIMIT_PUSHDOWN:
            case SUPPORTS_TOPN_PUSHDOWN:
            case SUPPORTS_AGGREGATION_PUSHDOWN:
                return false;

            case SUPPORTS_RENAME_SCHEMA:
                return false;

            case SUPPORTS_DROP_COLUMN:
            case SUPPORTS_RENAME_COLUMN:
            case SUPPORTS_SET_COLUMN_TYPE:
                return false;

            case SUPPORTS_COMMENT_ON_VIEW_COLUMN:
                return false;

            case SUPPORTS_CREATE_MATERIALIZED_VIEW:
                return false;

            default:
                return super.hasBehavior(connectorBehavior);
        }
    }

    @Override
    protected String errorMessageForInsertIntoNotNullColumn(String columnName)
    {
        return "NULL value not allowed for NOT NULL column: " + columnName;
    }

    @Override
    protected void verifyConcurrentUpdateFailurePermissible(Exception e)
    {
        assertThat(e)
                .hasMessage("Failed to write Delta Lake transaction log entry")
                .cause()
                .hasMessageMatching(
                        "Transaction log locked.*" +
                                "|.*/_delta_log/\\d+.json already exists" +
                                "|Conflicting concurrent writes found..*" +
                                "|Multiple live locks found for:.*" +
                                "|Target file .* was created during locking");
    }

    @Override
    protected void verifyConcurrentInsertFailurePermissible(Exception e)
    {
        assertThat(e)
                .hasMessage("Failed to write Delta Lake transaction log entry")
                .cause()
                .hasMessageMatching(
                        "Transaction log locked.*" +
                                "|.*/_delta_log/\\d+.json already exists" +
                                "|Conflicting concurrent writes found..*" +
                                "|Multiple live locks found for:.*" +
                                "|Target file .* was created during locking");
    }

    @Override
    protected void verifyConcurrentAddColumnFailurePermissible(Exception e)
    {
        assertThat(e)
                .hasMessageMatching("Unable to add '.*' column for: .*")
                .cause()
                .hasMessageMatching(
                        "Transaction log locked.*" +
                                "|.*/_delta_log/\\d+.json already exists" +
                                "|Conflicting concurrent writes found..*" +
                                "|Multiple live locks found for:.*" +
                                "|Target file .* was created during locking");
    }

    @Override
    protected Optional<DataMappingTestSetup> filterCaseSensitiveDataMappingTestData(DataMappingTestSetup dataMappingTestSetup)
    {
        String typeName = dataMappingTestSetup.getTrinoTypeName();
        if (typeName.equals("char(1)")) {
            return Optional.of(dataMappingTestSetup.asUnsupported());
        }
        return Optional.of(dataMappingTestSetup);
    }

    @Override
    protected Optional<DataMappingTestSetup> filterDataMappingSmokeTestData(DataMappingTestSetup dataMappingTestSetup)
    {
        String typeName = dataMappingTestSetup.getTrinoTypeName();
        if (typeName.equals("time") ||
                typeName.equals("time(6)") ||
                typeName.equals("timestamp") ||
                typeName.equals("timestamp(6)") ||
                typeName.equals("timestamp(6) with time zone") ||
                typeName.equals("char(3)")) {
            return Optional.of(dataMappingTestSetup.asUnsupported());
        }
        return Optional.of(dataMappingTestSetup);
    }

    @Override
    protected TestTable createTableWithDefaultColumns()
    {
        throw new SkipException("Delta Lake does not support columns with a default value");
    }

    @Override
    protected MaterializedResult getDescribeOrdersResult()
    {
        return resultBuilder(getQueryRunner().getDefaultSession(), VARCHAR, VARCHAR, VARCHAR, VARCHAR)
                .row("orderkey", "bigint", "", "")
                .row("custkey", "bigint", "", "")
                .row("orderstatus", "varchar", "", "")
                .row("totalprice", "double", "", "")
                .row("orderdate", "date", "", "")
                .row("orderpriority", "varchar", "", "")
                .row("clerk", "varchar", "", "")
                .row("shippriority", "integer", "", "")
                .row("comment", "varchar", "", "")
                .build();
    }

    @Test
    @Override
    public void testShowCreateTable()
    {
        assertThat((String) computeScalar("SHOW CREATE TABLE orders"))
                .matches("\\QCREATE TABLE " + DELTA_CATALOG + "." + SCHEMA + ".orders (\n" +
                        "   orderkey bigint,\n" +
                        "   custkey bigint,\n" +
                        "   orderstatus varchar,\n" +
                        "   totalprice double,\n" +
                        "   orderdate date,\n" +
                        "   orderpriority varchar,\n" +
                        "   clerk varchar,\n" +
                        "   shippriority integer,\n" +
                        "   comment varchar\n" +
                        ")\n" +
                        "WITH (\n" +
                        "   location = \\E'.*/test_schema/orders.*'\n\\Q" +
                        ")");
    }

    // not pushdownable means not convertible to a tuple domain
    @Test
    public void testQueryNullPartitionWithNotPushdownablePredicate()
    {
        String tableName = "test_null_partitions_" + randomNameSuffix();
        assertUpdate("" +
                        "CREATE TABLE " + tableName + " (a, b, c) WITH (location = '" + format("s3://%s/%s", BUCKET_NAME, tableName) + "', partitioned_by = ARRAY['c']) " +
                        "AS VALUES (1, 1, 1), (2, 2, 2), (3, 3, 3), (null, null, null), (4, 4, 4)",
                "VALUES 5");
        assertQuery("SELECT a FROM " + tableName + " WHERE c % 5 = 1", "VALUES (1)");
    }

    @Test
    public void testPartitionColumnOrderIsDifferentFromTableDefinition()
    {
        String tableName = "test_partition_order_is_different_from_table_definition_" + randomNameSuffix();
        assertUpdate("" +
                "CREATE TABLE " + tableName + "(data int, first varchar, second varchar) " +
                "WITH (" +
                "partitioned_by = ARRAY['second', 'first'], " +
                "location = '" + format("s3://%s/%s", BUCKET_NAME, tableName) + "')");

        assertUpdate("INSERT INTO " + tableName + " VALUES (1, 'first#1', 'second#1')", 1);
        assertQuery("SELECT * FROM " + tableName, "VALUES (1, 'first#1', 'second#1')");

        assertUpdate("INSERT INTO " + tableName + " (data, first) VALUES (2, 'first#2')", 1);
        assertQuery("SELECT * FROM " + tableName, "VALUES (1, 'first#1', 'second#1'), (2, 'first#2', NULL)");

        assertUpdate("INSERT INTO " + tableName + " (data, second) VALUES (3, 'second#3')", 1);
        assertQuery("SELECT * FROM " + tableName, "VALUES (1, 'first#1', 'second#1'), (2, 'first#2', NULL), (3, NULL, 'second#3')");

        assertUpdate("INSERT INTO " + tableName + " (data) VALUES (4)", 1);
        assertQuery("SELECT * FROM " + tableName, "VALUES (1, 'first#1', 'second#1'), (2, 'first#2', NULL), (3, NULL, 'second#3'), (4, NULL, NULL)");
    }

    @Override
    public void testShowCreateSchema()
    {
        String schemaName = getSession().getSchema().orElseThrow();
        assertThat((String) computeScalar("SHOW CREATE SCHEMA " + schemaName))
                .isEqualTo(format("CREATE SCHEMA %s.%s\n" +
                        "WITH (\n" +
                        "   location = 's3://%s/test_schema'\n" +
                        ")", getSession().getCatalog().orElseThrow(), schemaName, BUCKET_NAME));
    }

    /**
     * @see io.trino.plugin.deltalake.BaseDeltaLakeConnectorSmokeTest#testRenameExternalTable for more test coverage
     */
    @Override
    public void testRenameTable()
    {
        assertThatThrownBy(super::testRenameTable)
                .hasMessage("Renaming managed tables is not allowed with current metastore configuration")
                .hasStackTraceContaining("SQL: ALTER TABLE test_rename_");
    }

    /**
     * @see io.trino.plugin.deltalake.BaseDeltaLakeConnectorSmokeTest#testRenameExternalTableAcrossSchemas for more test coverage
     */
    @Override
    public void testRenameTableAcrossSchema()
    {
        assertThatThrownBy(super::testRenameTableAcrossSchema)
                .hasMessage("Renaming managed tables is not allowed with current metastore configuration")
                .hasStackTraceContaining("SQL: ALTER TABLE test_rename_");
    }

    @Override
    public void testRenameTableToUnqualifiedPreservesSchema()
    {
        assertThatThrownBy(super::testRenameTableToUnqualifiedPreservesSchema)
                .hasMessage("Renaming managed tables is not allowed with current metastore configuration")
                .hasStackTraceContaining("SQL: ALTER TABLE test_source_schema_");
    }

    @Override
    public void testRenameTableToLongTableName()
    {
        assertThatThrownBy(super::testRenameTableToLongTableName)
                .hasMessage("Renaming managed tables is not allowed with current metastore configuration")
                .hasStackTraceContaining("SQL: ALTER TABLE test_rename_");
    }

    @Override
    public void testDropNonEmptySchemaWithTable()
    {
        String schemaName = "test_drop_non_empty_schema_" + randomNameSuffix();
        if (!hasBehavior(SUPPORTS_CREATE_SCHEMA)) {
            return;
        }

        assertUpdate("CREATE SCHEMA " + schemaName + " WITH (location = 's3://" + BUCKET_NAME + "/" + schemaName + "')");
        assertUpdate("CREATE TABLE " + schemaName + ".t(x int)");
        assertQueryFails("DROP SCHEMA " + schemaName, ".*Cannot drop non-empty schema '\\Q" + schemaName + "\\E'");
        assertUpdate("DROP TABLE " + schemaName + ".t");
        assertUpdate("DROP SCHEMA " + schemaName);
    }

    @Override
    public void testCharVarcharComparison()
    {
        // Delta Lake doesn't have a char type
        assertThatThrownBy(super::testCharVarcharComparison)
                .hasStackTraceContaining("Unsupported type: char(3)");
    }

    @Test(dataProvider = "timestampValues")
    public void testTimestampPredicatePushdown(String value)
    {
        String tableName = "test_parquet_timestamp_predicate_pushdown_" + randomNameSuffix();

        assertUpdate("DROP TABLE IF EXISTS " + tableName);
        assertUpdate("CREATE TABLE " + tableName + " (t TIMESTAMP WITH TIME ZONE)");
        assertUpdate("INSERT INTO " + tableName + " VALUES (TIMESTAMP '" + value + "')", 1);

        DistributedQueryRunner queryRunner = (DistributedQueryRunner) getQueryRunner();
        MaterializedResultWithQueryId queryResult = queryRunner.executeWithQueryId(
                getSession(),
                "SELECT * FROM " + tableName + " WHERE t < TIMESTAMP '" + value + "'");
        assertEquals(getQueryInfo(queryRunner, queryResult).getQueryStats().getProcessedInputDataSize().toBytes(), 0);

        queryResult = queryRunner.executeWithQueryId(
                getSession(),
                "SELECT * FROM " + tableName + " WHERE t > TIMESTAMP '" + value + "'");
        assertEquals(getQueryInfo(queryRunner, queryResult).getQueryStats().getProcessedInputDataSize().toBytes(), 0);

        assertQueryStats(
                getSession(),
                "SELECT * FROM " + tableName + " WHERE t = TIMESTAMP '" + value + "'",
                queryStats -> assertThat(queryStats.getProcessedInputDataSize().toBytes()).isGreaterThan(0),
                results -> {});
    }

    @DataProvider
    public Object[][] timestampValues()
    {
        return new Object[][] {
                {"1965-10-31 01:00:08.123 UTC"},
                {"1965-10-31 01:00:08.999 UTC"},
                {"1970-01-01 01:13:42.000 America/Bahia_Banderas"}, // There is a gap in JVM zone
                {"1970-01-01 00:00:00.000 Asia/Kathmandu"},
                {"2018-10-28 01:33:17.456 Europe/Vilnius"},
                {"9999-12-31 23:59:59.999 UTC"}};
    }

    @Test
    public void testAddColumnToPartitionedTable()
    {
        try (TestTable table = new TestTable(getQueryRunner()::execute, "test_add_column_partitioned_table_", "(x VARCHAR, part VARCHAR) WITH (partitioned_by = ARRAY['part'])")) {
            assertUpdate("INSERT INTO " + table.getName() + " SELECT 'first', 'part-0001'", 1);
            assertQueryFails("ALTER TABLE " + table.getName() + " ADD COLUMN x bigint", ".* Column 'x' already exists");
            assertQueryFails("ALTER TABLE " + table.getName() + " ADD COLUMN part bigint", ".* Column 'part' already exists");

            assertUpdate("ALTER TABLE " + table.getName() + " ADD COLUMN a varchar(50)");
            assertUpdate("INSERT INTO " + table.getName() + " SELECT 'second', 'part-0002', 'xxx'", 1);
            assertQuery(
                    "SELECT x, part, a FROM " + table.getName(),
                    "VALUES ('first', 'part-0001', NULL), ('second', 'part-0002', 'xxx')");

            assertUpdate("ALTER TABLE " + table.getName() + " ADD COLUMN b double");
            assertUpdate("INSERT INTO " + table.getName() + " SELECT 'third', 'part-0003', 'yyy', 33.3E0", 1);
            assertQuery(
                    "SELECT x, part, a, b FROM " + table.getName(),
                    "VALUES ('first', 'part-0001', NULL, NULL), ('second', 'part-0002', 'xxx', NULL), ('third', 'part-0003', 'yyy', 33.3)");

            assertUpdate("ALTER TABLE " + table.getName() + " ADD COLUMN IF NOT EXISTS c varchar(50)");
            assertUpdate("ALTER TABLE " + table.getName() + " ADD COLUMN IF NOT EXISTS part varchar(50)");
            assertUpdate("INSERT INTO " + table.getName() + " SELECT 'fourth', 'part-0004', 'zzz', 55.3E0, 'newColumn'", 1);
            assertQuery(
                    "SELECT x, part, a, b, c FROM " + table.getName(),
                    "VALUES ('first', 'part-0001', NULL, NULL, NULL), ('second', 'part-0002', 'xxx', NULL, NULL), ('third', 'part-0003', 'yyy', 33.3, NULL), ('fourth', 'part-0004', 'zzz', 55.3, 'newColumn')");
        }
    }

    private QueryInfo getQueryInfo(DistributedQueryRunner queryRunner, MaterializedResultWithQueryId queryResult)
    {
        return queryRunner.getCoordinator().getQueryManager().getFullQueryInfo(queryResult.getQueryId());
    }

    @Test
    public void testAddColumnAndOptimize()
    {
        try (TestTable table = new TestTable(getQueryRunner()::execute, "test_add_column_and_optimize", "(x VARCHAR)")) {
            assertUpdate("INSERT INTO " + table.getName() + " SELECT 'first'", 1);

            assertUpdate("ALTER TABLE " + table.getName() + " ADD COLUMN a varchar(50)");
            assertUpdate("INSERT INTO " + table.getName() + " SELECT 'second', 'xxx'", 1);
            assertQuery(
                    "SELECT x, a FROM " + table.getName(),
                    "VALUES ('first', NULL), ('second', 'xxx')");

            Set<String> beforeActiveFiles = getActiveFiles(table.getName());
            computeActual("ALTER TABLE " + table.getName() + " EXECUTE OPTIMIZE");

            // Verify OPTIMIZE happened, but table data didn't change
            assertThat(beforeActiveFiles).isNotEqualTo(getActiveFiles(table.getName()));
            assertQuery(
                    "SELECT x, a FROM " + table.getName(),
                    "VALUES ('first', NULL), ('second', 'xxx')");
        }
    }

    @Test
    public void testAddColumnAndVacuum()
            throws Exception
    {
        Session sessionWithShortRetentionUnlocked = Session.builder(getSession())
                .setCatalogSessionProperty(getSession().getCatalog().orElseThrow(), "vacuum_min_retention", "0s")
                .build();

        try (TestTable table = new TestTable(getQueryRunner()::execute, "test_add_column_and_optimize", "(x VARCHAR)")) {
            assertUpdate("INSERT INTO " + table.getName() + " SELECT 'first'", 1);
            assertUpdate("INSERT INTO " + table.getName() + " SELECT 'second'", 1);

            Set<String> initialFiles = getActiveFiles(table.getName());
            assertThat(initialFiles).hasSize(2);

            assertUpdate("ALTER TABLE " + table.getName() + " ADD COLUMN a varchar(50)");

            assertUpdate("UPDATE " + table.getName() + " SET a = 'new column'", 2);
            Stopwatch timeSinceUpdate = Stopwatch.createStarted();
            Set<String> updatedFiles = getActiveFiles(table.getName());
            assertThat(updatedFiles)
                    .hasSizeGreaterThanOrEqualTo(1)
                    .hasSizeLessThanOrEqualTo(2)
                    .doesNotContainAnyElementsOf(initialFiles);
            assertThat(getAllDataFilesFromTableDirectory(table.getName())).isEqualTo(union(initialFiles, updatedFiles));

            assertQuery(
                    "SELECT x, a FROM " + table.getName(),
                    "VALUES ('first', 'new column'), ('second', 'new column')");

            MILLISECONDS.sleep(1_000 - timeSinceUpdate.elapsed(MILLISECONDS) + 1);
            assertUpdate(sessionWithShortRetentionUnlocked, "CALL system.vacuum(schema_name => CURRENT_SCHEMA, table_name => '" + table.getName() + "', retention => '1s')");

            // Verify VACUUM happened, but table data didn't change
            assertThat(getAllDataFilesFromTableDirectory(table.getName())).isEqualTo(updatedFiles);
            assertQuery(
                    "SELECT x, a FROM " + table.getName(),
                    "VALUES ('first', 'new column'), ('second', 'new column')");
        }
    }

    @Test
    public void testTargetMaxFileSize()
    {
        String tableName = "test_default_max_file_size" + randomNameSuffix();
        @Language("SQL") String createTableSql = format("CREATE TABLE %s AS SELECT * FROM tpch.sf1.lineitem LIMIT 100000", tableName);

        Session session = Session.builder(getSession())
                .setSystemProperty("task_writer_count", "1")
                // task scale writers should be disabled since we want to write with a single task writer
                .setSystemProperty("task_scale_writers_enabled", "false")
                .build();
        assertUpdate(session, createTableSql, 100000);
        Set<String> initialFiles = getActiveFiles(tableName);
        assertThat(initialFiles.size()).isLessThanOrEqualTo(3);
        assertUpdate(format("DROP TABLE %s", tableName));

        DataSize maxSize = DataSize.of(40, DataSize.Unit.KILOBYTE);
        session = Session.builder(getSession())
                .setSystemProperty("task_writer_count", "1")
                // task scale writers should be disabled since we want to write with a single task writer
                .setSystemProperty("task_scale_writers_enabled", "false")
                .setCatalogSessionProperty("delta", "target_max_file_size", maxSize.toString())
                .build();

        assertUpdate(session, createTableSql, 100000);
        assertThat(query(format("SELECT count(*) FROM %s", tableName))).matches("VALUES BIGINT '100000'");
        Set<String> updatedFiles = getActiveFiles(tableName);
        assertThat(updatedFiles.size()).isGreaterThan(10);

        MaterializedResult result = computeActual("SELECT DISTINCT \"$path\", \"$file_size\" FROM " + tableName);
        for (MaterializedRow row : result) {
            // allow up to a larger delta due to the very small max size and the relatively large writer chunk size
            assertThat((Long) row.getField(1)).isLessThan(maxSize.toBytes() * 5);
        }
    }

    @Test
    public void testPathColumn()
    {
        try (TestTable table = new TestTable(getQueryRunner()::execute, "test_path_column", "(x VARCHAR)")) {
            assertUpdate("INSERT INTO " + table.getName() + " SELECT 'first'", 1);
            String firstFilePath = (String) computeScalar("SELECT \"$path\" FROM " + table.getName());
            assertUpdate("INSERT INTO " + table.getName() + " SELECT 'second'", 1);
            String secondFilePath = (String) computeScalar("SELECT \"$path\" FROM " + table.getName() + " WHERE x = 'second'");

            // Verify predicate correctness on $path column
            assertQuery("SELECT x FROM " + table.getName() + " WHERE \"$path\" = '" + firstFilePath + "'", "VALUES 'first'");
            assertQuery("SELECT x FROM " + table.getName() + " WHERE \"$path\" <> '" + firstFilePath + "'", "VALUES 'second'");
            assertQuery("SELECT x FROM " + table.getName() + " WHERE \"$path\" IN ('" + firstFilePath + "', '" + secondFilePath + "')", "VALUES ('first'), ('second')");
            assertQuery("SELECT x FROM " + table.getName() + " WHERE \"$path\" IS NOT NULL", "VALUES ('first'), ('second')");
            assertQueryReturnsEmptyResult("SELECT x FROM " + table.getName() + " WHERE \"$path\" IS NULL");
        }
    }

    @Test
    public void testTableLocationTrailingSpace()
    {
        String tableName = "table_with_space_" + randomNameSuffix();
        String tableLocationWithTrailingSpace = "s3://" + BUCKET_NAME + "/" + tableName + " ";

        assertUpdate(format("CREATE TABLE %s (customer VARCHAR) WITH (location = '%s')", tableName, tableLocationWithTrailingSpace));
        assertUpdate("INSERT INTO " + tableName + " (customer) VALUES ('Aaron'), ('Bill')", 2);
        assertQuery("SELECT * FROM " + tableName, "VALUES ('Aaron'), ('Bill')");

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testTableLocationTrailingSlash()
    {
        String tableWithSlash = "table_with_slash";
        String tableWithoutSlash = "table_without_slash";

        assertUpdate(format("CREATE TABLE %s (customer VARCHAR) WITH (location = 's3://%s/%s/')", tableWithSlash, BUCKET_NAME, tableWithSlash));
        assertUpdate(format("INSERT INTO %s (customer) VALUES ('Aaron'), ('Bill')", tableWithSlash), 2);
        assertQuery("SELECT * FROM " + tableWithSlash, "VALUES ('Aaron'), ('Bill')");

        assertUpdate(format("CREATE TABLE %s (customer VARCHAR) WITH (location = 's3://%s/%s')", tableWithoutSlash, BUCKET_NAME, tableWithoutSlash));
        assertUpdate(format("INSERT INTO %s (customer) VALUES ('Carol'), ('Dave')", tableWithoutSlash), 2);
        assertQuery("SELECT * FROM " + tableWithoutSlash, "VALUES ('Carol'), ('Dave')");

        assertUpdate("DROP TABLE " + tableWithSlash);
        assertUpdate("DROP TABLE " + tableWithoutSlash);
    }

    @Test
    public void testMergeSimpleSelectPartitioned()
    {
        String targetTable = "merge_simple_target_" + randomNameSuffix();
        String sourceTable = "merge_simple_source_" + randomNameSuffix();
        assertUpdate(format("CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['address'])", targetTable, BUCKET_NAME, targetTable));

        assertUpdate(format("INSERT INTO %s (customer, purchases, address) VALUES ('Aaron', 5, 'Antioch'), ('Bill', 7, 'Buena'), ('Carol', 3, 'Cambridge'), ('Dave', 11, 'Devon')", targetTable), 4);

        assertUpdate(format("CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s')", sourceTable, BUCKET_NAME, sourceTable));

        assertUpdate(format("INSERT INTO %s (customer, purchases, address) VALUES ('Aaron', 6, 'Arches'), ('Ed', 7, 'Etherville'), ('Carol', 9, 'Centreville'), ('Dave', 11, 'Darbyshire')", sourceTable), 4);

        @Language("SQL") String sql = format("MERGE INTO %s t USING %s s ON (t.customer = s.customer)", targetTable, sourceTable) +
                "    WHEN MATCHED AND s.address = 'Centreville' THEN DELETE" +
                "    WHEN MATCHED THEN UPDATE SET purchases = s.purchases + t.purchases, address = s.address" +
                "    WHEN NOT MATCHED THEN INSERT (customer, purchases, address) VALUES(s.customer, s.purchases, s.address)";

        assertUpdate(sql, 4);

        assertQuery("SELECT * FROM " + targetTable, "VALUES ('Aaron', 11, 'Arches'), ('Ed', 7, 'Etherville'), ('Bill', 7, 'Buena'), ('Dave', 22, 'Darbyshire')");

        assertUpdate("DROP TABLE " + sourceTable);
        assertUpdate("DROP TABLE " + targetTable);
    }

    @Test(dataProvider = "partitionedProvider")
    public void testMergeUpdateWithVariousLayouts(String partitionPhase)
    {
        String targetTable = "merge_formats_target_" + randomNameSuffix();
        String sourceTable = "merge_formats_source_" + randomNameSuffix();
        assertUpdate(format("CREATE TABLE %s (customer VARCHAR, purchase VARCHAR) WITH (location = 's3://%s/%s'%s)", targetTable, BUCKET_NAME, targetTable, partitionPhase));

        assertUpdate(format("INSERT INTO %s (customer, purchase) VALUES ('Dave', 'dates'), ('Lou', 'limes'), ('Carol', 'candles')", targetTable), 3);
        assertQuery("SELECT * FROM " + targetTable, "VALUES ('Dave', 'dates'), ('Lou', 'limes'), ('Carol', 'candles')");

        assertUpdate(format("CREATE TABLE %s (customer VARCHAR, purchase VARCHAR) WITH (location = 's3://%s/%s')", sourceTable, BUCKET_NAME, sourceTable));

        assertUpdate(format("INSERT INTO %s (customer, purchase) VALUES ('Craig', 'candles'), ('Len', 'limes'), ('Joe', 'jellybeans')", sourceTable), 3);

        @Language("SQL") String sql = format("MERGE INTO %s t USING %s s ON (t.purchase = s.purchase)", targetTable, sourceTable) +
                "    WHEN MATCHED AND s.purchase = 'limes' THEN DELETE" +
                "    WHEN MATCHED THEN UPDATE SET customer = CONCAT(t.customer, '_', s.customer)" +
                "    WHEN NOT MATCHED THEN INSERT (customer, purchase) VALUES(s.customer, s.purchase)";

        assertUpdate(sql, 3);

        assertQuery("SELECT * FROM " + targetTable, "VALUES ('Dave', 'dates'), ('Carol_Craig', 'candles'), ('Joe', 'jellybeans')");
        assertUpdate("DROP TABLE " + sourceTable);
        assertUpdate("DROP TABLE " + targetTable);
    }

    @DataProvider
    public Object[][] partitionedProvider()
    {
        return new Object[][] {
                {""},
                {", partitioned_by = ARRAY['customer']"},
                {", partitioned_by = ARRAY['purchase']"}
        };
    }

    @Test(dataProvider = "partitionedProvider")
    public void testMergeMultipleOperations(String partitioning)
    {
        int targetCustomerCount = 32;
        String targetTable = "merge_multiple_" + randomNameSuffix();
        assertUpdate(format("CREATE TABLE %s (purchase INT, zipcode INT, spouse VARCHAR, address VARCHAR, customer VARCHAR) WITH (location = 's3://%s/%s'%s)", targetTable, BUCKET_NAME, targetTable, partitioning));
        String originalInsertFirstHalf = IntStream.range(1, targetCustomerCount / 2)
                .mapToObj(intValue -> format("('joe_%s', %s, %s, 'jan_%s', '%s Poe Ct')", intValue, 1000, 91000, intValue, intValue))
                .collect(Collectors.joining(", "));
        String originalInsertSecondHalf = IntStream.range(targetCustomerCount / 2, targetCustomerCount)
                .mapToObj(intValue -> format("('joe_%s', %s, %s, 'jan_%s', '%s Poe Ct')", intValue, 2000, 92000, intValue, intValue))
                .collect(Collectors.joining(", "));

        assertUpdate(format("INSERT INTO %s (customer, purchase, zipcode, spouse, address) VALUES %s, %s", targetTable, originalInsertFirstHalf, originalInsertSecondHalf), targetCustomerCount - 1);

        String firstMergeSource = IntStream.range(targetCustomerCount / 2, targetCustomerCount)
                .mapToObj(intValue -> format("('joe_%s', %s, %s, 'jill_%s', '%s Eop Ct')", intValue, 3000, 83000, intValue, intValue))
                .collect(Collectors.joining(", "));

        assertUpdate(format("MERGE INTO %s t USING (VALUES %s) AS s(customer, purchase, zipcode, spouse, address)", targetTable, firstMergeSource) +
                        "    ON t.customer = s.customer" +
                        "    WHEN MATCHED THEN UPDATE SET purchase = s.purchase, zipcode = s.zipcode, spouse = s.spouse, address = s.address",
                targetCustomerCount / 2);

        assertQuery(
                "SELECT customer, purchase, zipcode, spouse, address FROM " + targetTable,
                format("VALUES %s, %s", originalInsertFirstHalf, firstMergeSource));

        String nextInsert = IntStream.range(targetCustomerCount, targetCustomerCount * 3 / 2)
                .mapToObj(intValue -> format("('jack_%s', %s, %s, 'jan_%s', '%s Poe Ct')", intValue, 4000, 74000, intValue, intValue))
                .collect(Collectors.joining(", "));

        assertUpdate(format("INSERT INTO %s (customer, purchase, zipcode, spouse, address) VALUES %s", targetTable, nextInsert), targetCustomerCount / 2);

        String secondMergeSource = IntStream.range(1, targetCustomerCount * 3 / 2)
                .mapToObj(intValue -> format("('joe_%s', %s, %s, 'jen_%s', '%s Poe Ct')", intValue, 5000, 85000, intValue, intValue))
                .collect(Collectors.joining(", "));

        assertUpdate(format("MERGE INTO %s t USING (VALUES %s) AS s(customer, purchase, zipcode, spouse, address)", targetTable, secondMergeSource) +
                        "    ON t.customer = s.customer" +
                        "    WHEN MATCHED AND t.zipcode = 91000 THEN DELETE" +
                        "    WHEN MATCHED AND s.zipcode = 85000 THEN UPDATE SET zipcode = 60000" +
                        "    WHEN MATCHED THEN UPDATE SET zipcode = s.zipcode, spouse = s.spouse, address = s.address" +
                        "    WHEN NOT MATCHED THEN INSERT (customer, purchase, zipcode, spouse, address) VALUES(s.customer, s.purchase, s.zipcode, s.spouse, s.address)",
                targetCustomerCount * 3 / 2 - 1);

        String updatedBeginning = IntStream.range(targetCustomerCount / 2, targetCustomerCount)
                .mapToObj(intValue -> format("('joe_%s', %s, %s, 'jill_%s', '%s Eop Ct')", intValue, 3000, 60000, intValue, intValue))
                .collect(Collectors.joining(", "));
        String updatedMiddle = IntStream.range(targetCustomerCount, targetCustomerCount * 3 / 2)
                .mapToObj(intValue -> format("('joe_%s', %s, %s, 'jen_%s', '%s Poe Ct')", intValue, 5000, 85000, intValue, intValue))
                .collect(Collectors.joining(", "));
        String updatedEnd = IntStream.range(targetCustomerCount, targetCustomerCount * 3 / 2)
                .mapToObj(intValue -> format("('jack_%s', %s, %s, 'jan_%s', '%s Poe Ct')", intValue, 4000, 74000, intValue, intValue))
                .collect(Collectors.joining(", "));

        assertQuery(
                "SELECT customer, purchase, zipcode, spouse, address FROM " + targetTable,
                format("VALUES %s, %s, %s", updatedBeginning, updatedMiddle, updatedEnd));

        assertUpdate("DROP TABLE " + targetTable);
    }

    @Test
    public void testMergeSimpleQueryPartitioned()
    {
        String targetTable = "merge_simple_" + randomNameSuffix();
        assertUpdate(format("CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['address'])", targetTable, BUCKET_NAME, targetTable));

        assertUpdate(format("INSERT INTO %s (customer, purchases, address) VALUES ('Aaron', 5, 'Antioch'), ('Bill', 7, 'Buena'), ('Carol', 3, 'Cambridge'), ('Dave', 11, 'Devon')", targetTable), 4);

        @Language("SQL") String query = format("MERGE INTO %s t USING ", targetTable) +
                "(SELECT * FROM (VALUES ('Aaron', 6, 'Arches'), ('Carol', 9, 'Centreville'), ('Dave', 11, 'Darbyshire'), ('Ed', 7, 'Etherville'))) AS s(customer, purchases, address)" +
                "    " +
                "ON (t.customer = s.customer)" +
                "    WHEN MATCHED AND s.address = 'Centreville' THEN DELETE" +
                "    WHEN MATCHED THEN UPDATE SET purchases = s.purchases + t.purchases, address = s.address" +
                "    WHEN NOT MATCHED THEN INSERT (customer, purchases, address) VALUES(s.customer, s.purchases, s.address)";
        assertUpdate(query, 4);

        assertQuery("SELECT * FROM " + targetTable, "VALUES ('Aaron', 11, 'Arches'), ('Bill', 7, 'Buena'), ('Dave', 22, 'Darbyshire'), ('Ed', 7, 'Etherville')");

        assertUpdate("DROP TABLE " + targetTable);
    }

    @Test(dataProvider = "targetWithDifferentPartitioning")
    public void testMergeMultipleRowsMatchFails(String createTableSql)
    {
        String targetTable = "merge_multiple_target_" + randomNameSuffix();
        String sourceTable = "merge_multiple_source_" + randomNameSuffix();
        assertUpdate(format(createTableSql, targetTable, BUCKET_NAME, targetTable));

        assertUpdate(format("INSERT INTO %s (customer, purchases, address) VALUES ('Aaron', 5, 'Antioch'), ('Bill', 7, 'Antioch')", targetTable), 2);

        assertUpdate(format("CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s')", sourceTable, BUCKET_NAME, sourceTable));

        assertUpdate(format("INSERT INTO %s (customer, purchases, address) VALUES ('Aaron', 6, 'Adelphi'), ('Aaron', 8, 'Ashland')", sourceTable), 2);

        assertThatThrownBy(() -> computeActual(format("MERGE INTO %s t USING %s s ON (t.customer = s.customer)", targetTable, sourceTable) +
                "    WHEN MATCHED THEN UPDATE SET address = s.address"))
                .hasMessage("One MERGE target table row matched more than one source row");

        assertUpdate(format("MERGE INTO %s t USING %s s ON (t.customer = s.customer)", targetTable, sourceTable) +
                        "    WHEN MATCHED AND s.address = 'Adelphi' THEN UPDATE SET address = s.address",
                1);
        assertQuery("SELECT customer, purchases, address FROM " + targetTable, "VALUES ('Aaron', 5, 'Adelphi'), ('Bill', 7, 'Antioch')");
        assertUpdate("DROP TABLE " + sourceTable);
        assertUpdate("DROP TABLE " + targetTable);
    }

    @DataProvider
    public Object[][] targetWithDifferentPartitioning()
    {
        return new Object[][] {
                {"CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s')"},
                {"CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['customer'])"},
                {"CREATE TABLE %s (customer VARCHAR, address VARCHAR, purchases INT) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['address'])"},
                {"CREATE TABLE %s (purchases INT, customer VARCHAR, address VARCHAR) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['address', 'customer'])"},
                {"CREATE TABLE %s (purchases INT, address VARCHAR, customer VARCHAR) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['address', 'customer'])"}
        };
    }

    @Test(dataProvider = "targetAndSourceWithDifferentPartitioning")
    public void testMergeWithDifferentPartitioning(String testDescription, String createTargetTableSql, String createSourceTableSql)
    {
        String targetTable = format("%s_target_%s", testDescription, randomNameSuffix());
        String sourceTable = format("%s_source_%s", testDescription, randomNameSuffix());

        assertUpdate(format(createTargetTableSql, targetTable, BUCKET_NAME, targetTable));

        assertUpdate(format("INSERT INTO %s (customer, purchases, address) VALUES ('Aaron', 5, 'Antioch'), ('Bill', 7, 'Buena'), ('Carol', 3, 'Cambridge'), ('Dave', 11, 'Devon')", targetTable), 4);

        assertUpdate(format(createSourceTableSql, sourceTable, BUCKET_NAME, sourceTable));

        assertUpdate(format("INSERT INTO %s (customer, purchases, address) VALUES ('Aaron', 6, 'Arches'), ('Ed', 7, 'Etherville'), ('Carol', 9, 'Centreville'), ('Dave', 11, 'Darbyshire')", sourceTable), 4);

        @Language("SQL") String sql = format("MERGE INTO %s t USING %s s ON (t.customer = s.customer)", targetTable, sourceTable) +
                "    WHEN MATCHED AND s.address = 'Centreville' THEN DELETE" +
                "    WHEN MATCHED THEN UPDATE SET purchases = s.purchases + t.purchases, address = s.address" +
                "    WHEN NOT MATCHED THEN INSERT (customer, purchases, address) VALUES(s.customer, s.purchases, s.address)";
        assertUpdate(sql, 4);

        assertQuery("SELECT * FROM " + targetTable, "VALUES ('Aaron', 11, 'Arches'), ('Bill', 7, 'Buena'), ('Dave', 22, 'Darbyshire'), ('Ed', 7, 'Etherville')");

        assertUpdate("DROP TABLE " + sourceTable);
        assertUpdate("DROP TABLE " + targetTable);
    }

    @DataProvider
    public Object[][] targetAndSourceWithDifferentPartitioning()
    {
        return new Object[][] {
                {
                        "target_partitioned_source_and_target_partitioned",
                        "CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['address', 'customer'])",
                        "CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['address'])",
                },
                {
                        "target_partitioned_source_and_target_partitioned",
                        "CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['customer', 'address'])",
                        "CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['address'])",
                },
                {
                        "target_flat_source_partitioned_by_customer",
                        "CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s')",
                        "CREATE TABLE %s (purchases INT, address VARCHAR, customer VARCHAR) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['customer'])"
                },
                {
                        "target_partitioned_by_customer_source_flat",
                        "CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['address'])",
                        "CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s')",
                },
                {
                        "target_bucketed_by_customer_source_flat",
                        "CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['customer', 'address'])",
                        "CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s')",
                },
                {
                        "target_partitioned_source_partitioned",
                        "CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['customer'])",
                        "CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['address'])",
                },
                {
                        "target_partitioned_target_partitioned",
                        "CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['address'])",
                        "CREATE TABLE %s (customer VARCHAR, purchases INT, address VARCHAR) WITH (location = 's3://%s/%s', partitioned_by = ARRAY['customer'])",
                }
        };
    }

    @Test
    public void testTableWithNonNullableColumns()
    {
        String tableName = "test_table_with_non_nullable_columns_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName + "(col1 INTEGER NOT NULL, col2 INTEGER, col3 INTEGER)");
        assertUpdate("INSERT INTO " + tableName + " VALUES(1, 10, 100)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES(2, 20, 200)", 1);
        assertThatThrownBy(() -> query("INSERT INTO " + tableName + " VALUES(null, 30, 300)"))
                .hasMessageContaining("NULL value not allowed for NOT NULL column: col1");
        assertThatThrownBy(() -> query("INSERT INTO " + tableName + " VALUES(TRY(5/0), 40, 400)"))
                .hasMessageContaining("NULL value not allowed for NOT NULL column: col1");

        assertThatThrownBy(() -> query("UPDATE " + tableName + " SET col1 = NULL where col3 = 100"))
                .hasMessageContaining("NULL value not allowed for NOT NULL column: col1");
        assertThatThrownBy(() -> query("UPDATE " + tableName + " SET col1 = TRY(5/0) where col3 = 200"))
                .hasMessageContaining("NULL value not allowed for NOT NULL column: col1");

        assertQuery("SELECT * FROM " + tableName, "VALUES(1, 10, 100), (2, 20, 200)");
    }

    @Test(dataProvider = "changeDataFeedColumnNamesDataProvider")
    public void testCreateTableWithChangeDataFeedColumnName(String columnName)
    {
        try (TestTable table = new TestTable(getQueryRunner()::execute, "test_create_table_cdf", "(" + columnName + " int)")) {
            assertTableColumnNames(table.getName(), columnName);
        }

        try (TestTable table = new TestTable(getQueryRunner()::execute, "test_create_table_cdf", "AS SELECT 1 AS " + columnName)) {
            assertTableColumnNames(table.getName(), columnName);
        }
    }

    @Test(dataProvider = "changeDataFeedColumnNamesDataProvider")
    public void testUnsupportedCreateTableWithChangeDataFeed(String columnName)
    {
        String tableName = "test_unsupported_create_table_cdf" + randomNameSuffix();

        assertQueryFails(
                "CREATE TABLE " + tableName + "(" + columnName + " int) WITH (change_data_feed_enabled = true)",
                "\\QUnable to use [%s] when change data feed is enabled\\E".formatted(columnName));
        assertFalse(getQueryRunner().tableExists(getSession(), tableName));

        assertQueryFails(
                "CREATE TABLE " + tableName + " WITH (change_data_feed_enabled = true) AS SELECT 1 AS " + columnName,
                "\\QUnable to use [%s] when change data feed is enabled\\E".formatted(columnName));
        assertFalse(getQueryRunner().tableExists(getSession(), tableName));
    }

    @Test(dataProvider = "changeDataFeedColumnNamesDataProvider")
    public void testUnsupportedAddColumnWithChangeDataFeed(String columnName)
    {
        try (TestTable table = new TestTable(getQueryRunner()::execute, "test_add_column", "(col int) WITH (change_data_feed_enabled = true)")) {
            assertQueryFails(
                    "ALTER TABLE " + table.getName() + " ADD COLUMN " + columnName + " int",
                    "\\QColumn name %s is forbidden when change data feed is enabled\\E".formatted(columnName));
            assertTableColumnNames(table.getName(), "col");

            assertUpdate("ALTER TABLE " + table.getName() + " SET PROPERTIES change_data_feed_enabled = false");
            assertUpdate("ALTER TABLE " + table.getName() + " ADD COLUMN " + columnName + " int");
            assertTableColumnNames(table.getName(), "col", columnName);
        }
    }

    @Test(dataProvider = "changeDataFeedColumnNamesDataProvider")
    public void testUnsupportedSetTablePropertyWithChangeDataFeed(String columnName)
    {
        try (TestTable table = new TestTable(getQueryRunner()::execute, "test_set_properties", "(" + columnName + " int)")) {
            assertQueryFails(
                    "ALTER TABLE " + table.getName() + " SET PROPERTIES change_data_feed_enabled = true",
                    "\\QUnable to enable change data feed because table contains [%s] columns\\E".formatted(columnName));
            assertThat((String) computeScalar("SHOW CREATE TABLE " + table.getName()))
                    .doesNotContain("change_data_feed_enabled = true");
        }
    }

    @DataProvider
    public Object[][] changeDataFeedColumnNamesDataProvider()
    {
        return CHANGE_DATA_FEED_COLUMN_NAMES.stream().collect(toDataProvider());
    }

    @Test
    public void testThatEnableCdfTablePropertyIsShownForCtasTables()
    {
        String tableName = "test_show_create_show_property_for_table_created_with_ctas_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName + "(page_url, views)" +
                "WITH (change_data_feed_enabled = true) " +
                "AS VALUES ('url1', 1), ('url2', 2)", 2);
        assertThat((String) computeScalar("SHOW CREATE TABLE " + tableName))
                .contains("change_data_feed_enabled = true");
    }

    @Test
    public void testAlterTableWithUnsupportedProperties()
    {
        String tableName = "test_alter_table_with_unsupported_properties_" + randomNameSuffix();

        assertUpdate("CREATE TABLE " + tableName + " (a_number INT)");

        assertQueryFails("ALTER TABLE " + tableName + " SET PROPERTIES change_data_feed_enabled = true, checkpoint_interval = 10",
                "The following properties cannot be updated: checkpoint_interval");
        assertQueryFails("ALTER TABLE " + tableName + " SET PROPERTIES partitioned_by = ARRAY['a']",
                "The following properties cannot be updated: partitioned_by");

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testSettingChangeDataFeedEnabledProperty()
    {
        String tableName = "test_enable_and_disable_cdf_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (page_url VARCHAR, domain VARCHAR, views INTEGER)");

        assertUpdate("ALTER TABLE " + tableName + " SET PROPERTIES change_data_feed_enabled = false");
        assertThat((String) computeScalar("SHOW CREATE TABLE " + tableName))
                .contains("change_data_feed_enabled = false");

        assertUpdate("ALTER TABLE " + tableName + " SET PROPERTIES change_data_feed_enabled = true");
        assertThat((String) computeScalar("SHOW CREATE TABLE " + tableName)).contains("change_data_feed_enabled = true");

        assertUpdate("ALTER TABLE " + tableName + " SET PROPERTIES change_data_feed_enabled = false");
        assertThat((String) computeScalar("SHOW CREATE TABLE " + tableName)).contains("change_data_feed_enabled = false");

        assertUpdate("ALTER TABLE " + tableName + " SET PROPERTIES change_data_feed_enabled = true");
        assertThat((String) computeScalar("SHOW CREATE TABLE " + tableName))
                .contains("change_data_feed_enabled = true");
    }

    @Test
    public void testProjectionPushdown()
    {
        String tableName = "test_projection_pushdown_" + randomNameSuffix();

        assertUpdate("CREATE TABLE " + tableName + " (id BIGINT, root ROW(f1 BIGINT, f2 BIGINT))");
        assertUpdate("INSERT INTO " + tableName + " VALUES (1, ROW(1, 2)), (1, NULl), (1, ROW(NULL, 4))", 3);

        assertQuery("SELECT root.f1, id FROM " + tableName, "VALUES (1, 1), (NULL, 1), (NULL, 1)");

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testProjectionPushdownOnPartitionedTables()
    {
        String tableNamePartitionAtBeginning = "test_table_with_partition_at_beginning_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableNamePartitionAtBeginning + " (id BIGINT, root ROW(f1 BIGINT, f2 BIGINT)) WITH (partitioned_by = ARRAY['id'])");
        assertUpdate("INSERT INTO " + tableNamePartitionAtBeginning + " VALUES (1, ROW(1, 2)), (1, ROW(2, 3)), (1, ROW(3, 4))", 3);
        assertQuery("SELECT root.f1, id, root.f2 FROM " + tableNamePartitionAtBeginning, "VALUES (1, 1, 2), (2, 1, 3), (3, 1, 4)");
        assertUpdate("DROP TABLE " + tableNamePartitionAtBeginning);

        String tableNamePartitioningAtEnd = "tes_table_with_partition_at_end_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableNamePartitioningAtEnd + " (root ROW(f1 BIGINT, f2 BIGINT), id BIGINT) WITH (partitioned_by = ARRAY['id'])");
        assertUpdate("INSERT INTO " + tableNamePartitioningAtEnd + " VALUES (ROW(1, 2), 1), (ROW(2, 3), 1), (ROW(3, 4), 1)", 3);
        assertQuery("SELECT root.f2, id, root.f1 FROM " + tableNamePartitioningAtEnd, "VALUES (2, 1, 1), (3, 1, 2), (4, 1, 3)");
        assertUpdate("DROP TABLE " + tableNamePartitioningAtEnd);
    }

    @Test
    public void testProjectionWithCaseSensitiveField()
    {
        // TODO consider moving this in BaseConnectorTest
        String tableName = "test_projection_with_case_sensitive_field_" + randomNameSuffix();

        assertUpdate("CREATE TABLE " + tableName + " (id INT, a ROW(\"UPPER_CASE\" INT, \"lower_case\" INT, \"MiXeD_cAsE\" INT))");
        assertUpdate("INSERT INTO " + tableName + " VALUES (1, ROW(2, 3, 4)), (5, ROW(6, 7, 8))", 2);

        String expected = "VALUES (2, 3, 4), (6, 7, 8)";
        assertQuery("SELECT a.UPPER_CASE, a.lower_case, a.MiXeD_cAsE FROM " + tableName, expected);
        assertQuery("SELECT a.upper_case, a.lower_case, a.mixed_case FROM " + tableName, expected);
        assertQuery("SELECT a.UPPER_CASE, a.LOWER_CASE, a.MIXED_CASE FROM " + tableName, expected);
        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testProjectionPushdownMultipleRows()
    {
        String tableName = "test_projection_pushdown_multiple_rows_" + randomNameSuffix();

        assertUpdate("CREATE TABLE " + tableName +
                " (id BIGINT, nested1 ROW(child1 BIGINT, child2 VARCHAR, child3 INT), nested2 ROW(child1 DOUBLE, child2 BOOLEAN, child3 DATE))");
        assertUpdate("INSERT INTO " + tableName + " VALUES" +
                        " (1, ROW(10, 'a', 100), ROW(10.10, true, DATE '2023-04-19'))," +
                        " (2, ROW(20, 'b', 200), ROW(20.20, false, DATE '1990-04-20'))," +
                        " (4, ROW(40, NULL, 400), NULL)," +
                        " (5, NULL, ROW(NULL, true, NULL))",
                4);

        // Select one field from one row field
        assertQuery("SELECT id, nested1.child1 FROM " + tableName, "VALUES (1, 10), (2, 20), (4, 40), (5, NULL)");
        assertQuery("SELECT nested2.child3, id FROM " + tableName, "VALUES (DATE '2023-04-19', 1), (DATE '1990-04-20', 2), (NULL, 4), (NULL, 5)");

        // Select one field each from multiple row fields
        assertQuery("SELECT nested2.child1, id, nested1.child2 FROM " + tableName, "VALUES (10.10, 1, 'a'), (20.20, 2, 'b'), (NULL, 4, NULL), (NULL, 5, NULL)");

        // Select multiple fields from one row field
        assertQuery("SELECT nested1.child3, id, nested1.child2 FROM " + tableName, "VALUES (100, 1, 'a'), (200, 2, 'b'), (400, 4, NULL), (NULL, 5, NULL)");
        assertQuery(
                "SELECT nested2.child2, nested2.child3, id FROM " + tableName,
                "VALUES (true, DATE '2023-04-19' , 1), (false, DATE '1990-04-20', 2), (NULL, NULL, 4), (true, NULL, 5)");

        // Select multiple fields from multiple row fields
        assertQuery(
                "SELECT id, nested2.child1, nested1.child3, nested2.child2, nested1.child1 FROM " + tableName,
                "VALUES (1, 10.10, 100, true, 10), (2, 20.20, 200, false, 20), (4, NULL, 400, NULL, 40), (5, NULL, NULL, true, NULL)");

        // Select only nested fields
        assertQuery("SELECT nested2.child2, nested1.child3 FROM " + tableName, "VALUES (true, 100), (false, 200), (NULL, 400), (true, NULL)");

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testReadHighlyNestedData()
    {
        // TODO consider moving this in BaseConnectorTest
        String tableName = "test_highly_nested_data_" + randomNameSuffix();

        assertUpdate("CREATE TABLE " + tableName + " (id INT, row1_t ROW(f1 INT, f2 INT, row2_t ROW (f1 INT, f2 INT, row3_t ROW(f1 INT, f2 INT))))");
        assertUpdate("INSERT INTO " + tableName + " VALUES (1, ROW(2, 3, ROW(4, 5, ROW(6, 7)))), (11, ROW(12, 13, ROW(14, 15, ROW(16, 17))))", 2);
        assertUpdate("INSERT INTO " + tableName + " VALUES (21, ROW(22, 23, ROW(24, 25, ROW(26, 27))))", 1);

        // Test select projected columns, with and without their parent column
        assertQuery("SELECT id, row1_t.row2_t.row3_t.f2 FROM " + tableName, "VALUES (1, 7), (11, 17), (21, 27)");
        assertQuery("SELECT id, row1_t.row2_t.row3_t.f2, CAST(row1_t AS JSON) FROM " + tableName,
                "VALUES (1, 7, '{\"f1\":2,\"f2\":3,\"row2_t\":{\"f1\":4,\"f2\":5,\"row3_t\":{\"f1\":6,\"f2\":7}}}'), " +
                        "(11, 17, '{\"f1\":12,\"f2\":13,\"row2_t\":{\"f1\":14,\"f2\":15,\"row3_t\":{\"f1\":16,\"f2\":17}}}'), " +
                        "(21, 27, '{\"f1\":22,\"f2\":23,\"row2_t\":{\"f1\":24,\"f2\":25,\"row3_t\":{\"f1\":26,\"f2\":27}}}')");

        // Test predicates on immediate child column and deeper nested column
        assertQuery("SELECT id, CAST(row1_t.row2_t.row3_t AS JSON) FROM " + tableName + " WHERE row1_t.row2_t.row3_t.f2 = 27", "VALUES (21, '{\"f1\":26,\"f2\":27}')");
        assertQuery("SELECT id, CAST(row1_t.row2_t.row3_t AS JSON) FROM " + tableName + " WHERE row1_t.row2_t.row3_t.f2 > 20", "VALUES (21, '{\"f1\":26,\"f2\":27}')");
        assertQuery("SELECT id, CAST(row1_t AS JSON) FROM " + tableName + " WHERE row1_t.row2_t.row3_t.f2 = 27",
                "VALUES (21, '{\"f1\":22,\"f2\":23,\"row2_t\":{\"f1\":24,\"f2\":25,\"row3_t\":{\"f1\":26,\"f2\":27}}}')");
        assertQuery("SELECT id, CAST(row1_t AS JSON) FROM " + tableName + " WHERE row1_t.row2_t.row3_t.f2 > 20",
                "VALUES (21, '{\"f1\":22,\"f2\":23,\"row2_t\":{\"f1\":24,\"f2\":25,\"row3_t\":{\"f1\":26,\"f2\":27}}}')");

        // Test predicates on parent columns
        assertQuery("SELECT id, row1_t.row2_t.row3_t.f1 FROM " + tableName + " WHERE row1_t.row2_t.row3_t = ROW(16, 17)", "VALUES (11, 16)");
        assertQuery("SELECT id, row1_t.row2_t.row3_t.f1 FROM " + tableName + " WHERE row1_t = ROW(22, 23, ROW(24, 25, ROW(26, 27)))", "VALUES (21, 26)");

        // Explain highly nested select
        assertExplain(
                "EXPLAIN SELECT id, row1_t.row2_t.row3_t.f1, row1_t.row2_t.f1, row1_t.row2_t.row3_t.f2 FROM " + tableName + " WHERE row1_t.row2_t.row3_t.f2 = 27",
                "ScanFilter\\[table = (.*), filterPredicate = \\(\"row1_t#row2_t#row3_t#f2\" = 27\\)]",
                "id(.*) := id:integer:REGULAR",
                "row1_t#row2_t#f1 := row1_t#row2_t#f1:integer:REGULAR",
                "row1_t#row2_t#row3_t#f1 := row1_t#row2_t#row3_t#f1:integer:REGULAR",
                "row1_t#row2_t#row3_t#f2 := row1_t#row2_t#row3_t#f2:integer:REGULAR");

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testProjectionPushdownReadsLessData()
    {
        // TODO consider moving this in BaseConnectorTest
        String tableName = "test_projection_pushdown_reads_less_data_" + randomNameSuffix();

        assertUpdate("CREATE TABLE " + tableName + " (id INT, root ROW(leaf1 BIGINT, leaf2 BIGINT))");
        assertUpdate("INSERT INTO " + tableName + " SELECT val, ROW(val + 1, val + 2) FROM UNNEST(SEQUENCE(1, 10)) AS t(val)", 10);

        MaterializedResult expectedResult = computeActual("SELECT val + 2 FROM UNNEST(SEQUENCE(1, 10)) AS t(val)");
        String selectQuery = "SELECT root.leaf2 FROM " + tableName;
        Session sessionWithoutPushdown = Session.builder(getSession())
                .setCatalogSessionProperty(getSession().getCatalog().orElseThrow(), "projection_pushdown_enabled", "false")
                .build();

        assertQueryStats(
                getSession(),
                selectQuery,
                statsWithPushdown -> {
                    DataSize physicalInputDataSizeWithPushdown = statsWithPushdown.getPhysicalInputDataSize();
                    DataSize processedDataSizeWithPushdown = statsWithPushdown.getProcessedInputDataSize();
                    assertQueryStats(
                            sessionWithoutPushdown,
                            selectQuery,
                            statsWithoutPushdown -> {
                                assertThat(statsWithoutPushdown.getPhysicalInputDataSize()).isGreaterThan(physicalInputDataSizeWithPushdown);
                                assertThat(statsWithoutPushdown.getProcessedInputDataSize()).isGreaterThan(processedDataSizeWithPushdown);
                            },
                            results -> assertEquals(results.getOnlyColumnAsSet(), expectedResult.getOnlyColumnAsSet()));
                },
                results -> assertEquals(results.getOnlyColumnAsSet(), expectedResult.getOnlyColumnAsSet()));

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testProjectionPushdownPhysicalInputSize()
    {
        // TODO consider moving this in BaseConnectorTest
        String tableName = "test_projection_pushdown_physical_input_size_" + randomNameSuffix();

        assertUpdate("CREATE TABLE " + tableName + " (id INT, root ROW(leaf1 BIGINT, leaf2 BIGINT))");
        assertUpdate("INSERT INTO " + tableName + " SELECT val, ROW(val + 1, val + 2) FROM UNNEST(SEQUENCE(1, 10)) AS t(val)", 10);

        // Verify that the physical input size is smaller when reading the root.leaf1 field compared to reading the root field
        assertQueryStats(
                getSession(),
                "SELECT root FROM " + tableName,
                statsWithSelectRootField -> {
                    assertQueryStats(
                            getSession(),
                            "SELECT root.leaf1 FROM " + tableName,
                            statsWithSelectLeafField -> {
                                assertThat(statsWithSelectLeafField.getPhysicalInputDataSize()).isLessThan(statsWithSelectRootField.getPhysicalInputDataSize());
                            },
                            results -> assertEquals(results.getOnlyColumnAsSet(), computeActual("SELECT val + 1 FROM UNNEST(SEQUENCE(1, 10)) AS t(val)").getOnlyColumnAsSet()));
                },
                results -> assertEquals(results.getOnlyColumnAsSet(), computeActual("SELECT ROW(val + 1, val + 2) FROM UNNEST(SEQUENCE(1, 10)) AS t(val)").getOnlyColumnAsSet()));

        // Verify that the physical input size is the same when reading the root field compared to reading both the root and root.leaf1 fields
        assertQueryStats(
                getSession(),
                "SELECT root FROM " + tableName,
                statsWithSelectRootField -> {
                    assertQueryStats(
                            getSession(),
                            "SELECT root, root.leaf1 FROM " + tableName,
                            statsWithSelectRootAndLeafField -> {
                                assertThat(statsWithSelectRootAndLeafField.getPhysicalInputDataSize()).isEqualTo(statsWithSelectRootField.getPhysicalInputDataSize());
                            },
                            results -> assertEqualsIgnoreOrder(results.getMaterializedRows(), computeActual("SELECT ROW(val + 1, val + 2), val + 1 FROM UNNEST(SEQUENCE(1, 10)) AS t(val)").getMaterializedRows()));
                },
                results -> assertEquals(results.getOnlyColumnAsSet(), computeActual("SELECT ROW(val + 1, val + 2) FROM UNNEST(SEQUENCE(1, 10)) AS t(val)").getOnlyColumnAsSet()));

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testProjectionPushdownColumnReorderInSchemaAndDataFile()
    {
        try (TestTable testTable = new TestTable(getQueryRunner()::execute,
                "test_projection_pushdown_column_reorder_",
                "(id BIGINT, nested1 ROW(a BIGINT, b VARCHAR, c INT), nested2 ROW(d DOUBLE, e BOOLEAN, f DATE))")) {
            assertUpdate("INSERT INTO " + testTable.getName() + " VALUES (100, ROW(10, 'a', 100), ROW(10.10, true, DATE '2023-04-19'))", 1);
            String tableDataFile = ((String) computeScalar("SELECT \"$path\" FROM " + testTable.getName()))
                    .replaceFirst("s3://" + BUCKET_NAME, "");

            try (TestTable temporaryTable = new TestTable(
                    getQueryRunner()::execute,
                    "test_projection_pushdown_column_reorder_temporary_",
                    "(nested2 ROW(d DOUBLE, e BOOLEAN, f DATE), id BIGINT, nested1 ROW(a BIGINT, b VARCHAR, c INT))")) {
                assertUpdate("INSERT INTO " + temporaryTable.getName() + " VALUES (ROW(10.10, true, DATE '2023-04-19'), 100, ROW(10, 'a', 100))", 1);

                String temporaryDataFile = ((String) computeScalar("SELECT \"$path\" FROM " + temporaryTable.getName()))
                        .replaceFirst("s3://" + BUCKET_NAME, "");

                // Replace table1 data file with table2 data file, so that the table's schema and data's schema has different column order
                hiveMinioDataLake.getMinioClient().copyObject(BUCKET_NAME, temporaryDataFile, BUCKET_NAME, tableDataFile);
            }

            assertThat(query("SELECT nested2.e, nested1.a, nested2.f, nested1.b, id FROM " + testTable.getName()))
                    .isFullyPushedDown();
        }
    }

    @Test
    public void testProjectionPushdownExplain()
    {
        String tableName = "test_projection_pushdown_explain_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (id BIGINT, root ROW(f1 BIGINT, f2 BIGINT)) WITH (partitioned_by = ARRAY['id'])");

        assertExplain(
                "EXPLAIN SELECT root.f2 FROM " + tableName,
                "TableScan\\[table = (.*)]",
                "root#f2 := root#f2:bigint:REGULAR");

        Session sessionWithoutPushdown = Session.builder(getSession())
                .setCatalogSessionProperty(getSession().getCatalog().orElseThrow(), "projection_pushdown_enabled", "false")
                .build();
        assertExplain(
                sessionWithoutPushdown,
                "EXPLAIN SELECT root.f2 FROM " + tableName,
                "ScanProject\\[table = (.*)]",
                "expr := \"root\"\\[2]",
                "root := root:row\\(f1 bigint, f2 bigint\\):REGULAR");

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testProjectionPushdownNonPrimitiveTypeExplain()
    {
        String tableName = "test_projection_pushdown_non_primtive_type_" + randomNameSuffix();

        assertUpdate("CREATE TABLE " + tableName +
                " (id BIGINT, _row ROW(child BIGINT), _array ARRAY(ROW(child BIGINT)), _map MAP(BIGINT, BIGINT))");

        assertExplain(
                "EXPLAIN SELECT id, _row.child, _array[1].child, _map[1] FROM " + tableName,
                "ScanProject\\[table = (.*)]",
                "expr(.*) := \"_array(.*)\"\\[BIGINT '1']\\[1]",
                "id(.*) := id:bigint:REGULAR",
                // _array:array\\(row\\(child bigint\\)\\) is a symbol name, not a dereference expression.
                "_array(.*) := _array:array\\(row\\(child bigint\\)\\):REGULAR",
                "_map(.*) := _map:map\\(bigint, bigint\\):REGULAR",
                "_row#child := _row#child:bigint:REGULAR");
    }

    @Test
    public void testReadCdfChanges()
    {
        String tableName = "test_basic_operations_on_table_with_cdf_enabled_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (page_url VARCHAR, domain VARCHAR, views INTEGER) WITH (change_data_feed_enabled = true)");
        assertUpdate("INSERT INTO " + tableName + " VALUES('url1', 'domain1', 1), ('url2', 'domain2', 2), ('url3', 'domain3', 3)", 3);
        assertUpdate("INSERT INTO " + tableName + " VALUES('url4', 'domain4', 4), ('url5', 'domain5', 2), ('url6', 'domain6', 6)", 3);

        assertUpdate("UPDATE " + tableName + " SET page_url = 'url22' WHERE views = 2", 2);
        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "'))",
                """
                        VALUES
                            ('url1', 'domain1', 1, 'insert', BIGINT '1'),
                            ('url2', 'domain2', 2, 'insert', BIGINT '1'),
                            ('url3', 'domain3', 3, 'insert', BIGINT '1'),
                            ('url4', 'domain4', 4, 'insert', BIGINT '2'),
                            ('url5', 'domain5', 2, 'insert', BIGINT '2'),
                            ('url6', 'domain6', 6, 'insert', BIGINT '2'),
                            ('url2', 'domain2', 2, 'update_preimage', BIGINT '3'),
                            ('url22', 'domain2', 2, 'update_postimage', BIGINT '3'),
                            ('url5', 'domain5', 2, 'update_preimage', BIGINT '3'),
                            ('url22', 'domain5', 2, 'update_postimage', BIGINT '3')
                        """);

        assertUpdate("DELETE FROM " + tableName + " WHERE views = 2", 2);
        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "', 3))",
                """
                        VALUES
                            ('url22', 'domain2', 2, 'delete', BIGINT '4'),
                            ('url22', 'domain5', 2, 'delete', BIGINT '4')
                        """);

        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "')) ORDER BY _commit_version, _change_type, domain",
                """
                        VALUES
                            ('url1', 'domain1', 1, 'insert', BIGINT '1'),
                            ('url2', 'domain2', 2, 'insert', BIGINT '1'),
                            ('url3', 'domain3', 3, 'insert', BIGINT '1'),
                            ('url4', 'domain4', 4, 'insert', BIGINT '2'),
                            ('url5', 'domain5', 2, 'insert', BIGINT '2'),
                            ('url6', 'domain6', 6, 'insert', BIGINT '2'),
                            ('url22', 'domain2', 2, 'update_postimage', BIGINT '3'),
                            ('url22', 'domain5', 2, 'update_postimage', BIGINT '3'),
                            ('url2', 'domain2', 2, 'update_preimage', BIGINT '3'),
                            ('url5', 'domain5', 2, 'update_preimage', BIGINT '3'),
                            ('url22', 'domain2', 2, 'delete', BIGINT '4'),
                            ('url22', 'domain5', 2, 'delete', BIGINT '4')
                        """);
    }

    @Test
    public void testReadCdfChangesOnPartitionedTable()
    {
        String tableName = "test_basic_operations_on_table_with_cdf_enabled_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (page_url VARCHAR, domain VARCHAR, views INTEGER) WITH (change_data_feed_enabled = true, partitioned_by = ARRAY['domain'])");
        assertUpdate("INSERT INTO " + tableName + " VALUES('url1', 'domain1', 1), ('url2', 'domain2', 2), ('url3', 'domain1', 3)", 3);
        assertUpdate("INSERT INTO " + tableName + " VALUES('url4', 'domain1', 400), ('url5', 'domain2', 500), ('url6', 'domain3', 2)", 3);

        assertUpdate("UPDATE " + tableName + " SET domain = 'domain4' WHERE views = 2", 2);
        assertQuery("SELECT * FROM " + tableName, "" +
                        """
                            VALUES
                                ('url1', 'domain1', 1),
                                ('url2', 'domain4', 2),
                                ('url3', 'domain1', 3),
                                ('url4', 'domain1', 400),
                                ('url5', 'domain2', 500),
                                ('url6', 'domain4', 2)
                        """);

        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "'))",
                """
                        VALUES
                            ('url1', 'domain1', 1, 'insert', BIGINT '1'),
                            ('url2', 'domain2', 2, 'insert', BIGINT '1'),
                            ('url3', 'domain1', 3, 'insert', BIGINT '1'),
                            ('url4', 'domain1', 400, 'insert', BIGINT '2'),
                            ('url5', 'domain2', 500, 'insert', BIGINT '2'),
                            ('url6', 'domain3', 2, 'insert', BIGINT '2'),
                            ('url2', 'domain2', 2, 'update_preimage', BIGINT '3'),
                            ('url2', 'domain4', 2, 'update_postimage', BIGINT '3'),
                            ('url6', 'domain3', 2, 'update_preimage', BIGINT '3'),
                            ('url6', 'domain4', 2, 'update_postimage', BIGINT '3')
                        """);

        assertUpdate("DELETE FROM " + tableName + " WHERE domain = 'domain4'", 2);
        assertQuery("SELECT * FROM " + tableName,
                        """
                            VALUES
                                ('url1', 'domain1', 1),
                                ('url3', 'domain1', 3),
                                ('url4', 'domain1', 400),
                                ('url5', 'domain2', 500)
                        """);
        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "', 3))",
                """
                        VALUES
                            ('url2', 'domain4', 2, 'delete', BIGINT '4'),
                            ('url6', 'domain4', 2, 'delete', BIGINT '4')
                        """);
    }

    @Test
    public void testReadMergeChanges()
    {
        String tableName1 = "test_basic_operations_on_table_with_cdf_enabled_merge_into_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName1 + " (page_url VARCHAR, domain VARCHAR, views INTEGER) WITH (change_data_feed_enabled = true)");
        assertUpdate("INSERT INTO " + tableName1 + " VALUES('url1', 'domain1', 1), ('url2', 'domain2', 2), ('url3', 'domain3', 3), ('url4', 'domain4', 4)", 4);

        String tableName2 = "test_basic_operations_on_table_with_cdf_enabled_merge_from_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName2 + " (page_url VARCHAR, domain VARCHAR, views INTEGER)");
        assertUpdate("INSERT INTO " + tableName2 + " VALUES('url1', 'domain10', 10), ('url2', 'domain20', 20), ('url5', 'domain5', 50)", 3);
        assertUpdate("INSERT INTO " + tableName2 + " VALUES('url4', 'domain40', 40)", 1);

        assertUpdate("MERGE INTO " + tableName1 + " tableWithCdf USING " + tableName2 + " source " +
                "ON (tableWithCdf.page_url = source.page_url) " +
                "WHEN MATCHED AND tableWithCdf.views > 1 " +
                "THEN UPDATE SET views = (tableWithCdf.views + source.views) " +
                "WHEN MATCHED AND tableWithCdf.views <= 1 " +
                "THEN DELETE " +
                "WHEN NOT MATCHED " +
                "THEN INSERT (page_url, domain, views) VALUES (source.page_url, source.domain, source.views)", 4);

        assertQuery("SELECT * FROM " + tableName1,
                        """
                            VALUES
                                ('url2', 'domain2', 22),
                                ('url3', 'domain3', 3),
                                ('url4', 'domain4', 44),
                                ('url5', 'domain5', 50)
                        """);
        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName1 + "', 0))",
                """
                        VALUES
                            ('url1', 'domain1', 1, 'insert', BIGINT '1'),
                            ('url2', 'domain2', 2, 'insert', BIGINT '1'),
                            ('url3', 'domain3', 3, 'insert', BIGINT '1'),
                            ('url4', 'domain4', 4, 'insert', BIGINT '1'),
                            ('url4', 'domain4', 4, 'update_preimage', BIGINT '2'),
                            ('url4', 'domain4', 44, 'update_postimage', BIGINT '2'),
                            ('url2', 'domain2', 2, 'update_preimage', BIGINT '2'),
                            ('url2', 'domain2', 22, 'update_postimage', BIGINT '2'),
                            ('url1', 'domain1', 1, 'delete', BIGINT '2'),
                            ('url5', 'domain5', 50, 'insert', BIGINT '2')
                        """);
    }

    @Test
    public void testReadMergeChangesOnPartitionedTable()
    {
        String targetTable = "test_basic_operations_on_partitioned_table_with_cdf_enabled_target_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + targetTable + " (page_url VARCHAR, domain VARCHAR, views INTEGER) WITH (change_data_feed_enabled = true, partitioned_by = ARRAY['domain'])");
        assertUpdate("INSERT INTO " + targetTable + " VALUES('url1', 'domain1', 1), ('url2', 'domain2', 2), ('url3', 'domain3', 3), ('url4', 'domain1', 4)", 4);

        String sourceTable1 = "test_basic_operations_on_partitioned_table_with_cdf_enabled_source_1_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + sourceTable1 + " (page_url VARCHAR, domain VARCHAR, views INTEGER)");
        assertUpdate("INSERT INTO " + sourceTable1 + " VALUES('url1', 'domain1', 10), ('url2', 'domain2', 20), ('url5', 'domain3', 5)", 3);
        assertUpdate("INSERT INTO " + sourceTable1 + " VALUES('url4', 'domain2', 40)", 1);

        assertUpdate("MERGE INTO " + targetTable + " target USING " + sourceTable1 + " source " +
                "ON (target.page_url = source.page_url) " +
                "WHEN MATCHED AND target.views > 2 " +
                "THEN UPDATE SET views = (target.views + source.views) " +
                "WHEN MATCHED AND target.views <= 2 " +
                "THEN DELETE " +
                "WHEN NOT MATCHED " +
                "THEN INSERT (page_url, domain, views) VALUES (source.page_url, source.domain, source.views)", 4);

        assertQuery("SELECT * FROM " + targetTable,
                        """
                        VALUES
                            ('url3', 'domain3', 3),
                            ('url4', 'domain1', 44),
                            ('url5', 'domain3', 5)
                        """);

        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + targetTable + "'))",
                """
                        VALUES
                            ('url1', 'domain1', 1, 'insert', 1),
                            ('url2', 'domain2', 2, 'insert', BIGINT '1'),
                            ('url3', 'domain3', 3, 'insert', BIGINT '1'),
                            ('url4', 'domain1', 4, 'insert', BIGINT '1'),
                            ('url1', 'domain1', 1, 'delete', BIGINT '2'),
                            ('url2', 'domain2', 2, 'delete', BIGINT '2'),
                            ('url4', 'domain1', 4, 'update_preimage', BIGINT '2'),
                            ('url4', 'domain1', 44, 'update_postimage', BIGINT '2'),
                            ('url5', 'domain3', 5, 'insert', BIGINT '2')
                        """);

        String sourceTable2 = "test_basic_operations_on_partitioned_table_with_cdf_enabled_source_1_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + sourceTable2 + " (page_url VARCHAR, domain VARCHAR, views INTEGER)");
        assertUpdate("INSERT INTO " + sourceTable2 +
                " VALUES('url3', 'domain1', 300), ('url4', 'domain2', 400), ('url5', 'domain3', 500), ('url6', 'domain1', 600)", 4);

        assertUpdate("MERGE INTO " + targetTable + " target USING " + sourceTable2 + " source " +
                "ON (target.page_url = source.page_url) " +
                "WHEN MATCHED AND target.views > 3 " +
                "THEN UPDATE SET domain = source.domain, views = (source.views + target.views) " +
                "WHEN MATCHED AND target.views <= 3 " +
                "THEN DELETE " +
                "WHEN NOT MATCHED " +
                "THEN INSERT (page_url, domain, views) VALUES (source.page_url, source.domain, source.views)", 4);

        assertQuery("SELECT * FROM " + targetTable,
                 """
                 VALUES
                    ('url4', 'domain2', 444),
                    ('url5', 'domain3', 505),
                    ('url6', 'domain1', 600)
                 """);

        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + targetTable + "', 2))",
                """
                        VALUES
                            ('url3', 'domain3', 3, 'delete', BIGINT '3'),
                            ('url4', 'domain1', 44, 'update_preimage', BIGINT '3'),
                            ('url4', 'domain2', 444, 'update_postimage', BIGINT '3'),
                            ('url5', 'domain3', 5, 'update_preimage', BIGINT '3'),
                            ('url5', 'domain3', 505, 'update_postimage', BIGINT '3'),
                            ('url6', 'domain1', 600, 'insert', BIGINT '3')
                        """);
    }

    @Test
    public void testCdfCommitTimestamp()
    {
        String tableName = "test_cdf_commit_timestamp_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (page_url VARCHAR, domain VARCHAR, views INTEGER) WITH (change_data_feed_enabled = true)");
        assertUpdate("INSERT INTO " + tableName + " VALUES('url1', 'domain1', 1)", 1);
        ZonedDateTime historyCommitTimestamp = (ZonedDateTime) computeScalar("SELECT timestamp FROM \"" + tableName + "$history\" WHERE version = 1");
        ZonedDateTime tableChangesCommitTimestamp = (ZonedDateTime) computeScalar("SELECT _commit_timestamp FROM TABLE(system.table_changes('test_schema', '" + tableName + "', 0)) WHERE _commit_Version = 1");
        assertThat(historyCommitTimestamp).isEqualTo(tableChangesCommitTimestamp);
    }

    @Test
    public void testReadDifferentChangeRanges()
    {
        String tableName = "test_reading_ranges_of_changes_on_table_with_cdf_enabled_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (page_url VARCHAR, domain VARCHAR, views INTEGER) WITH (change_data_feed_enabled = true)");
        assertQueryReturnsEmptyResult("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "'))");
        assertUpdate("INSERT INTO " + tableName + " VALUES('url1', 'domain1', 1)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES('url2', 'domain2', 2)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES('url3', 'domain3', 3)", 1);
        assertQueryReturnsEmptyResult("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "', 3))");

        assertUpdate("UPDATE " + tableName + " SET page_url = 'url22' WHERE domain = 'domain2'", 1);
        assertUpdate("UPDATE " + tableName + " SET page_url = 'url33' WHERE views = 3", 1);
        assertUpdate("DELETE FROM " + tableName + " WHERE page_url = 'url1'", 1);

        assertQuery("SELECT * FROM " + tableName,
                """
                 VALUES
                    ('url22', 'domain2', 2),
                    ('url33', 'domain3', 3)
                 """);

        assertQueryFails("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "', 1000))",
                "since_version: 1000 is higher then current table version: 6");
        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "', 0))",
                """
                        VALUES
                            ('url1', 'domain1', 1, 'insert', BIGINT '1'),
                            ('url2', 'domain2', 2, 'insert', BIGINT '2'),
                            ('url3', 'domain3', 3, 'insert', BIGINT '3'),
                            ('url2', 'domain2', 2, 'update_preimage', BIGINT '4'),
                            ('url22', 'domain2', 2, 'update_postimage', BIGINT '4'),
                            ('url3', 'domain3', 3, 'update_preimage', BIGINT '5'),
                            ('url33', 'domain3', 3, 'update_postimage', BIGINT '5'),
                            ('url1', 'domain1', 1, 'delete', BIGINT '6')
                        """);

        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "'))",
                """
                        VALUES
                            ('url1', 'domain1', 1, 'insert', BIGINT '1'),
                            ('url2', 'domain2', 2, 'insert', BIGINT '2'),
                            ('url3', 'domain3', 3, 'insert', BIGINT '3'),
                            ('url2', 'domain2', 2, 'update_preimage', BIGINT '4'),
                            ('url22', 'domain2', 2, 'update_postimage', BIGINT '4'),
                            ('url3', 'domain3', 3, 'update_preimage', BIGINT '5'),
                            ('url33', 'domain3', 3, 'update_postimage', BIGINT '5'),
                            ('url1', 'domain1', 1, 'delete', BIGINT '6')
                        """);

        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "', 3))",
                """
                        VALUES
                            ('url2', 'domain2', 2, 'update_preimage', BIGINT '4'),
                            ('url22', 'domain2', 2, 'update_postimage', BIGINT '4'),
                            ('url3', 'domain3', 3, 'update_preimage', BIGINT '5'),
                            ('url33', 'domain3', 3, 'update_postimage', BIGINT '5'),
                            ('url1', 'domain1', 1, 'delete', BIGINT '6')
                        """);

        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "', 5))",
                "VALUES ('url1', 'domain1', 1, 'delete', BIGINT '6')");
        assertQueryFails("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "', 10))", "since_version: 10 is higher then current table version: 6");
    }

    @Test
    public void testReadChangesOnTableWithColumnAdded()
    {
        String tableName = "test_reading_changes_on_table_with_columns_added_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (page_url VARCHAR, domain VARCHAR, views INTEGER) WITH (change_data_feed_enabled = true)");
        assertUpdate("INSERT INTO " + tableName + " VALUES('url1', 'domain1', 1)", 1);
        assertUpdate("ALTER TABLE " + tableName + " ADD COLUMN company VARCHAR");
        assertUpdate("INSERT INTO " + tableName + " VALUES('url2', 'domain2', 2, 'starburst')", 1);

        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "'))",
                """
                        VALUES
                            ('url1', 'domain1', 1, null, 'insert', BIGINT '1'),
                            ('url2', 'domain2', 2, 'starburst', 'insert', BIGINT '3')
                        """);
    }

    @Test
    public void testReadChangesOnTableWithRowColumn()
    {
        String tableName = "test_reading_changes_on_table_with_columns_added_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (page_url VARCHAR, costs ROW(month VARCHAR, amount BIGINT)) WITH (change_data_feed_enabled = true)");
        assertUpdate("INSERT INTO " + tableName + " VALUES('url1', ROW('01', 11))", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES('url2', ROW('02', 19))", 1);
        assertUpdate("UPDATE " + tableName + " SET costs = ROW('02', 37) WHERE costs.month = '02'", 1);

        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "'))",
                """
                        VALUES
                            ('url1', ROW('01', BIGINT '11') , 'insert', BIGINT '1'),
                            ('url2', ROW('02', BIGINT '19') , 'insert', BIGINT '2'),
                            ('url2', ROW('02', BIGINT '19') , 'update_preimage', BIGINT '3'),
                            ('url2', ROW('02', BIGINT '37') , 'update_postimage', BIGINT '3')
                        """);

        assertThat(query("SELECT costs.month, costs.amount, _commit_version FROM TABLE(system.table_changes('test_schema', '" + tableName + "'))"))
                .matches("""
                        VALUES
                            (VARCHAR '01', BIGINT '11', BIGINT '1'),
                            (VARCHAR '02', BIGINT '19', BIGINT '2'),
                            (VARCHAR '02', BIGINT '19', BIGINT '3'),
                            (VARCHAR '02', BIGINT '37', BIGINT '3')
                        """);
    }

    @Test
    public void testCdfOnTableWhichDoesntHaveItEnabledInitially()
    {
        String tableName = "test_cdf_on_table_without_it_initially_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (page_url VARCHAR, domain VARCHAR, views INTEGER)");
        assertUpdate("INSERT INTO " + tableName + " VALUES('url1', 'domain1', 1)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES('url2', 'domain2', 2)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES('url3', 'domain3', 3)", 1);
        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "', 0))",
                """
                        VALUES
                            ('url1', 'domain1', 1, 'insert', BIGINT '1'),
                            ('url2', 'domain2', 2, 'insert', BIGINT '2'),
                            ('url3', 'domain3', 3, 'insert', BIGINT '3')
                        """);

        assertUpdate("UPDATE " + tableName + " SET page_url = 'url22' WHERE domain = 'domain2'", 1);
        assertQuerySucceeds("ALTER TABLE " + tableName + " SET PROPERTIES change_data_feed_enabled = true");
        assertUpdate("UPDATE " + tableName + " SET page_url = 'url33' WHERE views = 3", 1);
        assertUpdate("DELETE FROM " + tableName + " WHERE page_url = 'url1'", 1);

        assertQuery("SELECT * FROM " + tableName,
                """
                 VALUES
                    ('url22', 'domain2', 2),
                    ('url33', 'domain3', 3)
                 """);

        assertQueryFails("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "', 3))",
                "Change Data Feed is not enabled at version 4. Version contains 'remove' entries without 'cdc' entries");
        assertQueryFails("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "'))",
                "Change Data Feed is not enabled at version 4. Version contains 'remove' entries without 'cdc' entries");
        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "', 5))",
                """
                        VALUES
                            ('url3', 'domain3', 3, 'update_preimage', BIGINT '6'),
                            ('url33', 'domain3', 3, 'update_postimage', BIGINT '6'),
                            ('url1', 'domain1', 1, 'delete', BIGINT '7')
                        """);
    }

    @Test
    public void testReadChangesFromCtasTable()
    {
        String tableName = "test_basic_operations_on_table_with_cdf_enabled_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName + " WITH (change_data_feed_enabled = true) AS SELECT * FROM (VALUES" +
                "('url1', 'domain1', 1), " +
                "('url2', 'domain2', 2)) t(page_url, domain, views)",
                2);

        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "'))",
                """
                        VALUES
                            ('url1', 'domain1', 1, 'insert', BIGINT '0'),
                            ('url2', 'domain2', 2, 'insert', BIGINT '0')
                        """);
    }

    @Test
    public void testVacuumDeletesCdfFiles()
            throws InterruptedException
    {
        String tableName = "test_vacuum_correctly_deletes_cdf_files_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (page_url VARCHAR, domain VARCHAR, views INTEGER) WITH (change_data_feed_enabled = true)");
        assertUpdate("INSERT INTO " + tableName + " VALUES('url1', 'domain1', 1), ('url3', 'domain3', 3), ('url2', 'domain2', 2)", 3);
        assertUpdate("UPDATE " + tableName + " SET views = views * 10 WHERE views = 1", 1);
        assertUpdate("UPDATE " + tableName + " SET views = views * 10 WHERE views = 2", 1);
        Stopwatch timeSinceUpdate = Stopwatch.createStarted();
        Thread.sleep(2000);
        assertUpdate("UPDATE " + tableName + " SET views = views * 30 WHERE views = 3", 1);
        Session sessionWithShortRetentionUnlocked = Session.builder(getSession())
                .setCatalogSessionProperty(getSession().getCatalog().orElseThrow(), "vacuum_min_retention", "0s")
                .build();
        Set<String> allFilesFromCdfDirectory = getAllFilesFromCdfDirectory(tableName);
        assertThat(allFilesFromCdfDirectory).hasSizeGreaterThanOrEqualTo(3);
        long retention = timeSinceUpdate.elapsed().getSeconds();
        getQueryRunner().execute(sessionWithShortRetentionUnlocked, "CALL delta.system.vacuum('test_schema', '" + tableName + "', '" + retention + "s')");
        allFilesFromCdfDirectory = getAllFilesFromCdfDirectory(tableName);
        assertThat(allFilesFromCdfDirectory).hasSizeBetween(1, 2);
        assertQueryFails("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "', 2))", ".*File does not exist.*");
        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "', 3))",
                """
                        VALUES
                            ('url3', 'domain3', 3, 'update_preimage', BIGINT '4'),
                            ('url3', 'domain3', 90, 'update_postimage', BIGINT '4')
                        """);
    }

    @Test
    public void testCdfWithOptimize()
    {
        String tableName = "test_cdf_with_optimize_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (page_url VARCHAR, domain VARCHAR, views INTEGER) WITH (change_data_feed_enabled = true)");
        assertUpdate("INSERT INTO " + tableName + " VALUES('url1', 'domain1', 1)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES('url2', 'domain2', 2)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES('url3', 'domain3', 3)", 1);
        assertUpdate("UPDATE " + tableName + " SET views = views * 30 WHERE views = 3", 1);
        computeActual("ALTER TABLE " + tableName + " EXECUTE OPTIMIZE");
        assertUpdate("INSERT INTO " + tableName + " VALUES('url10', 'domain10', 10)", 1);
        assertTableChangesQuery("SELECT * FROM TABLE(system.table_changes('test_schema', '" + tableName + "', 0))",
                """
                        VALUES
                            ('url1', 'domain1', 1, 'insert', BIGINT '1'),
                            ('url2', 'domain2', 2, 'insert', BIGINT '2'),
                            ('url3', 'domain3', 3, 'insert', BIGINT '3'),
                            ('url10', 'domain10', 10, 'insert', BIGINT '6'),
                            ('url3', 'domain3', 3, 'update_preimage', BIGINT '4'),
                            ('url3', 'domain3', 90, 'update_postimage', BIGINT '4')
                        """);
    }

    @Test
    public void testTableChangesAccessControl()
    {
        String tableName = "test_deny_table_changes_" + randomNameSuffix();
        assertUpdate("CREATE TABLE " + tableName + " (page_url VARCHAR, domain VARCHAR, views INTEGER) ");
        assertUpdate("INSERT INTO " + tableName + " VALUES('url1', 'domain1', 1)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES('url2', 'domain2', 2)", 1);
        assertUpdate("INSERT INTO " + tableName + " VALUES('url3', 'domain3', 3)", 1);

        assertAccessDenied(
                "SELECT * FROM TABLE(system.table_changes('" + SCHEMA + "', '" + tableName + "', 0))",
                "Cannot execute function .*",
                privilege("delta.system.table_changes", EXECUTE_FUNCTION));

        assertAccessDenied(
                "SELECT * FROM TABLE(system.table_changes('" + SCHEMA + "', '" + tableName + "', 0))",
                "Cannot select from columns .*",
                privilege(tableName, SELECT_COLUMN));

        assertUpdate("DROP TABLE " + tableName);
    }

    @Override
    protected void verifyAddNotNullColumnToNonEmptyTableFailurePermissible(Throwable e)
    {
        assertThat(e).hasMessageMatching("Unable to add NOT NULL column '.*' for non-empty table: .*");
    }

    @Override
    protected String createSchemaSql(String schemaName)
    {
        return "CREATE SCHEMA " + schemaName + " WITH (location = 's3://" + BUCKET_NAME + "/" + schemaName + "')";
    }

    @Override
    protected OptionalInt maxSchemaNameLength()
    {
        return OptionalInt.of(128);
    }

    @Override
    protected void verifySchemaNameLengthFailurePermissible(Throwable e)
    {
        assertThat(e).hasMessageMatching("(?s)(.*Read timed out)|(.*\"`NAME`\" that has maximum length of 128.*)");
    }

    @Override
    protected OptionalInt maxTableNameLength()
    {
        return OptionalInt.of(128);
    }

    @Override
    protected void verifyTableNameLengthFailurePermissible(Throwable e)
    {
        assertThat(e).hasMessageMatching("(?s)(.*Read timed out)|(.*\"`TBL_NAME`\" that has maximum length of 128.*)");
    }

    private Set<String> getActiveFiles(String tableName)
    {
        return getActiveFiles(tableName, getQueryRunner().getDefaultSession());
    }

    private Set<String> getActiveFiles(String tableName, Session session)
    {
        return computeActual(session, "SELECT DISTINCT \"$path\" FROM " + tableName).getOnlyColumnAsSet().stream()
                .map(String.class::cast)
                .collect(toImmutableSet());
    }

    private Set<String> getAllDataFilesFromTableDirectory(String tableName)
    {
        return getTableFiles(tableName).stream()
                .filter(path -> !path.contains("/" + TRANSACTION_LOG_DIRECTORY))
                .collect(toImmutableSet());
    }

    private List<String> getTableFiles(String tableName)
    {
        return hiveMinioDataLake.listFiles(format("%s/%s", SCHEMA, tableName)).stream()
                .map(path -> format("s3://%s/%s", BUCKET_NAME, path))
                .collect(toImmutableList());
    }

    private void assertTableChangesQuery(@Language("SQL") String sql, @Language("SQL") String expectedResult)
    {
        assertThat(query(sql))
                .exceptColumns("_commit_timestamp")
                .skippingTypesCheck()
                .matches(expectedResult);
    }

    private Set<String> getAllFilesFromCdfDirectory(String tableName)
    {
        return getTableFiles(tableName).stream()
                .filter(path -> path.contains("/" + CHANGE_DATA_FOLDER_NAME))
                .collect(toImmutableSet());
    }
}
