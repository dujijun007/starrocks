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

package com.starrocks.sql;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.JsonObject;
import com.starrocks.common.Pair;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.util.Counter;
import com.starrocks.common.util.ProfileManager;
import com.starrocks.common.util.ProfilingExecPlan;
import com.starrocks.common.util.RuntimeProfile;
import com.starrocks.planner.AggregationNode;
import com.starrocks.planner.ExchangeNode;
import com.starrocks.planner.JoinNode;
import com.starrocks.planner.MultiCastDataSink;
import com.starrocks.planner.OlapTableSink;
import com.starrocks.planner.PlanNode;
import com.starrocks.planner.ScanNode;
import com.starrocks.planner.UnionNode;
import com.starrocks.qe.SessionVariable;
import com.starrocks.sql.optimizer.cost.CostEstimate;
import com.starrocks.sql.optimizer.statistics.ColumnStatistic;
import com.starrocks.sql.optimizer.statistics.Statistics;
import com.starrocks.thrift.TPartitionType;
import com.starrocks.thrift.TUnit;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.starrocks.qe.scheduler.QueryRuntimeProfile.LOAD_CHANNEL_PROFILE_NAME;

public class ExplainAnalyzer {
    private static final Logger LOG = LogManager.getLogger(ExplainAnalyzer.class);

    private static final int FINAL_SINK_PSEUDO_PLAN_NODE_ID = -1;
    private static final Pattern PLAN_NODE_ID = Pattern.compile("^.*?\\(.*?plan_node_id=([-0-9]+)\\).*$");
    private static final Pattern PLAN_OP_NAME = Pattern.compile("^(.*?) \\(.*?plan_node_id=[-0-9]+\\).*$");

    // ANSI Characters
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_CORAL = "\u001B[38;2;250;128;114m";
    private static final String ANSI_BLACK_ON_RED = "\u001B[41;30m";
    private static final String ANSI_BLACK_ON_CORAL = "\u001B[38;2;0;0;0m\u001B[48;2;250;128;114m";

    private static final Set<String> EXCLUDE_DETAIL_METRIC_NAMES = Sets.newHashSet(
            RuntimeProfile.ROOT_COUNTER, RuntimeProfile.TOTAL_TIME_COUNTER,
            "NetworkTime", "ScanTime");
    private static final Set<String> INCLUDE_DETAIL_METRIC_NAMES = Sets.newHashSet(
            "IOTaskWaitTime", "IOTaskExecTime"
    );

    private static int getPlanNodeId(RuntimeProfile operator) {
        Matcher matcher = PLAN_NODE_ID.matcher(operator.getName());
        Preconditions.checkState(matcher.matches());
        return Integer.parseInt(matcher.group(1));
    }

    public static String analyze(ProfilingExecPlan plan,
                                 RuntimeProfile profile,
                                 List<Integer> planNodeIds,
                                 boolean colorExplainOutput) {
        LOG.debug("plan {} profile {} planNodeIds {}", plan, profile, planNodeIds);
        if (plan == null && profile.getChild("Summary") != null) {
            String loadType = profile.getChild("Summary").getInfoString(ProfileManager.LOAD_TYPE);
            if (loadType != null && (loadType.equals(ProfileManager.LOAD_TYPE_STREAM_LOAD)
                    || loadType.equals(ProfileManager.LOAD_TYPE_ROUTINE_LOAD))) {
                StringBuilder builder = new StringBuilder();
                profile.prettyPrint(builder, "");
                return builder.toString();
            }
        }
        ExplainAnalyzer analyzer = new ExplainAnalyzer(plan, profile, planNodeIds, colorExplainOutput);
        return analyzer.analyze();
    }

    public static String analyze(ProfilingExecPlan plan, RuntimeProfile profile)
            throws StarRocksException {
        ExplainAnalyzer analyzer = new ExplainAnalyzer(plan, profile, null, false);
        return analyzer.getQueryProgress();
    }

    private enum GraphElement {
        LST_OPERATOR_INDENT("└──"),
        MIDDLE_OPERATOR_INDENT("├──"),

        LEAF_METRIC_INDENT("    "),
        NON_LEAF_METRIC_INDENT("│   "),

        LAST_CHILD_OPERATOR_INDENT("   "),
        MIDDLE_CHILD_OPERATOR_INDENT("│  ");

        GraphElement(String content) {
            this.content = content;
        }

        private final String content;
    }

    private enum SearchMode {
        NATIVE_ONLY,
        SUBORDINATE_ONLY,
        BOTH;

        public boolean isNative() {
            return this == NATIVE_ONLY || this == BOTH;
        }

        public boolean isSubordinate() {
            return this == SUBORDINATE_ONLY || this == BOTH;
        }
    }

    private final ProfilingExecPlan plan;
    private final RuntimeProfile summaryProfile;
    private final RuntimeProfile plannerProfile;
    private final RuntimeProfile executionProfile;
    private final Set<Integer> detailPlanNodeIds = Sets.newHashSet();
    private final StringBuilder summaryBuffer = new StringBuilder();
    private final StringBuilder detailBuffer = new StringBuilder();
    private final LinkedList<String> indents = Lists.newLinkedList();
    private final Map<Integer, NodeInfo> allNodeInfos = Maps.newHashMap();
    private boolean isRuntimeProfile;
    private boolean isFinishedIdentical;

    private String color = ANSI_RESET;
    private boolean colorExplainOutput = true;

    private long cumulativeOperatorTime;
    private Counter cumulativeScanTime;
    private Counter cumulativeNetworkTime;
    private Counter scheduleTime;

    public ExplainAnalyzer(ProfilingExecPlan plan,
                           RuntimeProfile queryProfile,
                           List<Integer> planNodeIds,
                           boolean colorExplainOutput) {
        this.plan = plan;
        this.colorExplainOutput = colorExplainOutput;
        if (this.plan == null) {
            this.summaryProfile = null;
            this.plannerProfile = null;
            this.executionProfile = null;
        } else {
            this.summaryProfile = queryProfile.getChild("Summary");
            this.plannerProfile = queryProfile.getChild("Planner");
            this.executionProfile = queryProfile.getChild("Execution");
        }
        if (CollectionUtils.isNotEmpty(planNodeIds)) {
            detailPlanNodeIds.addAll(planNodeIds);
        }
    }

    public String analyze() {
        if (plan == null || summaryProfile == null || executionProfile == null) {
            return null;
        }

        try {
            parseProfile();
            appendExecutionInfo();
            appendSummaryInfo();
        } catch (Exception e) {
            LOG.error("Failed to analyze profiles", e);
            summaryBuffer.setLength(0);
            detailBuffer.setLength(0);
            indents.clear();
            resetColor();
            appendSummaryLine("Failed to analyze profiles, ", e.getMessage());
        }

        return summaryBuffer.toString() + detailBuffer;
    }

    public String getQueryProgress() throws StarRocksException {
        try {
            //get total operator info
            parseProfile();
            long totalCount = allNodeInfos.size();
            //calculate finished operator count and progress
            long finishedCount = allNodeInfos.values().stream()
                    .filter(nodeInfo -> nodeInfo.state.isFinished())
                    .count();
            String progress = (totalCount == 0L ? "0.00%" :
                    String.format("%.2f%%", 100.0 * finishedCount / totalCount));

            JsonObject progressInfo = new JsonObject();
            progressInfo.addProperty("total_operator_num", totalCount);
            progressInfo.addProperty("finished_operator_num", finishedCount);
            progressInfo.addProperty("progress_percent", progress);

            JsonObject result = new JsonObject();
            result.addProperty("query_id", summaryProfile.getInfoString(ProfileManager.QUERY_ID));
            result.addProperty("state", summaryProfile.getInfoString(ProfileManager.QUERY_STATE));
            result.add("progress_info", progressInfo);
            return result.toString();
        } catch (Exception e) {
            throw new StarRocksException("Failed to get query progress.");
        }
    }

