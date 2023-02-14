/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.benchmark.compute.operation;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.aggregation.AggregationName;
import org.elasticsearch.compute.aggregation.AggregationType;
import org.elasticsearch.compute.aggregation.Aggregator;
import org.elasticsearch.compute.aggregation.AggregatorFunction;
import org.elasticsearch.compute.aggregation.AggregatorMode;
import org.elasticsearch.compute.aggregation.GroupingAggregator;
import org.elasticsearch.compute.aggregation.GroupingAggregatorFunction;
import org.elasticsearch.compute.aggregation.blockhash.BlockHash;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.DoubleArrayVector;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.data.IntArrayVector;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.LongArrayVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.AggregationOperator;
import org.elasticsearch.compute.operator.HashAggregationOperator;
import org.elasticsearch.compute.operator.Operator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

@Warmup(iterations = 5)
@Measurement(iterations = 7)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
public class AggregatorBenchmark {
    private static final int BLOCK_LENGTH = 8 * 1024;
    private static final int GROUPS = 5;

    private static final BigArrays BIG_ARRAYS = BigArrays.NON_RECYCLING_INSTANCE;  // TODO real big arrays?

    private static final String LONGS = "longs";
    private static final String INTS = "ints";
    private static final String DOUBLES = "doubles";
    private static final String BOOLEANS = "booleans";
    private static final String BYTES_REFS = "bytes_refs";

    private static final String VECTOR_DOUBLES = "vector_doubles";
    private static final String HALF_NULL_DOUBLES = "half_null_doubles";
    private static final String VECTOR_LONGS = "vector_" + LONGS;
    private static final String HALF_NULL_LONGS = "half_null_" + LONGS;
    private static final String MULTIVALUED_LONGS = "multivalued";

    private static final String AVG = "avg";
    private static final String COUNT = "count";
    private static final String MIN = "min";
    private static final String MAX = "max";
    private static final String SUM = "sum";

    private static final String NONE = "none";

    static {
        // Smoke test all the expected values and force loading subclasses more like prod
        try {
            for (String grouping : AggregatorBenchmark.class.getField("grouping").getAnnotationsByType(Param.class)[0].value()) {
                for (String op : AggregatorBenchmark.class.getField("op").getAnnotationsByType(Param.class)[0].value()) {
                    for (String blockType : AggregatorBenchmark.class.getField("blockType").getAnnotationsByType(Param.class)[0].value()) {
                        run(grouping, op, blockType);
                    }
                }
            }
        } catch (NoSuchFieldException e) {
            throw new AssertionError();
        }
    }

    @Param({ NONE, LONGS, INTS, DOUBLES, BOOLEANS, BYTES_REFS })
    public String grouping;

    @Param({ AVG, COUNT, MIN, MAX, SUM })
    public String op;

    @Param({ VECTOR_LONGS, HALF_NULL_LONGS, VECTOR_DOUBLES, HALF_NULL_DOUBLES })
    public String blockType;

    private static Operator operator(String grouping, AggregationName aggName, AggregationType aggType) {
        if (grouping.equals("none")) {
            AggregatorFunction.Factory factory = AggregatorFunction.of(aggName, aggType);
            return new AggregationOperator(List.of(new Aggregator(factory, AggregatorMode.SINGLE, 0)));
        }
        List<HashAggregationOperator.GroupSpec> groups = switch (grouping) {
            case LONGS -> List.of(new HashAggregationOperator.GroupSpec(0, ElementType.LONG));
            case INTS -> List.of(new HashAggregationOperator.GroupSpec(0, ElementType.INT));
            case DOUBLES -> List.of(new HashAggregationOperator.GroupSpec(0, ElementType.DOUBLE));
            case BOOLEANS -> List.of(new HashAggregationOperator.GroupSpec(0, ElementType.BOOLEAN));
            case BYTES_REFS -> List.of(new HashAggregationOperator.GroupSpec(0, ElementType.BYTES_REF));
            default -> throw new IllegalArgumentException("unsupported grouping [" + grouping + "]");
        };
        GroupingAggregatorFunction.Factory factory = GroupingAggregatorFunction.of(aggName, aggType);
        return new HashAggregationOperator(
            List.of(new GroupingAggregator.GroupingAggregatorFactory(BIG_ARRAYS, factory, AggregatorMode.SINGLE, groups.size())),
            () -> BlockHash.build(groups, BIG_ARRAYS)
        );
    }

