package io.mycat.calcite;

import io.mycat.MycatConnection;
import io.mycat.api.collector.RowBaseIterator;
import io.mycat.beans.mycat.JdbcRowBaseIterator;
import io.mycat.beans.mycat.MycatRowMetaData;
import io.mycat.calcite.prepare.MycatCalcitePlanner;
import io.mycat.calcite.resultset.EnumeratorRowIterator;
import io.mycat.calcite.resultset.MyCatResultSetEnumerator;
import io.mycat.calcite.rules.StreamUnionRule;
import io.mycat.calcite.table.SingeTargetSQLTable;
import io.mycat.datasource.jdbc.JdbcRuntime;
import io.mycat.sqlRecorder.SqlRecorder;
import io.mycat.sqlRecorder.SqlRecorderRuntime;
import io.mycat.sqlRecorder.SqlRecorderType;
import io.mycat.upondb.MycatDBContext;
import io.mycat.util.TimeProvider;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;
import org.apache.calcite.interpreter.Interpreters;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.logical.LogicalUnion;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.runtime.ArrayBindable;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.tools.RelRunners;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CalciteRunners {
    private final static Logger LOGGER = LoggerFactory.getLogger(CalciteRunners.class);

    @SneakyThrows
    public static CompileRes compile(MycatCalcitePlanner planner, String sql, SqlNode sqlNode, boolean forUpdate) {
        long start = TimeProvider.INSTANCE.now();
        SqlRecorder recorder = SqlRecorderRuntime.INSTANCE.getCurrentRecorder();
        recorder.start();
        recorder.addRecord(SqlRecorderType.AT_START, sql, start);
        planner.parse();
        SqlNode validate = planner.validate(sqlNode);
        RelNode relNode = planner.rel(validate).project(true);
        RelDataType resultRowType = relNode.getRowType();
        long cro = TimeProvider.INSTANCE.now();
        recorder.addRecord(SqlRecorderType.COMPILE_SQL, sql, cro - start);
        try {
            return compile(planner, relNode, resultRowType, forUpdate);
        } finally {
            recorder.addRecord(SqlRecorderType.RBO, sql, TimeProvider.INSTANCE.now() - cro);
        }
    }

    @EqualsAndHashCode
    @Data
    @AllArgsConstructor
    public static class CompileRes {
        RelNode relNode;
        RelDataType resultRowType;
    }

    public static CompileRes compile(MycatCalcitePlanner planner, RelNode relNode, RelDataType resultRowType, boolean forUpdate) {
        try {
            relNode = Objects.requireNonNull(planner.eliminateLogicTable(relNode));
            try {
                /**
                 * 上拉union仅仅是优化不应该导致关系表达式不能执行
                 */
                relNode = Objects.requireNonNull(planner.pullUpUnion(relNode));
            } catch (Throwable e) {
                LOGGER.error("", e);
            }
            relNode = Objects.requireNonNull(planner.pushDownBySQL(relNode, forUpdate));
            return new CompileRes(Objects.requireNonNull(relNode), resultRowType);
        } catch (Throwable e) {
            LOGGER.error("", e);
            throw e;
        }
    }


    @SneakyThrows
    public static RowBaseIterator run(String sql, MycatCalciteDataContext calciteDataContext, RelNode relNode, RelDataType resultSetType) {
        SqlRecorder recorder = SqlRecorderRuntime.INSTANCE.getCurrentRecorder();
        Map<String, List<SingeTargetSQLTable>> map = new HashMap<>();
        relNode.accept(new RelShuttleImpl() {
            @Override
            public RelNode visit(TableScan scan) {
                SingeTargetSQLTable unwrap = scan.getTable().unwrap(SingeTargetSQLTable.class);
                if (unwrap != null && !unwrap.existsEnumerable()) {
                    List<SingeTargetSQLTable> tables = map.computeIfAbsent(unwrap.getTargetName(), s -> new ArrayList<>(2));
                    tables.add(unwrap);
                }
                return super.visit(scan);
            }
        });

        HepProgramBuilder hepProgramBuilder = new HepProgramBuilder();
        hepProgramBuilder.addMatchLimit(64);

        hepProgramBuilder.addRuleInstance(StreamUnionRule.INSTANCE);
        final HepPlanner planner2 = new HepPlanner(hepProgramBuilder.build());
        planner2.setRoot(relNode);
        relNode = planner2.findBestExp();

        //check
        relNode.accept(new RelShuttleImpl() {
            @Override
            public RelNode visit(LogicalUnion union) {
                if (union.getInputs().size() > 2) {
                    throw new AssertionError("union input more 2");
                }
                return super.visit(union);
            }
        });
        long startGetConnectionTime = TimeProvider.INSTANCE.now();
        fork(sql, calciteDataContext, map);
        long cbo = TimeProvider.INSTANCE.now();
        recorder.addRecord(SqlRecorderType.GET_CONNECTION, sql, cbo - startGetConnectionTime);
        MycatRowMetaData mycatRowMetaData = CalciteConvertors.getMycatRowMetaData(resultSetType != null ? resultSetType : relNode.getRowType());
        try {
            ArrayBindable bindable1 = Interpreters.bindable(relNode);
            long execution_start = TimeProvider.INSTANCE.now();
            recorder.addRecord(SqlRecorderType.CBO, sql, execution_start - cbo);
            Enumerable<Object[]> bind = bindable1.bind(calciteDataContext);
            Enumerator<Object[]> enumerator = bind.enumerator();
            return new EnumeratorRowIterator(mycatRowMetaData, enumerator,
                    () -> {
                        recorder.addRecord(SqlRecorderType.EXECUTION_TIME, sql, TimeProvider.INSTANCE.now() - execution_start);
                        recorder.addRecord(SqlRecorderType.AT_END, sql, TimeProvider.INSTANCE.now());
                    });
        } catch (Throwable e) {
            LOGGER.warn("may meet unsupported operation ",e);
            PreparedStatement preparedStatement = RelRunners.run(relNode);
            long execution_start = TimeProvider.INSTANCE.now();
            recorder.addRecord(SqlRecorderType.CBO, sql, execution_start - cbo);
            ResultSet resultSet = preparedStatement.executeQuery();
            return new JdbcRowBaseIterator(mycatRowMetaData, preparedStatement, resultSet, () -> {
                preparedStatement.close();
                recorder.addRecord(SqlRecorderType.EXECUTION_TIME, sql, TimeProvider.INSTANCE.now() - execution_start);
                recorder.addRecord(SqlRecorderType.AT_END, sql, TimeProvider.INSTANCE.now());
            }, sql);
        }
    }

    private static void fork(String sql, MycatCalciteDataContext calciteDataContext, Map<String, List<SingeTargetSQLTable>> map) throws IllegalAccessException {
        MycatDBContext uponDBContext = calciteDataContext.getUponDBContext();
        AtomicBoolean cancelFlag = uponDBContext.cancelFlag();
        SqlRecorder recorder = SqlRecorderRuntime.INSTANCE.getCurrentRecorder();

        if (uponDBContext.isInTransaction()) {
            for (Map.Entry<String, List<SingeTargetSQLTable>> entry : map.entrySet()) {
                String datasource = entry.getKey();
                List<SingeTargetSQLTable> list = entry.getValue();
                SingeTargetSQLTable table = list.get(0);
                if (table.existsEnumerable()) {
                    continue;
                }
                MycatConnection connection = uponDBContext.getConnection(datasource);
                long start = System.currentTimeMillis();
                if (list.size() > 1) {
                    throw new IllegalAccessException("事务内该执行计划重复拉取同一个数据源的数据");
                }
                Future<RowBaseIterator> submit = JdbcRuntime.INSTANCE.getFetchDataExecutorService()
                        .submit(() -> {
                            try {
                                return connection.executeQuery(table.getMetaData(), table.getSql());
                            } finally {
                                recorder.addRecord(SqlRecorderType.CONNECTION_QUERY_RESPONSE,
                                        sql, TimeProvider.INSTANCE.now() - start);
                            }
                        });
                table.setEnumerable(new AbstractEnumerable<Object[]>() {
                    @Override
                    @SneakyThrows
                    public Enumerator<Object[]> enumerator() {
                        return new MyCatResultSetEnumerator(cancelFlag, submit.get(1, TimeUnit.MINUTES));
                    }
                });
            }
        } else {
            Iterator<String> iterator = map.entrySet().stream()
                    .flatMap(i -> i.getValue().stream())
                    .filter(i -> !i.existsEnumerable())
                    .map(i -> i.getTargetName()).iterator();
            Map<String, Deque<MycatConnection>> nameMap = JdbcRuntime.INSTANCE.getConnection(iterator);
            long start = System.currentTimeMillis();
            for (Map.Entry<String, List<SingeTargetSQLTable>> entry : map.entrySet()) {
                List<SingeTargetSQLTable> value = entry.getValue();
                for (SingeTargetSQLTable v : value) {
                    MycatConnection connection = nameMap.get(v.getTargetName()).remove();
                    uponDBContext.addCloseResource(connection);
                    Future<RowBaseIterator> submit = JdbcRuntime.INSTANCE.getFetchDataExecutorService()
                            .submit(() -> {
                                try {
                                    return connection.executeQuery(v.getMetaData(), v.getSql());
                                } finally {
                                    recorder.addRecord(SqlRecorderType.CONNECTION_QUERY_RESPONSE,
                                            sql, TimeProvider.INSTANCE.now() - start);
                                }
                            });
                    AbstractEnumerable enumerable = new AbstractEnumerable<Object[]>() {
                        @Override
                        @SneakyThrows
                        public Enumerator<Object[]> enumerator() {
                            LOGGER.info("拉取数据" + v.getTargetName() + " sql:" + v.getSql());
                            return new MyCatResultSetEnumerator(cancelFlag, submit.get());
                        }
                    };
                    v.setEnumerable(enumerable);
                }
            }
        }
    }

}