    private void parseProfile() {
        Preconditions.checkState(plan.getProfileLevel() == 1,
                "please set `pipeline_profile_level` to 1");

        String queryState = summaryProfile.getInfoString("Query State");
        if (Objects.equals(queryState, "Running")) {
            isRuntimeProfile = true;
        } else {
            checkIdentical();
        }

        for (int i = 0; i < executionProfile.getChildList().size(); i++) {
            RuntimeProfile fragmentProfile = executionProfile.getChildList().get(i).first;
            // TODO support analyze load channel profile
            if (LOAD_CHANNEL_PROFILE_NAME.equals(fragmentProfile.getName())) {
                continue;
            }

            ProfileNodeParser parser = new ProfileNodeParser(isRuntimeProfile, fragmentProfile);
            Map<Integer, NodeInfo> nodeInfos = parser.parse();

            for (Map.Entry<Integer, NodeInfo> entry : nodeInfos.entrySet()) {
                // Exchange can be located in different fragments, put them together
                if (allNodeInfos.containsKey(entry.getKey())) {
                    NodeInfo existsNodeInfo = allNodeInfos.get(entry.getKey());
                    existsNodeInfo.merge(entry.getValue());
                } else {
                    allNodeInfos.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // Bind plan element
        plan.getFragments().forEach((fragment) -> {
            ProfilingExecPlan.ProfilingElement sink = fragment.getSink();
            if (sink.isFinalSink()) {
                NodeInfo resultNodeInfo = allNodeInfos.get(FINAL_SINK_PSEUDO_PLAN_NODE_ID);
                if (resultNodeInfo == null) {
                    resultNodeInfo =
                            new NodeInfo(isRuntimeProfile, FINAL_SINK_PSEUDO_PLAN_NODE_ID, true, null, null, null);
                    allNodeInfos.put(resultNodeInfo.planNodeId, resultNodeInfo);
                }
                resultNodeInfo.element = sink;
            }

            ProfilingExecPlan.ProfilingElement planNode = fragment.getRoot();
            Queue<ProfilingExecPlan.ProfilingElement> queue = Lists.newLinkedList();
            queue.offer(planNode);
            while (!queue.isEmpty()) {
                int num = queue.size();
                for (int i = 0; i < num; i++) {
                    ProfilingExecPlan.ProfilingElement peek = queue.poll();
                    Preconditions.checkNotNull(peek);
                    NodeInfo nodeInfo = allNodeInfos.get(peek.getId());
                    if (nodeInfo == null) {
                        nodeInfo = new NodeInfo(isRuntimeProfile, peek.getId(), true, null, null, null);
                        allNodeInfos.put(nodeInfo.planNodeId, nodeInfo);
                    }
                    nodeInfo.element = peek;

                    for (ProfilingExecPlan.ProfilingElement child : peek.getChildren()) {
                        queue.offer(child);
                    }
                }
            }
        });

        allNodeInfos.values().forEach(NodeInfo::initState);

        // Setup output rows
        allNodeInfos.values().forEach(nodeInfo -> {
            if (nodeInfo.planNodeId == FINAL_SINK_PSEUDO_PLAN_NODE_ID) {
                nodeInfo.outputRowNums = searchOutputRows(nodeInfo, "CommonMetrics", "PushRowNum");
            } else if (nodeInfo.element.instanceOf(UnionNode.class)) {
                nodeInfo.outputRowNums = sumUpMetric(nodeInfo, SearchMode.NATIVE_ONLY,
                        false, "CommonMetrics", "PullRowNum");
            } else {
                nodeInfo.outputRowNums = searchOutputRows(nodeInfo, "CommonMetrics", "PullRowNum");
            }

            // Broadcast exchange
            if (nodeInfo.element.instanceOf(ExchangeNode.class)) {
                String partType = searchInfoString(nodeInfo, SearchMode.NATIVE_ONLY,
                        null, "UniqueMetrics", "PartType");
                if (TPartitionType.UNPARTITIONED.name().equalsIgnoreCase(partType) &&
                        nodeInfo.fragmentProfile != null) {
                    Counter instanceNum = nodeInfo.fragmentProfile.getCounter("InstanceNum");
                    if (instanceNum != null && instanceNum.getValue() > 0) {
                        nodeInfo.outputRowNums.setValue(nodeInfo.outputRowNums.getValue() / instanceNum.getValue());
                    }
                }
            }
        });

        // Setup mv hits
        String mvContent = summaryProfile.getInfoString("HitMaterializedViews");
        if (StringUtils.isNotBlank(mvContent)) {
            HashSet<String> mvs = Sets.newHashSet(mvContent.split(","));
            allNodeInfos.values().forEach(nodeInfo -> {
                if (nodeInfo.element.instanceOf(ScanNode.class)) {
                    String mv = searchInfoString(nodeInfo, SearchMode.NATIVE_ONLY,
                            null, "UniqueMetrics", "Rollup");
                    if (StringUtils.isNotBlank(mv) && mvs.contains(mv)) {
                        nodeInfo.element.addTitleAttribute("MV");
                    }
                }
            });
        }

        cumulativeOperatorTime = executionProfile.getCounter("QueryCumulativeOperatorTime").getValue();
        cumulativeScanTime = executionProfile.getCounter("QueryCumulativeScanTime");
        cumulativeNetworkTime = executionProfile.getCounter("QueryCumulativeNetworkTime");
        scheduleTime = executionProfile.getCounter("QueryPeakScheduleTime");
    }

    private void checkIdentical() {
        isFinishedIdentical = true;
        Queue<RuntimeProfile> queue = Lists.newLinkedList();
        queue.offer(executionProfile);
        while (!queue.isEmpty()) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                RuntimeProfile top = queue.poll();
                Preconditions.checkState(top != null);
                if (top.containsInfoString("NotIdentical")) {
                    isFinishedIdentical = false;
                    return;
                }

                for (RuntimeProfile child : top.getChildMap().values()) {
                    queue.offer(child);
                }
            }
        }
    }

    private void appendSummaryInfo() {
        appendSummaryLine("Summary");

        // 1. Brief information
        pushIndent(GraphElement.LEAF_METRIC_INDENT);
        if (plan.getFragments().stream()
                .anyMatch(fragment -> fragment.getSink().instanceOf(OlapTableSink.class))) {
            appendSummaryLine("Attention: ", getAnsiColor(ANSI_BOLD + ANSI_BLACK_ON_RED),
                    "The transaction of the statement will be aborted, and no data will be actually inserted!!!",
                    getAnsiColor(ANSI_RESET));
        }
        if (!isFinishedIdentical) {
            appendSummaryLine("Attention: ", getAnsiColor(ANSI_BOLD + ANSI_BLACK_ON_RED),
                    "Profile is not identical!!!", getAnsiColor(ANSI_RESET));
        }
        appendSummaryLine("QueryId: ", summaryProfile.getInfoString(ProfileManager.QUERY_ID));
        appendSummaryLine("Version: ", summaryProfile.getInfoString("StarRocks Version"));
        appendSummaryLine("State: ", summaryProfile.getInfoString(ProfileManager.QUERY_STATE));
        if (isRuntimeProfile) {
            appendSummaryLine("Legend: ", NodeState.INIT.symbol, " for blocked; ", NodeState.RUNNING.symbol,
                    " for running; ", NodeState.FINISHED.symbol, " for finished");
        }

        // 2. Time Usage
        appendSummaryLine("TotalTime: ", summaryProfile.containsInfoString(ProfileManager.TOTAL_TIME) ?
                summaryProfile.getInfoString(ProfileManager.TOTAL_TIME) :
                summaryProfile.getCounter(ProfileManager.TOTAL_TIME));
        pushIndent(GraphElement.LEAF_METRIC_INDENT);
        Counter executionWallTime = executionProfile.getCounter("QueryExecutionWallTime");
        if (executionWallTime == null) {
            executionWallTime = getMaximumPipelineDriverTime();
        }
        Counter resultDeliverTime = executionProfile.getCounter("ResultDeliverTime");
        if (resultDeliverTime == null) {
            resultDeliverTime = new Counter(TUnit.TIME_NS, null, 0);
        }
        if (executionWallTime != null) {
            appendSummaryLine("ExecutionTime: ", executionWallTime, " [",
                    "Scan: ", cumulativeScanTime,
                    String.format(" (%.2f%%)", 100.0 * cumulativeScanTime.getValue() / executionWallTime.getValue()),
                    ", Network: ", cumulativeNetworkTime,
                    String.format(" (%.2f%%)", 100.0 * cumulativeNetworkTime.getValue() / executionWallTime.getValue()),
                    ", ResultDeliverTime: ", resultDeliverTime,
                    String.format(" (%.2f%%)", 100.0 * resultDeliverTime.getValue() / executionWallTime.getValue()),
                    ", ScheduleTime: ", scheduleTime,
                    String.format(" (%.2f%%)", 100.0 * scheduleTime.getValue() / executionWallTime.getValue()),
                    "]");
        }
        if (!isRuntimeProfile) {
            appendSummaryLine("CollectProfileTime: ",
                    summaryProfile.containsInfoString(ProfileManager.PROFILE_COLLECT_TIME) ?
                            summaryProfile.getInfoString(ProfileManager.PROFILE_COLLECT_TIME) :
                            summaryProfile.getCounter(ProfileManager.PROFILE_COLLECT_TIME));
        }
        appendSummaryLine("FrontendProfileMergeTime: ", executionProfile.getCounter("FrontendProfileMergeTime"));
        popIndent(); // metric indent

        // 3. Memory Usage
        appendSummaryLine("QueryPeakMemoryUsage: ", executionProfile.getCounter("QueryPeakMemoryUsage"),
                ", QueryAllocatedMemoryUsage: ", executionProfile.getCounter("QueryAllocatedMemoryUsage"));

        // 4. Top Cpu Nodes
        appendCpuTopNodes();

        // 5. Top Memory Nodes
        appendMemoryNodes();

        // 6. Runtime Progress
        if (isRuntimeProfile) {
            long finishedCount = allNodeInfos.values().stream()
                    .filter(nodeInfo -> nodeInfo.state.isFinished())
                    .count();
            if (MapUtils.isNotEmpty(allNodeInfos)) {
                appendSummaryLine(String.format("Progress (finished operator/all operator): %.2f%%",
                        100.0 * finishedCount / allNodeInfos.size()));
            }
        }

        // 7. Non default Variables
        String sessionVariables = summaryProfile.getInfoString("NonDefaultSessionVariables");
        Map<String, SessionVariable.NonDefaultValue> variables = Maps.newTreeMap();
        variables.putAll(SessionVariable.NonDefaultValue.parseFrom(sessionVariables));
        if (MapUtils.isNotEmpty(variables)) {
            appendSummaryLine("NonDefaultVariables:");
            pushIndent(GraphElement.LEAF_METRIC_INDENT);
            variables.forEach((k, v) -> {
                appendSummaryLine(k, ": ", v.defaultValue, " -> ", v.actualValue);
            });
            popIndent();
        }

        popIndent(); // metric indent
    }

    private Counter getMaximumPipelineDriverTime() {
        Counter maxDriverTotalTime = null;
        for (Pair<RuntimeProfile, Boolean> fragmentProfileKv : executionProfile.getChildList()) {
            RuntimeProfile fragmentProfile = fragmentProfileKv.first;
            for (Pair<RuntimeProfile, Boolean> pipelineProfileKv : fragmentProfile.getChildList()) {
                RuntimeProfile pipelineProfile = pipelineProfileKv.first;
                Counter driverTotalTime = pipelineProfile.getMaxCounter("DriverTotalTime");
                if (maxDriverTotalTime == null || driverTotalTime.getValue() > maxDriverTotalTime.getValue()) {
                    maxDriverTotalTime = driverTotalTime;
                }
            }
        }
        return maxDriverTotalTime;
    }

    private void appendCpuTopNodes() {
        List<NodeInfo> topCpuNodes = Lists.newArrayList(allNodeInfos.values()).stream()
                .sorted((info1, info2) -> Long.compare(info2.totalTime.getValue(), info1.totalTime.getValue()))
                .limit(10)
                .collect(Collectors.toList());
        appendSummaryLine("Top Most Time-consuming Nodes:");
        pushIndent(GraphElement.LEAF_METRIC_INDENT);
        for (int i = 0; i < topCpuNodes.size(); i++) {
            NodeInfo nodeInfo = topCpuNodes.get(i);
            if (colorExplainOutput) {
                if (nodeInfo.isMostConsuming) {
                    setRedColor();
                } else if (nodeInfo.isSecondMostConsuming) {
                    setCoralColor();
                }
            }
            appendSummaryLine(String.format("%d. ", i + 1), nodeInfo.getTitle(),
                    ": ", nodeInfo.totalTime, String.format(" (%.2f%%)", nodeInfo.totalTimePercentage));
            resetColor();
        }
        popIndent(); // metric indent
    }

    private void appendMemoryNodes() {
        List<NodeInfo> topMemoryNodes = Lists.newArrayList(allNodeInfos.values()).stream()
                .filter(nodeInfo -> nodeInfo.element.isMemoryConsumingOperator())
                .filter(nodeInfo -> nodeInfo.peekMemory != null)
                .sorted((info1, info2) -> Long.compare(info2.peekMemory.getValue(), info1.peekMemory.getValue()))
                .limit(10)
                .collect(Collectors.toList());
        appendSummaryLine("Top Most Memory-consuming Nodes:");
        pushIndent(GraphElement.LEAF_METRIC_INDENT);
        for (int i = 0; i < topMemoryNodes.size(); i++) {
            NodeInfo nodeInfo = topMemoryNodes.get(i);
            appendSummaryLine(String.format("%d. ", i + 1), nodeInfo.getTitle(), ": ", nodeInfo.peekMemory);
        }
        popIndent(); // metric indent
    }

    private void appendExecutionInfo() {
        for (int i = 0; i < plan.getFragments().size(); i++) {
            ProfilingExecPlan.ProfilingFragment fragment = plan.getFragments().get(i);
            RuntimeProfile fragmentProfile = executionProfile.getChildList().get(i).first;
            appendFragment(fragment, fragmentProfile);
        }
    }

    private void appendFragment(ProfilingExecPlan.ProfilingFragment fragment, RuntimeProfile fragmentProfile) {
        appendDetailLine(fragmentProfile.getName());
        pushIndent(GraphElement.NON_LEAF_METRIC_INDENT);
        appendDetailLine("BackendNum: ", fragmentProfile.getCounter("BackendNum"));
        appendDetailLine("InstancePeakMemoryUsage: ",
                fragmentProfile.getCounter("InstancePeakMemoryUsage"),
                ", InstanceAllocatedMemoryUsage: ", fragmentProfile.getCounter("InstanceAllocatedMemoryUsage"));
        appendDetailLine("PrepareTime: ", fragmentProfile.getCounter("FragmentInstancePrepareTime"));
        String missingInstanceIds = fragmentProfile.getInfoString("MissingInstanceIds");
        if (missingInstanceIds != null) {
            appendDetailLine("MissingInstanceIds: ", missingInstanceIds);
        }
        popIndent(); // metric indent

        ProfilingExecPlan.ProfilingElement sink = fragment.getSink();
        NodeInfo sinkInfo = null;
        boolean isFinalSink = false;
        if (sink.instanceOf(MultiCastDataSink.class)) {
            List<String> ids = Lists.newArrayList();
            if (CollectionUtils.isNotEmpty(sink.getMultiSinkIds())) {
                sink.getMultiSinkIds().forEach(id -> ids.add(Integer.toString(id)));
            }
            appendDetailLine(GraphElement.LST_OPERATOR_INDENT, sink.getDisplayName(),
                    String.format(" (ids=[%s])", String.join(", ", ids)));
        } else {
            if (sink.isFinalSink()) {
                isFinalSink = true;
                // Calculate result sink's time info, other sink's type will be properly processed
                // at the receiver side fragment through exchange node
                sinkInfo = allNodeInfos.get(FINAL_SINK_PSEUDO_PLAN_NODE_ID);
                sinkInfo.computeTimeUsage(cumulativeOperatorTime);
                if (colorExplainOutput) {
                    if (sinkInfo.isMostConsuming) {
                        setRedColor();
                    } else if (sinkInfo.isSecondMostConsuming) {
                        setCoralColor();
                    }
                }
            } else {
                sinkInfo = allNodeInfos.get(sink.getId());
            }
            appendDetailLine(GraphElement.LST_OPERATOR_INDENT, sink.getDisplayName(),
                    isFinalSink ? "" : String.format(" (id=%d)", sink.getId()));
        }
        pushIndent(GraphElement.LAST_CHILD_OPERATOR_INDENT);
        pushIndent(GraphElement.NON_LEAF_METRIC_INDENT);
        if (isFinalSink && !sinkInfo.state.isInit()) {
            // Time Usage
            NodeInfo resultNodeInfo = allNodeInfos.get(FINAL_SINK_PSEUDO_PLAN_NODE_ID);
            List<Object> items = Lists.newArrayList();
            items.addAll(Arrays.asList("TotalTime: ", resultNodeInfo.totalTime,
                    String.format(" (%.2f%%)", resultNodeInfo.totalTimePercentage)));
            items.addAll(Arrays.asList(" [CPUTime: ", resultNodeInfo.cpuTime));
            items.add("]");
            appendDetailLine(items.toArray());

            // Output Rows
            appendDetailLine("OutputRows: ", resultNodeInfo.outputRowNums);
        }
        sink.getUniqueInfos().forEach((key, value) -> appendDetailLine(key, ": ", value));

        if (isFinalSink) {
            resetColor();
        }
        popIndent(); // metric indent

        leftOrderTraverse(fragment.getRoot(), null, -1, null);

        popIndent(); // child operator indent

        appendDetailLine();
    }

    private void leftOrderTraverse(ProfilingExecPlan.ProfilingElement cur, ProfilingExecPlan.ProfilingElement parent,
                                   int index,
                                   String preTitleAttribute) {
        int planNodeId = cur.getId();
        NodeInfo nodeInfo = allNodeInfos.get(planNodeId);
        Preconditions.checkNotNull(nodeInfo);

        nodeInfo.computeTimeUsage(cumulativeOperatorTime);
        nodeInfo.computeMemoryUsage();
        if (colorExplainOutput) {
            if (nodeInfo.isMostConsuming) {
                setRedColor();
            } else if (nodeInfo.isSecondMostConsuming) {
                setCoralColor();
            }
        }

        boolean isMiddleChild = (parent != null && index < parent.getChildren().size() - 1);
        appendDetailLine(isMiddleChild ? GraphElement.MIDDLE_OPERATOR_INDENT : GraphElement.LST_OPERATOR_INDENT,
                preTitleAttribute == null ? "" : String.format("<%s> ", preTitleAttribute), nodeInfo.getTitle());

        if (isMiddleChild) {
            pushIndent(GraphElement.MIDDLE_CHILD_OPERATOR_INDENT);
        } else {
            pushIndent(GraphElement.LAST_CHILD_OPERATOR_INDENT);
        }

        boolean shouldTraverseChildren = cur.getChildren() != null
                && cur.getChildren().size() > 0
                && !(cur.instanceOf(ExchangeNode.class));
        if (!shouldTraverseChildren) {
            pushIndent(GraphElement.LEAF_METRIC_INDENT);
        } else {
            pushIndent(GraphElement.NON_LEAF_METRIC_INDENT);
        }
        appendOperatorInfo(nodeInfo);
        popIndent(); // metric indent

        resetColor();

        if (shouldTraverseChildren) {
            for (int i = 0; i < cur.getChildren().size(); i++) {
                String childTitleAttribute = null;
                if (nodeInfo.element.instanceOf(JoinNode.class)) {
                    childTitleAttribute = (i == 0 ? "PROBE" : "BUILD");
                }
                leftOrderTraverse(cur.getChildren().get(i), cur, i, childTitleAttribute);
            }
        }

        popIndent(); // child operator indent
    }

    private void appendOperatorInfo(NodeInfo nodeInfo) {
        if (isRuntimeProfile && nodeInfo.state.isInit()) {
            return;
        }

        // 1. Cost Estimation
        if (nodeInfo.element.getStatistics() != null && nodeInfo.element.getCostEstimate() != null) {
            Statistics statistics = nodeInfo.element.getStatistics();
            CostEstimate cost = nodeInfo.element.getCostEstimate();
            double totalCost = nodeInfo.element.getTotalCost();
            if (statistics.getColumnStatistics().values().stream().allMatch(ColumnStatistic::isUnknown)) {
                appendDetailLine("Estimates: [",
                        "row: ", (long) statistics.getOutputRowCount(),
                        ", cpu: ?, memory: ?, network: ?, cost: ", totalCost,
                        "]");
            } else {
                appendDetailLine("Estimates: [",
                        "row: ", (long) statistics.getOutputRowCount(),
                        ", cpu: ", String.format("%.2f", cost.getCpuCost()),
                        ", memory: ", String.format("%.2f", cost.getMemoryCost()),
                        ", network: ", String.format("%.2f", cost.getNetworkCost()),
                        ", cost: ", String.format("%.2f", totalCost),
                        "]");
            }
        } else {
            appendDetailLine("Estimates: [row: ?, cpu: ?, memory: ?, network: ?, cost: ?]");
        }

        // 2. Time Usage
        List<Object> items = Lists.newArrayList();
        items.addAll(Arrays.asList("TotalTime: ", nodeInfo.totalTime,
                String.format(" (%.2f%%)", nodeInfo.totalTimePercentage)));
        items.addAll(Arrays.asList(" [CPUTime: ", nodeInfo.cpuTime));
        if (nodeInfo.element.instanceOf(ExchangeNode.class)) {
            if (nodeInfo.networkTime != null) {
                items.addAll(Arrays.asList(", NetworkTime: ", nodeInfo.networkTime));
            }
        } else if (nodeInfo.element.instanceOf(ScanNode.class)) {
            if (nodeInfo.scanTime != null) {
                items.addAll(Arrays.asList(", ScanTime: ", nodeInfo.scanTime));
            }
        }
        items.add("]");
        appendDetailLine(items.toArray());

        // 3. Output Rows
        appendDetailLine("OutputRows: ", nodeInfo.outputRowNums);

        // 4. Memory Infos
        if (nodeInfo.element.isMemoryConsumingOperator()) {
            appendDetailLine("PeakMemory: ", nodeInfo.peekMemory, ", AllocatedMemory: ", nodeInfo.allocatedMemory);
        }

        // 5. Runtime Filters
        Counter rfInputRows = searchMetric(nodeInfo, SearchMode.NATIVE_ONLY, null, false,
                "CommonMetrics", "JoinRuntimeFilterInputRows");
        Counter rfOutputRows = searchMetric(nodeInfo, SearchMode.NATIVE_ONLY, null, false,
                "CommonMetrics", "JoinRuntimeFilterOutputRows");
        if (rfInputRows != null && rfOutputRows != null && rfInputRows.getValue() > 0) {
            appendDetailLine("RuntimeFilter: ", rfInputRows, " -> ", rfOutputRows, String.format(" (%.2f%%)",
                    100.0 * (rfInputRows.getValue() - rfOutputRows.getValue()) / rfInputRows.getValue()));
        }

        // 6. Progress Percentage
        if (isRuntimeProfile && nodeInfo.state.isRunning()) {
            Counter totalRowNum = getTotalRowNum(nodeInfo);
            if (totalRowNum != null && totalRowNum.getValue() > 0 && nodeInfo.outputRowNums != null) {
                appendDetailLine(String.format("Progress (processed rows/total rows): %.2f%%",
                        100.0 * nodeInfo.outputRowNums.getValue() / totalRowNum.getValue()));
            } else {
                appendDetailLine("Progress (processed rows/total rows): ?");
            }
        }

        // 7. Unique Infos
        appendOperatorUniqueInfo(nodeInfo);

        // 8. Subordinate Infos
        appendSubordinateInfos(nodeInfo);

        // 9. Details
        appendOperatorDetailInfo(nodeInfo);
    }

    // In order to calculate the progress of the current operator, we need to get the total row number that this
    // operator will process. And sometimes, child operator may already finish its execution, so we can get the output
    // row number from its lowest child along the hierarchy path.
    private Counter getTotalRowNum(NodeInfo rootInfo) {
        if (!(rootInfo.element.instanceOf(PlanNode.class))) {
            return null;
        }
        ProfilingExecPlan.ProfilingElement cur = rootInfo.element;
        while (true) {
            NodeInfo curNodeInfo = allNodeInfos.get(cur.getId());
            if (curNodeInfo.state.isInit()) {
                return null;
            } else if (curNodeInfo.state.isFinished()) {
                return curNodeInfo.outputRowNums;
            } else if (cur.instanceOf(AggregationNode.class)) {
                return null;
            } else if (CollectionUtils.isEmpty(cur.getChildren()) || cur.getChildren().size() > 1) {
                return null;
            } else {
                cur = cur.getChild(0);
            }
        }
    }

    private void appendOperatorUniqueInfo(NodeInfo nodeInfo) {
        if (nodeInfo.element.instanceOf(JoinNode.class)) {
            Counter buildTime = searchMetric(nodeInfo, SearchMode.NATIVE_ONLY, "_JOIN_BUILD (", true,
                    "CommonMetrics", "OperatorTotalTime");
            Counter probeTime = searchMetric(nodeInfo, SearchMode.NATIVE_ONLY, "_JOIN_PROBE (", true,
                    "CommonMetrics", "OperatorTotalTime");
            appendDetailLine("BuildTime: ", buildTime);
            appendDetailLine("ProbeTime: ", probeTime);
        } else if (nodeInfo.element.instanceOf(AggregationNode.class)) {
            Optional<RuntimeProfile> cacheOptional = nodeInfo.subordinateOperatorProfiles.stream()
                    .filter(profile -> profile.getName().contains("CACHE ("))
                    .findAny();
            if (cacheOptional.isPresent() && nodeInfo.element.hasChild(0) &&
                    nodeInfo.element.getChild(0).instanceOf(ScanNode.class)) {
                ProfilingExecPlan.ProfilingElement scanNode = nodeInfo.element.getChild(0);
                NodeInfo scanNodeInfo = allNodeInfos.get(scanNode.getId());
                Counter tabletNum = searchMetric(scanNodeInfo, SearchMode.NATIVE_ONLY, null, false,
                        "UniqueMetrics", "TabletCount");
                Counter cachePassthroughTabletNum = searchMetric(nodeInfo, SearchMode.SUBORDINATE_ONLY,
                        "CACHE (", false, "UniqueMetrics", "CachePassthroughTabletNum");
                Counter cacheProbeTabletNum = searchMetric(nodeInfo, SearchMode.SUBORDINATE_ONLY,
                        "CACHE (", false, "UniqueMetrics", "CacheProbeTabletNum");
                Counter cachePopulateTabletNum = searchMetric(nodeInfo, SearchMode.SUBORDINATE_ONLY,
                        "CACHE (", false, "UniqueMetrics", "CachePopulateTabletNum");
                if (tabletNum != null && tabletNum.getValue() > 0 && cachePassthroughTabletNum != null &&
                        cacheProbeTabletNum != null && cachePopulateTabletNum != null) {
                    appendDetailLine("TabletNum: ", tabletNum, " [",
                            "PassthroughNum: ", cachePassthroughTabletNum,
                            String.format(" (%.2f%%)",
                                    100.0 * cachePassthroughTabletNum.getValue() / tabletNum.getValue()),
                            ", ProbeNum: ", cacheProbeTabletNum,
                            String.format(" (%.2f%%)",
                                    100.0 * cacheProbeTabletNum.getValue() / tabletNum.getValue()),
                            ", PopulateNum: ", cachePopulateTabletNum,
                            String.format(" (%.2f%%)",
                                    100.0 * cachePopulateTabletNum.getValue() / tabletNum.getValue()),
                            "]");
                }
            }
        }

        nodeInfo.element.getUniqueInfos().forEach((key, value) -> appendDetailLine(key, ": ", value));
    }

    private void appendSubordinateInfos(NodeInfo nodeInfo) {
        List<RuntimeProfile> subordinateOperatorProfiles = nodeInfo.subordinateOperatorProfiles;
        if (CollectionUtils.isEmpty(subordinateOperatorProfiles)) {
            return;
        }
        Set<String> names = Sets.newTreeSet();
        for (RuntimeProfile profile : subordinateOperatorProfiles) {
            Matcher matcher = PLAN_OP_NAME.matcher(profile.getName());
            Preconditions.checkState(matcher.matches());
            String name = matcher.group(1);
            if (name.endsWith("_SINK")) {
                name = name.substring(0, name.length() - 5);
            }
            if (name.endsWith("_SOURCE")) {
                name = name.substring(0, name.length() - 7);
            }
            names.add(name);
        }
        appendDetailLine("SubordinateOperators: ");
        pushIndent(GraphElement.LEAF_METRIC_INDENT);
        for (String name : names) {
            StringBuilder titleBuilder = new StringBuilder();
            titleBuilder.append(name);
            List<String> attributes = Lists.newArrayList();
            if ("LOCAL_EXCHANGE".equalsIgnoreCase(name)) {
                String shuffleType = searchInfoString(nodeInfo, SearchMode.SUBORDINATE_ONLY, "LOCAL_EXCHANGE",
                        "UniqueMetrics", "Type");
                attributes.add(shuffleType);
            }
            if (CollectionUtils.isNotEmpty(attributes)) {
                titleBuilder.append(' ')
                        .append('[')
                        .append(String.join(", ", attributes))
                        .append(']');
            }

            appendDetailLine(titleBuilder.toString());
        }
        popIndent(); // metric indent
    }

    private void appendOperatorDetailInfo(NodeInfo nodeInfo) {
        if (!detailPlanNodeIds.contains(nodeInfo.planNodeId) && !nodeInfo.isMostConsuming &&
                !nodeInfo.isSecondMostConsuming) {
            return;
        }

        boolean onlyTimeConsumingMetrics = !detailPlanNodeIds.contains(nodeInfo.planNodeId);

        RuntimeProfile mergedUniqueMetrics = new RuntimeProfile();
        for (RuntimeProfile operatorProfile : nodeInfo.operatorProfiles) {
            RuntimeProfile uniqueMetrics = operatorProfile.getChild("UniqueMetrics");
            if (uniqueMetrics == null) {
                continue;
            }
            mergedUniqueMetrics.copyAllInfoStringsFrom(uniqueMetrics, null);
            mergedUniqueMetrics.copyAllCountersFrom(uniqueMetrics);
        }

        BiConsumer<Predicate<String>, Boolean> metricTraverser = (predicate, enableHighlight) -> {
            LinkedList<Pair<String, Boolean>> stack = Lists.newLinkedList();
            stack.push(Pair.create(RuntimeProfile.ROOT_COUNTER, false));
            while (!stack.isEmpty()) {
                Pair<String, Boolean> pair = stack.peek();
                boolean isRoot = Objects.equals(pair.first, RuntimeProfile.ROOT_COUNTER);
                if (pair.second) {
                    if (!isRoot) {
                        popIndent(); // metric indent
                    }
                    stack.pop();
                    continue;
                }
                if (!isRoot) {
                    pushIndent(GraphElement.LEAF_METRIC_INDENT);
                    appendDetailMetric(nodeInfo, mergedUniqueMetrics, pair.first, enableHighlight);
                }
                pair.second = true;
                Set<String> childCounterNames = mergedUniqueMetrics.getChildCounterMap().get(pair.first);
                if (CollectionUtils.isNotEmpty(childCounterNames)) {
                    childCounterNames.stream()
                            .filter(name -> !name.startsWith(RuntimeProfile.MERGED_INFO_PREFIX_MIN)
                                    && !name.startsWith(RuntimeProfile.MERGED_INFO_PREFIX_MAX))
                            .filter(predicate)
                            .collect(Collectors.toCollection(TreeSet::new))
                            .descendingSet()
                            .forEach(name -> stack.push(Pair.create(name, false)));
                }
            }
        };

        if (onlyTimeConsumingMetrics) {
            // Only list time-consuming metrics
            Set<String> selectNames = Sets.newHashSet();
            for (Map.Entry<String, Counter> kv : mergedUniqueMetrics.getCounterMap().entrySet()) {
                String name = kv.getKey();
                Counter counter = kv.getValue();
                if (!Counter.isTimeType(counter.getType())) {
                    continue;
                }
                if (INCLUDE_DETAIL_METRIC_NAMES.contains(name) ||
                        nodeInfo.isTimeConsumingMetric(mergedUniqueMetrics, name)) {
                    selectNames.add(name);
                    // Add all ancestors
                    Pair<Counter, String> pair = mergedUniqueMetrics.getCounterPair(name);
                    while (pair != null && !RuntimeProfile.ROOT_COUNTER.equals(pair.second)) {
                        selectNames.add(pair.second);
                        pair = mergedUniqueMetrics.getCounterPair(pair.second);
                    }
                }
            }

            if (CollectionUtils.isNotEmpty(selectNames)) {
                appendDetailLine("Detail Timers: ", nodeInfo.getDetailAttributes());
                metricTraverser.accept(selectNames::contains, false);
            }
        } else {
            appendDetailLine("Details: ", nodeInfo.getDetailAttributes());
            pushIndent(GraphElement.LEAF_METRIC_INDENT);

            if (MapUtils.isNotEmpty(mergedUniqueMetrics.getInfoStrings())) {
                appendDetailLine("Infos:");
                pushIndent(GraphElement.LEAF_METRIC_INDENT);
                for (Map.Entry<String, String> kv : mergedUniqueMetrics.getInfoStrings().entrySet()) {
                    appendDetailLine(kv.getKey(), ": ", kv.getValue());
                }
                popIndent(); // metric indent
            }

            if (CollectionUtils.isNotEmpty(mergedUniqueMetrics.getChildCounterMap().get(RuntimeProfile.ROOT_COUNTER))) {
                appendDetailLine("Counters:");
                metricTraverser.accept(name -> true, true);
            }

            popIndent(); // metric indent
        }
    }

    private void appendDetailMetric(NodeInfo nodeInfo, RuntimeProfile uniqueMetrics, String name,
                                    boolean enableHighlight) {
        if (name.startsWith(RuntimeProfile.MERGED_INFO_PREFIX_MIN)
                || name.startsWith(RuntimeProfile.MERGED_INFO_PREFIX_MAX)
                || EXCLUDE_DETAIL_METRIC_NAMES.contains(name)) {
            return;
        }
        Counter counter = uniqueMetrics.getCounter(name);
        if (counter == null) {
            return;
        }
        Counter minCounter = uniqueMetrics.getCounter(RuntimeProfile.MERGED_INFO_PREFIX_MIN + name);
        Counter maxCounter = uniqueMetrics.getCounter(RuntimeProfile.MERGED_INFO_PREFIX_MAX + name);
        boolean needHighlight = enableHighlight && colorExplainOutput && nodeInfo.isTimeConsumingMetric(uniqueMetrics, name);
        List<Object> items = Lists.newArrayList();
        if (needHighlight) {
            items.add(getBackGround());
        }
        items.add(name);
        items.add(": ");
        items.add(counter);
        if (minCounter != null || maxCounter != null) {
            items.add(" [");
            items.add("min=");
            items.add(minCounter);
            items.add(", max=");
            items.add(maxCounter);
            items.add("]");
        }
        if (needHighlight) {
            items.add(ANSI_RESET);
        }
        appendDetailLine(items.toArray());
    }

    private static String searchInfoString(NodeInfo nodeInfo, SearchMode searchMode, String pattern,
                                           String... nameLevels) {
        List<RuntimeProfile> profiles = Lists.newArrayList();
        if (searchMode.isNative()) {
            profiles.addAll(nodeInfo.operatorProfiles);
        }
        if (searchMode.isSubordinate()) {
            profiles.addAll(nodeInfo.subordinateOperatorProfiles);
        }
        for (RuntimeProfile operatorProfile : profiles) {
            if (pattern != null && !operatorProfile.getName().contains(pattern)) {
                continue;
            }
            RuntimeProfile cur = getLastLevel(operatorProfile, nameLevels);
            int lastIndex = nameLevels.length - 1;
            String content = cur.getInfoString(nameLevels[lastIndex]);

            if (content != null) {
                return content;
            }
        }

        return null;
    }

    private static Counter searchMetric(NodeInfo nodeInfo, SearchMode searchMode, String pattern,
                                        boolean useMaxValue, String... nameLevels) {
        List<RuntimeProfile> profiles = Lists.newArrayList();
        if (searchMode.isNative()) {
            profiles.addAll(nodeInfo.operatorProfiles);
        }
        if (searchMode.isSubordinate()) {
            profiles.addAll(nodeInfo.subordinateOperatorProfiles);
        }
        for (RuntimeProfile operatorProfile : profiles) {
            if (pattern != null && !operatorProfile.getName().contains(pattern)) {
                continue;
            }
            RuntimeProfile cur = getLastLevel(operatorProfile, nameLevels);
            int lastIndex = nameLevels.length - 1;
            Counter counter;
            if (useMaxValue) {
                counter = cur.getMaxCounter(nameLevels[lastIndex]);
            } else {
                counter = cur.getCounter(nameLevels[lastIndex]);
            }

            if (counter != null) {
                return counter;
            }
        }

        return null;
    }

    // We must get some metrics, such as output rows, from the upper level operator,
    // For example, we have an operator group including AGGREGATE_BLOCKING_SINK and AGGREGATE_BLOCKING_SOURCE
    // And the order between these operators are not stable, and we need to get the output rows information
    // form the AGGREGATE_BLOCKING_SOURCE, rather than AGGREGATE_BLOCKING_SINK
    private static Counter searchOutputRows(NodeInfo nodeInfo, String... nameLevels) {
        for (int i = 0; i < nodeInfo.operatorProfiles.size(); i++) {
            RuntimeProfile operatorProfile = nodeInfo.operatorProfiles.get(i);
            if (i < nodeInfo.operatorProfiles.size() - 1 && (operatorProfile.getName().contains("_SINK (")
                    || operatorProfile.getName().contains("_BUILD ("))) {
                continue;
            }
            RuntimeProfile cur = getLastLevel(operatorProfile, nameLevels);
            int lastIndex = nameLevels.length - 1;
            return cur.getCounter(nameLevels[lastIndex]);
        }
        return null;
    }

    private static Counter sumUpMetric(NodeInfo nodeInfo, SearchMode searchMode, boolean useMaxValue,
                                       String... nameLevels) {
        Counter counterSumUp = null;
        List<RuntimeProfile> profiles = Lists.newArrayList();
        if (searchMode.isNative()) {
            profiles.addAll(nodeInfo.operatorProfiles);
        }
        if (searchMode.isSubordinate()) {
            profiles.addAll(nodeInfo.subordinateOperatorProfiles);
        }
        for (RuntimeProfile profile : profiles) {
            RuntimeProfile cur = getLastLevel(profile, nameLevels);
            int lastIndex = nameLevels.length - 1;
            Counter counter;
            if (useMaxValue) {
                counter = cur.getMaxCounter(nameLevels[lastIndex]);
            } else {
                counter = cur.getCounter(nameLevels[lastIndex]);
            }
            if (counter == null) {
                continue;
            }
            if (counterSumUp == null) {
                counterSumUp = new Counter(counter.getType(), counter.getStrategy(), 0);
            }

            counterSumUp.setValue(counterSumUp.getValue() + counter.getValue());
        }

        return counterSumUp;
    }

    private static RuntimeProfile getLastLevel(RuntimeProfile operatorProfile, String... nameLevels) {
        RuntimeProfile cur = operatorProfile;
        for (int i = 0; i < nameLevels.length - 1; i++) {
            cur = cur.getChild(nameLevels[i]);
        }
        return cur;
    }

    private void appendSummaryLine(Object... contents) {
        appendLine(summaryBuffer, contents);
    }

    private void appendDetailLine(Object... contents) {
        appendLine(detailBuffer, contents);
    }

    private void appendLine(StringBuilder buffer, Object... contents) {
        Iterator<String> iterator = indents.descendingIterator();
        while (iterator.hasNext()) {
            buffer.append(iterator.next());
        }
        boolean isColorAppended = false;
        for (Object content : contents) {
            if (colorExplainOutput && !isColorAppended && !(content instanceof GraphElement)) {
                buffer.append(color);
                isColorAppended = true;
            }

            if (content == null) {
                buffer.append("?");
            } else if (content instanceof GraphElement) {
                buffer.append(((GraphElement) content).content);
            } else if (content instanceof Counter) {
                buffer.append(RuntimeProfile.printCounter((Counter) content));
            } else if (content instanceof Supplier) {
                buffer.append(((Supplier<?>) content).get());
            } else {
                buffer.append(content);
            }
        }
        if (colorExplainOutput) {
            buffer.append(ANSI_RESET);
        }
        buffer.append('\n');
    }

    private void pushIndent(GraphElement indent) {
        indents.push(indent.content);
    }

    private void popIndent() {
        indents.pop();
    }

    private String getBackGround() {
        if (color.contains(ANSI_RED)) {
            return ANSI_BOLD + ANSI_BLACK_ON_RED;
        } else if (color.contains(ANSI_CORAL)) {
            return ANSI_BOLD + ANSI_BLACK_ON_CORAL;
        }
        return ANSI_BOLD;
    }

    private void setRedColor() {
        color = ANSI_BOLD + ANSI_RED;
    }

    private void setCoralColor() {
        color = ANSI_BOLD + ANSI_CORAL;
    }

    private void resetColor() {
        color = ANSI_RESET;
    }

    private String getAnsiColor(String color) {
        if (!colorExplainOutput) {
            return "";
        }
        return color;
    }

    private enum NodeState {
        INIT("\u23F3"), // ⏳
        RUNNING("\uD83D\uDE80"), // 🚀
        FINISHED("\u2705"); // ✅

        private final String symbol;

        NodeState(String symbol) {
            this.symbol = symbol;
        }

        public boolean isInit() {
            return INIT.equals(this);
        }

        public boolean isRunning() {
            return RUNNING.equals(this);
        }

        public boolean isFinished() {
            return FINISHED.equals(this);
        }
    }

    // This structure is designed to hold all the information of a node, including plan stage information
    // as well as runtime stage information.
    private static final class NodeInfo {
        private final boolean isRuntimeProfile;
        private final int planNodeId;
        private final boolean isIntegrated;

        private final RuntimeProfile fragmentProfile;

        // One node in plan may be decomposed into multiply pipeline operators
        // like `aggregate -> (aggregate_sink_operator, aggregate_source_operator)`
        // as well as some subordinate operators, including local_exchange_operator and chunk_accumulate etc.
        private final List<RuntimeProfile> operatorProfiles;
        private final List<RuntimeProfile> subordinateOperatorProfiles;

        private ProfilingExecPlan.ProfilingElement element;
        private Counter totalTime;
        private Counter cpuTime;
        private Counter networkTime;
        private Counter scanTime;
        private Counter outputRowNums;
        private Counter peekMemory;
        private Counter allocatedMemory;
        private double totalTimePercentage;
        private boolean isMostConsuming;
        private boolean isSecondMostConsuming;
        private NodeState state;

        public NodeInfo(boolean isRuntimeProfile, int planNodeId, boolean isIntegrated, RuntimeProfile fragmentProfile,
                        List<RuntimeProfile> operatorProfiles, List<RuntimeProfile> subordinateOperatorProfiles) {
            this.isRuntimeProfile = isRuntimeProfile;
            this.planNodeId = planNodeId;
            this.isIntegrated = isIntegrated;
            this.fragmentProfile = fragmentProfile;
            this.operatorProfiles = operatorProfiles == null ? Lists.newArrayList() : operatorProfiles;
            this.subordinateOperatorProfiles =
                    subordinateOperatorProfiles == null ? Lists.newArrayList() : subordinateOperatorProfiles;
        }

        public void initState() {
            if (operatorProfiles.isEmpty()) {
                state = NodeState.INIT;
            } else {
                state = NodeState.FINISHED;
                if (!isIntegrated) {
                    state = NodeState.RUNNING;
                    return;
                }
                for (RuntimeProfile operatorProfile : operatorProfiles) {
                    RuntimeProfile commonMetrics = operatorProfile.getChild("CommonMetrics");
                    if (commonMetrics.containsInfoString("IsRunning")) {
                        state = NodeState.RUNNING;
                        return;
                    }
                }
            }
        }

        public String getDetailAttributes() {
            if (element.instanceOf(ScanNode.class)) {
                return "[ScanTime = IOTaskExecTime + IOTaskWaitTime]";
            }
            return "";
        }

        public boolean isTimeConsumingMetric(RuntimeProfile metrics, String name) {
            Counter counter = metrics.getCounter(name);
            if (counter == null) {
                return false;
            }
            Counter maxCounter = metrics.getCounter(RuntimeProfile.MERGED_INFO_PREFIX_MAX + name);
            if (Counter.isTimeType(counter.getType()) && totalTime.getValue() > 0) {
                if (counter.isAvg() && maxCounter != null &&
                        1d * maxCounter.getValue() / totalTime.getValue() > 0.3) {
                    return true;
                } else {
                    return 1d * counter.getValue() / totalTime.getValue() > 0.3;
                }
            }
            return false;
        }

        public void merge(NodeInfo other) {
            this.operatorProfiles.addAll(other.operatorProfiles);
            this.subordinateOperatorProfiles.addAll(other.subordinateOperatorProfiles);
        }

        public void computeTimeUsage(long cumulativeOperatorTime) {
            totalTime = new Counter(TUnit.TIME_NS, null, 0);
            cpuTime = sumUpMetric(this, SearchMode.BOTH, true, "CommonMetrics", "OperatorTotalTime");
            if (cpuTime != null) {
                totalTime.update(cpuTime.getValue());
            }
            if (element.instanceOf(ExchangeNode.class)) {
                networkTime = searchMetric(this, SearchMode.NATIVE_ONLY, null, true, "UniqueMetrics", "NetworkTime");
                if (networkTime != null) {
                    totalTime.update(networkTime.getValue());
                }
            } else if (element.instanceOf(ScanNode.class)) {
                scanTime = searchMetric(this, SearchMode.NATIVE_ONLY, null, true, "UniqueMetrics", "ScanTime");
                if (scanTime != null) {
                    totalTime.update(scanTime.getValue());
                }
            }
            totalTimePercentage = (totalTime.getValue() * 100D / cumulativeOperatorTime);
            if (totalTimePercentage > 30) {
                isMostConsuming = true;
            } else if (totalTimePercentage > 15) {
                isSecondMostConsuming = true;
            }
        }

        public void computeMemoryUsage() {
            peekMemory = sumUpMetric(this, SearchMode.NATIVE_ONLY,
                    true, "CommonMetrics", "OperatorPeakMemoryUsage");
            allocatedMemory = sumUpMetric(this, SearchMode.NATIVE_ONLY,
                    false, "CommonMetrics", "OperatorAllocatedMemoryUsage");
        }

        public String getTitle() {
            StringBuilder titleBuilder = new StringBuilder();
            titleBuilder.append(element.getDisplayName());
            if (!element.isFinalSink()) {
                titleBuilder.append(String.format(" (id=%d) ", planNodeId));
            }
            // Attributes
            if (CollectionUtils.isNotEmpty(element.getTitleAttributes())) {
                titleBuilder.append('[')
                        .append(String.join(", ", element.getTitleAttributes()))
                        .append(']');
            }
            if (isRuntimeProfile) {
                titleBuilder.append(' ').append(state.symbol).append(' ');
            }
            return titleBuilder.toString();
        }
    }

    // One node in plan may correspond to multiply pipeline operators,
    // This class is designed to collect all the pipeline operators that should belong to one plan node
    private static final class ProfileNodeParser {
        private final boolean isRuntimeProfile;
        private final boolean isIntegrated;
        private final RuntimeProfile fragmentProfile;
        private final List<RuntimeProfile> pipelineProfiles;
        private int pipelineIdx;
        private int operatorIdx;

        public ProfileNodeParser(boolean isRuntimeProfile, RuntimeProfile fragmentProfile) {
            this.isRuntimeProfile = isRuntimeProfile;
            this.isIntegrated = !fragmentProfile.containsInfoString("NotIdentical");
            this.fragmentProfile = fragmentProfile;
            this.pipelineProfiles = fragmentProfile.getChildList().stream()
                    .map(pair -> pair.first)
                    .collect(Collectors.toList());
            pipelineIdx = 0;
            operatorIdx = 0;
        }

        public Map<Integer, NodeInfo> parse() {
            Map<Integer, NodeInfo> nodes = Maps.newHashMap();
            while (hasNext()) {
                List<RuntimeProfile> operatorProfiles = Lists.newArrayList();
                List<RuntimeProfile> subordinateOperatorProfiles = Lists.newArrayList();

                RuntimeProfile nextOperator = nextOperator();
                int planNodeId = getPlanNodeId(nextOperator);
                RuntimeProfile commonMetrics = nextOperator.getChild("CommonMetrics");
                boolean isSubordinate = commonMetrics != null && commonMetrics.containsInfoString("IsSubordinate");
                if (isSubordinate) {
                    subordinateOperatorProfiles.add(nextOperator);
                } else {
                    operatorProfiles.add(nextOperator);
                }
                moveForward();

                NodeInfo node = new NodeInfo(isRuntimeProfile, planNodeId, isIntegrated, this.fragmentProfile,
                        operatorProfiles, subordinateOperatorProfiles);
                if (nodes.containsKey(planNodeId)) {
                    NodeInfo existingNode = nodes.get(planNodeId);
                    existingNode.merge(node);
                } else {
                    nodes.put(planNodeId, node);
                }
            }

            return nodes;
        }

        private boolean hasNext() {
            return pipelineIdx < pipelineProfiles.size();
        }

        private RuntimeProfile nextOperator() {
            RuntimeProfile pipeline = pipelineProfiles.get(pipelineIdx);
            return pipeline.getChildList().get(operatorIdx).first;
        }

        private void moveForward() {
            RuntimeProfile pipeline = pipelineProfiles.get(pipelineIdx);
            if (operatorIdx < pipeline.getChildList().size() - 1) {
                operatorIdx++;
            } else {
                pipelineIdx++;
                operatorIdx = 0;
            }
        }
    }
}
