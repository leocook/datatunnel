package com.superior.datatunnel.plugin.jdbc;

import com.clearspring.analytics.util.Lists;
import com.superior.datatunnel.api.*;
import com.superior.datatunnel.api.model.DataTunnelSourceOption;
import com.superior.datatunnel.common.util.CommonUtils;
import com.superior.datatunnel.common.util.JdbcUtils;
import com.superior.datatunnel.plugin.jdbc.support.JdbcDialectUtils;
import com.superior.datatunnel.plugin.jdbc.support.dialect.DatabaseDialect;
import io.github.melin.jobserver.spark.api.LogUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.spark.sql.*;
import org.apache.spark.sql.execution.datasources.jdbc.JDBCOptions;
import org.apache.spark.sql.jdbc.JdbcDialect;
import org.apache.spark.sql.jdbc.JdbcDialects;
import org.glassfish.jersey.internal.guava.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.JavaConverters;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author melin 2021/7/27 11:06 上午O
 */
public class JdbcDataTunnelSource implements DataTunnelSource {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcDataTunnelSource.class);

    public void validateOptions(DataTunnelContext context) {
        DataSourceType dsType = context.getSourceOption().getDataSourceType();

        if (!DataSourceType.isJdbcDataSource(dsType)) {
            throw new IllegalArgumentException("不支持数据源类型: " + dsType);
        }
    }

    @Override
    public Set<String> optionalOptions() {
        Set<String> options = Sets.newHashSet();
        options.add("sessionInitStatement");
        options.add("customSchema");
        options.add("pushDownPredicate");
        options.add("pushDownAggregate");
        options.add("pushDownLimit");
        options.add("pushDownOffset");
        options.add("pushDownTableSample");
        options.add("preferTimestampNTZ");
        options.add("keytab");
        options.add("principal");
        options.add("refreshKrb5Config");
        return options;
    }

    @Override
    public Dataset<Row> read(DataTunnelContext context) throws IOException {
        validateOptions(context);

        JdbcDataTunnelSourceOption sourceOption = (JdbcDataTunnelSourceOption) context.getSourceOption();
        DataSourceType dataSourceType = sourceOption.getDataSourceType();

        String schemaName = sourceOption.getSchemaName();
        if (StringUtils.isBlank(schemaName)) {
            schemaName = sourceOption.getDatabaseName();
        }
        String tableName = sourceOption.getTableName();
        String[] columns = sourceOption.getColumns();

        String jdbcUrl = sourceOption.getJdbcUrl();
        if (StringUtils.isBlank(jdbcUrl)) {
            jdbcUrl = JdbcUtils.buildJdbcUrl(dataSourceType, sourceOption.getHost(),
                    sourceOption.getPort(), sourceOption.getDatabaseName(),
                    sourceOption.getSid(), sourceOption.getServiceName());
        }

        JDBCOptions options = buildJDBCOptions(jdbcUrl, "table", sourceOption);
        Connection connection = buildConnection(jdbcUrl, options);
        DatabaseDialect dialect = JdbcDialectUtils.getDatabaseDialect(connection, dataSourceType.name());

        List<String> schemaNames = getSchemaNames(schemaName, dialect);
        List<Pair<String, String>> tableNames = getTablesNames(schemaNames, tableName, dialect);
        if (tableNames.isEmpty()) {
            throw new DataTunnelException("没有找到匹配的表, schemaName: " + schemaName + ", tableName: " + tableName);
        }

        Dataset<Row> dataset = null;
        for (int i = 0, len = tableNames.size(); i < len; i++) {
            Pair<String, String> pair = tableNames.get(i);
            String fullTableName = pair.getLeft() + "." + pair.getRight();
            statTable(connection, sourceOption, fullTableName);

            if (columns.length > 1 || (columns.length == 1 && !"*".equals(columns[0]))) {
                String condition = StringUtils.trim(sourceOption.getCondition());
                if (StringUtils.isNotBlank(condition)) {
                    if (StringUtils.startsWithIgnoreCase(condition, "where")) {
                        fullTableName = "(SELECT " + StringUtils.join(columns, ",") + " FROM " + fullTableName + " " + condition + ") tdl_datatunnel";
                    } else {
                        fullTableName = "(SELECT " + StringUtils.join(columns, ",") + " FROM " + fullTableName + " where " + condition + ") tdl_datatunnel";
                    }
                } else {
                    fullTableName = "(SELECT " + StringUtils.join(columns, ",") + " FROM " + fullTableName + ") tdl_datatunnel";
                }
            }

            int fetchsize = sourceOption.getFetchsize();
            int queryTimeout = sourceOption.getQueryTimeout();
            String username = sourceOption.getUsername();
            String password = sourceOption.getPassword();

            DataFrameReader reader = context.getSparkSession().read()
                    .format("jdbc")
                    .options(sourceOption.getProperties())
                    .option("url", jdbcUrl)
                    .option("dbtable", fullTableName)
                    .option("fetchsize", fetchsize)
                    .option("queryTimeout", queryTimeout)
                    .option("user", username)
                    .option("password", password)
                    .option("pushDownPredicate", sourceOption.isPushDownPredicate())
                    .option("pushDownAggregate", sourceOption.isPushDownAggregate())
                    .option("pushDownLimit", sourceOption.isPushDownLimit());

            if (StringUtils.isNotBlank(sourceOption.getPartitionColumn())) {
                reader.option("partitionColumn", sourceOption.getPartitionColumn())
                        .option("numPartitions", sourceOption.getNumPartitions())
                        .option("lowerBound", sourceOption.getLowerBound())
                        .option("upperBound", sourceOption.getUpperBound());
            }

            Dataset<Row> result = reader.load();

            if (i == 0) {
                dataset = result;
            } else  {
                dataset = dataset.unionAll(result);
            }
        }

        JdbcUtils.close(connection);
        return dataset;
    }

    private List<String> getSchemaNames(String schemaName, DatabaseDialect dialect) {
        String[] names = StringUtils.split(schemaName, ",");
        List<String> schemaNames = dialect.getSchemaNames();

        List<String> list = Lists.newArrayList();
        for (String name : schemaNames) {
            for (String pattern : names) {
                String newPattern = CommonUtils.cleanQuote(pattern);
                if (name.equals(newPattern) || name.matches(newPattern)) {
                    list.add(pattern);
                    break;
                }
            }
        }
        if (list.size() == 0) {
            throw new DataTunnelException("没有找到匹配的schema: " + schemaName + ", schemas: " + StringUtils.join(schemaNames, ","));
        }
        return list;
    }

    private List<Pair<String, String>> getTablesNames(List<String> schemaNames, String tableName, DatabaseDialect dialect) {
        String[] names = StringUtils.split(tableName, ",");
        List<Pair<String, String>> list = Lists.newArrayList();
        for (String schemaName : schemaNames) {
            String schema = CommonUtils.cleanQuote(schemaName);
            List<String> tableNames = dialect.getTableNames(schema);
            for (String name : tableNames) {
                for (String pattern : names) {
                    if (name.equals(pattern) || name.matches(pattern)) {
                        list.add(Pair.of(schemaName, name));
                        break;
                    }
                }
            }
        }
        return list;
    }

    private JDBCOptions buildJDBCOptions(String url, String dbtable, JdbcDataTunnelSourceOption sourceOption) {
        Map<String, String> params = sourceOption.getParams();
        params.remove("partitionColumn");
        params.remove("lowerBound");
        params.remove("upperBound");
        params.remove("numPartitions");
        params.put("user", sourceOption.getUsername());
        return new JDBCOptions(url, dbtable, JavaConverters.mapAsScalaMapConverter(params).asScala()
                        .toMap(scala.Predef$.MODULE$.<scala.Tuple2<String, String>>conforms()));
    }

    private Connection buildConnection(String url, JDBCOptions options) {
        try {
            JdbcDialect dialect = JdbcDialects.get(url);
            return dialect.createConnectionFactory(options).apply(-1);
        } catch (Exception e) {
            String msg = "无法访问数据源: " + url + ", 失败原因: " + e.getMessage();
            throw new DataTunnelException(msg, e);
        }
    }

    private void statTable(Connection conn, JdbcDataTunnelSourceOption sourceOption, String table) {
        PreparedStatement stmt = null;
        try {
            String partitionColumn = sourceOption.getPartitionColumn();
            String lowerBound = sourceOption.getLowerBound();
            String upperBound = sourceOption.getUpperBound();
            Integer numPartitions = sourceOption.getNumPartitions();
            Integer partitionRecordCount = sourceOption.getPartitionRecordCount();

            // 如果用户指定分区参数，不需要再统计，大表统计比较耗时
            if (StringUtils.isNotBlank(partitionColumn)
                    && StringUtils.isNotBlank(lowerBound)
                    && StringUtils.isNotBlank(upperBound)) {

                return;
            }

            String sql;
            if (StringUtils.isNotBlank(partitionColumn)) {
                sql = "select count(1) as num , max(" + partitionColumn + ") max_value, min(" + partitionColumn + ") min_value " +
                        "from " + table;
            } else {
                sql = "select count(1) as num from " + table;
            }

            String condition = StringUtils.trim(sourceOption.getCondition());
            if (StringUtils.isNotBlank(condition)) {
                if (StringUtils.startsWithIgnoreCase(condition, condition)) {
                    sql = sql + " " + condition;
                } else {
                    sql = sql + " where " + condition;
                }
            }

            StopWatch stopWatch = StopWatch.createStarted();
            stmt = conn.prepareStatement(sql);
            ResultSet resultSet = stmt.executeQuery();
            resultSet.next();
            long count = Long.parseLong(resultSet.getString("num"));
            stopWatch.stop();
            String execTimes = stopWatch.formatTime();

            if (StringUtils.isBlank(partitionColumn)) {
                LOG.info("ExecTimes: {}, table {} record count: {}, set partitionColumn & numPartitions to improve running efficiency\n",
                        execTimes, table, count);
                LogUtils.warn("ExecTimes: {}, table {} record count: {}, set partitionColumn & numPartitions to improve running efficiency\n",
                        execTimes, table, count);
            } else {
                LOG.info("ExecTimes: {}, table {} record count: {}", execTimes, table, count);
                LogUtils.info("ExecTimes: {}, table {} record count: {}", execTimes, table, count);
            }

            if (StringUtils.isNotBlank(partitionColumn)) {
                if (StringUtils.isBlank(lowerBound)) {
                    String minValue = String.valueOf(resultSet.getObject("min_value"));
                    LOG.info("table {} min value: {}", table, minValue);
                    LogUtils.info("table {} min value: {}", table, minValue);
                    sourceOption.setLowerBound(minValue);
                }
                if (StringUtils.isBlank(upperBound)) {
                    String maxValue = String.valueOf(resultSet.getObject("max_value"));
                    LOG.info("table {} max value: {}", table, maxValue);
                    LogUtils.info("table {} max value: {}", table, maxValue);
                    sourceOption.setUpperBound(maxValue);
                }
            }

            if (numPartitions == null) {
                numPartitions = (int) Math.ceil((double) count / partitionRecordCount);
            }
            if (numPartitions == 0) {
                numPartitions = 1;
            }

            sourceOption.setNumPartitions(numPartitions);
            LogUtils.info("lowerBound: {}, upperBound: {}, numPartitions: {}",
                    sourceOption.getLowerBound(), sourceOption.getUpperBound(), numPartitions);
        } catch (SQLException e) {
            throw new DataTunnelException(e.getMessage(), e);
        } finally {
            JdbcUtils.close(stmt);
        }
    }

    @Override
    public Class<? extends DataTunnelSourceOption> getOptionClass() {
        return JdbcDataTunnelSourceOption.class;
    }
}