    private static void checkExpected(String grouping, String op, String blockType, AggregationType aggType, Page page) {
        String prefix = String.format("[%s][%s][%s] ", grouping, op, blockType);
        if (grouping.equals("none")) {
            checkUngrouped(prefix, op, aggType, page);
            return;
        }
        checkGrouped(prefix, grouping, op, aggType, page);
    }

    private static void checkGrouped(String prefix, String grouping, String op, AggregationType aggType, Page page) {
        switch (grouping) {
            case LONGS -> {
                LongBlock groups = page.getBlock(0);
                for (int g = 0; g < GROUPS; g++) {
                    if (groups.getLong(g) != (long) g) {
                        throw new AssertionError(prefix + "bad group expected [" + g + "] but was [" + groups.getLong(g) + "]");
                    }
                }
            }
            case INTS -> {
                IntBlock groups = page.getBlock(0);
                for (int g = 0; g < GROUPS; g++) {
                    if (groups.getInt(g) != g) {
                        throw new AssertionError(prefix + "bad group expected [" + g + "] but was [" + groups.getInt(g) + "]");
                    }
                }
            }
            case DOUBLES -> {
                DoubleBlock groups = page.getBlock(0);
                for (int g = 0; g < GROUPS; g++) {
                    if (groups.getDouble(g) != (double) g) {
                        throw new AssertionError(prefix + "bad group expected [" + (double) g + "] but was [" + groups.getDouble(g) + "]");
                    }
                }
            }
            case BOOLEANS -> {
                BooleanBlock groups = page.getBlock(0);
                if (groups.getBoolean(0) != false) {
                    throw new AssertionError(prefix + "bad group expected [false] but was [" + groups.getBoolean(0) + "]");
                }
                if (groups.getBoolean(1) != true) {
                    throw new AssertionError(prefix + "bad group expected [true] but was [" + groups.getBoolean(1) + "]");
                }
            }
            case BYTES_REFS -> {
                BytesRefBlock groups = page.getBlock(0);
                for (int g = 0; g < GROUPS; g++) {
                    if (false == groups.getBytesRef(g, new BytesRef()).equals(bytesGroup(g))) {
                        throw new AssertionError(
                            prefix + "bad group expected [" + bytesGroup(g) + "] but was [" + groups.getBytesRef(g, new BytesRef()) + "]"
                        );
                    }
                }
            }
            default -> throw new IllegalArgumentException("bad grouping [" + grouping + "]");
        }
        Block values = page.getBlock(1);
        int groups = switch (grouping) {
            case BOOLEANS -> 2;
            default -> GROUPS;
        };
        switch (op) {
            case AVG -> {
                DoubleBlock dValues = (DoubleBlock) values;
                for (int g = 0; g < groups; g++) {
                    long group = g;
                    long sum = LongStream.range(0, BLOCK_LENGTH).filter(l -> l % groups == group).sum();
                    long count = LongStream.range(0, BLOCK_LENGTH).filter(l -> l % groups == group).count();
                    double expected = (double) sum / count;
                    if (dValues.getDouble(g) != expected) {
                        throw new AssertionError(prefix + "expected [" + expected + "] but was [" + dValues.getDouble(g) + "]");
                    }
                }
            }
            case COUNT -> {
                LongBlock lValues = (LongBlock) values;
                for (int g = 0; g < groups; g++) {
                    long group = g;
                    long expected = LongStream.range(0, BLOCK_LENGTH).filter(l -> l % groups == group).count() * 1024;
                    if (lValues.getLong(g) != expected) {
                        throw new AssertionError(prefix + "expected [" + expected + "] but was [" + lValues.getLong(g) + "]");
                    }
                }
            }
            case MIN -> {
                switch (aggType) {
                    case longs -> {
                        LongBlock lValues = (LongBlock) values;
                        for (int g = 0; g < groups; g++) {
                            if (lValues.getLong(g) != (long) g) {
                                throw new AssertionError(prefix + "expected [" + g + "] but was [" + lValues.getLong(g) + "]");
                            }
                        }
                    }
                    case doubles -> {
                        DoubleBlock dValues = (DoubleBlock) values;
                        for (int g = 0; g < groups; g++) {
                            if (dValues.getDouble(g) != (long) g) {
                                throw new AssertionError(prefix + "expected [" + g + "] but was [" + dValues.getDouble(g) + "]");
                            }
                        }
                    }
                }
            }
            case MAX -> {
                switch (aggType) {
                    case longs -> {
                        LongBlock lValues = (LongBlock) values;
                        for (int g = 0; g < groups; g++) {
                            long group = g;
                            long expected = LongStream.range(0, BLOCK_LENGTH).filter(l -> l % groups == group).max().getAsLong();
                            if (lValues.getLong(g) != expected) {
                                throw new AssertionError(prefix + "expected [" + expected + "] but was [" + lValues.getLong(g) + "]");
                            }
                        }
                    }
                    case doubles -> {
                        DoubleBlock dValues = (DoubleBlock) values;
                        for (int g = 0; g < groups; g++) {
                            long group = g;
                            long expected = LongStream.range(0, BLOCK_LENGTH).filter(l -> l % groups == group).max().getAsLong();
                            if (dValues.getDouble(g) != expected) {
                                throw new AssertionError(prefix + "expected [" + expected + "] but was [" + dValues.getDouble(g) + "]");
                            }
                        }
                    }
                }
            }
            case SUM -> {
                switch (aggType) {
                    case longs -> {
                        LongBlock lValues = (LongBlock) values;
                        for (int g = 0; g < groups; g++) {
                            long group = g;
                            long expected = LongStream.range(0, BLOCK_LENGTH).filter(l -> l % groups == group).sum() * 1024;
                            if (lValues.getLong(g) != expected) {
                                throw new AssertionError(prefix + "expected [" + expected + "] but was [" + lValues.getLong(g) + "]");
                            }
                        }
                    }
                    case doubles -> {
                        DoubleBlock dValues = (DoubleBlock) values;
                        for (int g = 0; g < groups; g++) {
                            long group = g;
                            long expected = LongStream.range(0, BLOCK_LENGTH).filter(l -> l % groups == group).sum() * 1024;
                            if (dValues.getDouble(g) != expected) {
                                throw new AssertionError(prefix + "expected [" + expected + "] but was [" + dValues.getDouble(g) + "]");
                            }
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException("bad op " + op);
        }
    }

    private static void checkUngrouped(String prefix, String op, AggregationType aggType, Page page) {
        Block block = page.getBlock(0);
        switch (op) {
            case AVG -> {
                DoubleBlock dBlock = (DoubleBlock) block;
                if (dBlock.getDouble(0) != (BLOCK_LENGTH - 1) / 2.0) {
                    throw new AssertionError(
                        prefix + "expected [" + ((BLOCK_LENGTH - 1) / 2.0) + "] but was [" + dBlock.getDouble(0) + "]"
                    );
                }
            }
            case COUNT -> {
                LongBlock lBlock = (LongBlock) block;
                if (lBlock.getLong(0) != BLOCK_LENGTH * 1024) {
                    throw new AssertionError(prefix + "expected [" + (BLOCK_LENGTH * 1024) + "] but was [" + lBlock.getLong(0) + "]");
                }
            }
            case MIN -> {
                long expected = 0L;
                var val = switch (aggType) {
                    case longs -> ((LongBlock) block).getLong(0);
                    case doubles -> ((DoubleBlock) block).getDouble(0);
                    default -> throw new IllegalStateException("Unexpected aggregation type: " + aggType);
                };
                if (val != expected) {
                    throw new AssertionError(prefix + "expected [" + expected + "] but was [" + val + "]");
                }
            }
            case MAX -> {
                long expected = BLOCK_LENGTH - 1;
                var val = switch (aggType) {
                    case longs -> ((LongBlock) block).getLong(0);
                    case doubles -> ((DoubleBlock) block).getDouble(0);
                    default -> throw new IllegalStateException("Unexpected aggregation type: " + aggType);
                };
                if (val != expected) {
                    throw new AssertionError(prefix + "expected [" + expected + "] but was [" + val + "]");
                }
            }
            case SUM -> {
                long expected = (BLOCK_LENGTH * (BLOCK_LENGTH - 1L)) * 1024L / 2;
                var val = switch (aggType) {
                    case longs -> ((LongBlock) block).getLong(0);
                    case doubles -> ((DoubleBlock) block).getDouble(0);
                    default -> throw new IllegalStateException("Unexpected aggregation type: " + aggType);
                };
                if (val != expected) {
                    throw new AssertionError(prefix + "expected [" + expected + "] but was [" + val + "]");
                }
            }
            default -> throw new IllegalArgumentException("bad op " + op);
        }
    }

    private static Page page(String grouping, String blockType) {
        Block dataBlock = dataBlock(blockType);
        if (grouping.equals("none")) {
            return new Page(dataBlock);
        }
        List<Block> blocks = groupingBlocks(grouping, blockType);
        return new Page(Stream.concat(blocks.stream(), Stream.of(dataBlock)).toArray(Block[]::new));
    }

    private static Block dataBlock(String blockType) {
        return switch (blockType) {
            case VECTOR_LONGS -> new LongArrayVector(LongStream.range(0, BLOCK_LENGTH).toArray(), BLOCK_LENGTH).asBlock();
            case VECTOR_DOUBLES -> new DoubleArrayVector(
                LongStream.range(0, BLOCK_LENGTH).mapToDouble(l -> Long.valueOf(l).doubleValue()).toArray(),
                BLOCK_LENGTH
            ).asBlock();
            case MULTIVALUED_LONGS -> {
                var builder = LongBlock.newBlockBuilder(BLOCK_LENGTH);
                builder.beginPositionEntry();
                for (int i = 0; i < BLOCK_LENGTH; i++) {
                    builder.appendLong(i);
                    if (i % 5 == 0) {
                        builder.endPositionEntry();
                        builder.beginPositionEntry();
                    }
                }
                builder.endPositionEntry();
                yield builder.build();
            }
            case HALF_NULL_LONGS -> {
                var builder = LongBlock.newBlockBuilder(BLOCK_LENGTH);
                for (int i = 0; i < BLOCK_LENGTH; i++) {
                    builder.appendLong(i);
                    builder.appendNull();
                }
                yield builder.build();
            }
            case HALF_NULL_DOUBLES -> {
                var builder = DoubleBlock.newBlockBuilder(BLOCK_LENGTH);
                for (int i = 0; i < BLOCK_LENGTH; i++) {
                    builder.appendDouble(i);
                    builder.appendNull();
                }
                yield builder.build();
            }
            default -> throw new IllegalArgumentException("bad blockType: " + blockType);
        };
    }

    private static List<Block> groupingBlocks(String grouping, String blockType) {
        return switch (blockType) {
            case VECTOR_LONGS, VECTOR_DOUBLES -> switch (grouping) {
                    case LONGS -> List.of(
                        new LongArrayVector(LongStream.range(0, BLOCK_LENGTH).map(l -> l % GROUPS).toArray(), BLOCK_LENGTH).asBlock()
                    );
                    case INTS -> List.of(
                        new IntArrayVector(IntStream.range(0, BLOCK_LENGTH).map(i -> i % GROUPS).toArray(), BLOCK_LENGTH, null).asBlock()
                    );
                    case DOUBLES -> List.of(
                        new DoubleArrayVector(
                            IntStream.range(0, BLOCK_LENGTH).map(i -> i % GROUPS).mapToDouble(i -> (double) i).toArray(),
                            BLOCK_LENGTH
                        ).asBlock()
                    );
                    case BOOLEANS -> {
                        BooleanBlock.Builder builder = BooleanBlock.newBlockBuilder(BLOCK_LENGTH);
                        for (int i = 0; i < BLOCK_LENGTH; i++) {
                            builder.appendBoolean(i % 2 == 1);
                        }
                        yield List.of(builder.build());
                    }
                    case BYTES_REFS -> {
                        BytesRefBlock.Builder builder = BytesRefBlock.newBlockBuilder(BLOCK_LENGTH);
                        for (int i = 0; i < BLOCK_LENGTH; i++) {
                            builder.appendBytesRef(bytesGroup(i % GROUPS));
                        }
                        yield List.of(builder.build());
                    }
                    default -> throw new UnsupportedOperationException("unsupported grouping [" + grouping + "]");
                };
            case HALF_NULL_LONGS, HALF_NULL_DOUBLES -> switch (grouping) {
                    case LONGS -> {
                        var builder = LongBlock.newBlockBuilder(BLOCK_LENGTH);
                        for (int i = 0; i < BLOCK_LENGTH; i++) {
                            builder.appendLong(i % GROUPS);
                            builder.appendLong(i % GROUPS);
                        }
                        yield List.of(builder.build());
                    }
                    case INTS -> {
                        var builder = IntBlock.newBlockBuilder(BLOCK_LENGTH);
                        for (int i = 0; i < BLOCK_LENGTH; i++) {
                            builder.appendInt(i % GROUPS);
                            builder.appendInt(i % GROUPS);
                        }
                        yield List.of(builder.build());
                    }
                    case DOUBLES -> {
                        var builder = DoubleBlock.newBlockBuilder(BLOCK_LENGTH);
                        for (int i = 0; i < BLOCK_LENGTH; i++) {
                            builder.appendDouble(i % GROUPS);
                            builder.appendDouble(i % GROUPS);
                        }
                        yield List.of(builder.build());
                    }
                    case BOOLEANS -> {
                        BooleanBlock.Builder builder = BooleanBlock.newBlockBuilder(BLOCK_LENGTH);
                        for (int i = 0; i < BLOCK_LENGTH; i++) {
                            builder.appendBoolean(i % 2 == 1);
                            builder.appendBoolean(i % 2 == 1);
                        }
                        yield List.of(builder.build());
                    }
                    case BYTES_REFS -> {
                        BytesRefBlock.Builder builder = BytesRefBlock.newBlockBuilder(BLOCK_LENGTH);
                        for (int i = 0; i < BLOCK_LENGTH; i++) {
                            builder.appendBytesRef(bytesGroup(i % GROUPS));
                            builder.appendBytesRef(bytesGroup(i % GROUPS));
                        }
                        yield List.of(builder.build());
                    }
                    default -> throw new UnsupportedOperationException("unsupported grouping [" + grouping + "]");
                };
            default -> throw new IllegalArgumentException("bad blockType: " + blockType);
        };
    }

    private static BytesRef bytesGroup(int group) {
        return new BytesRef(switch (group) {
            case 0 -> "cat";
            case 1 -> "dog";
            case 2 -> "chicken";
            case 3 -> "pig";
            case 4 -> "cow";
            default -> throw new UnsupportedOperationException("can't handle [" + group + "]");
        });
    }

    @Benchmark
    @OperationsPerInvocation(1024 * BLOCK_LENGTH)
    public void run() {
        run(grouping, op, blockType);
    }

    private static void run(String grouping, String op, String blockType) {
        AggregationName aggName = AggregationName.of(op);
        AggregationType aggType = switch (blockType) {
            case VECTOR_LONGS, HALF_NULL_LONGS -> AggregationType.longs;
            case VECTOR_DOUBLES, HALF_NULL_DOUBLES -> AggregationType.doubles;
            default -> AggregationType.agnostic;
        };

        Operator operator = operator(grouping, aggName, aggType);
        Page page = page(grouping, blockType);
        for (int i = 0; i < 1024; i++) {
            operator.addInput(page);
        }
        operator.finish();
        checkExpected(grouping, op, blockType, aggType, operator.getOutput());
    }
}
