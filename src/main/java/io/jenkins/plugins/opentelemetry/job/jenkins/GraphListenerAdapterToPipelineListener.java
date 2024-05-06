/*
 * Copyright The Original Author or Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.jenkins.plugins.opentelemetry.job.jenkins;

import static com.google.common.base.Verify.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Iterables;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Computer;
import hudson.model.Run;
import io.jenkins.plugins.opentelemetry.JenkinsOpenTelemetryPluginConfiguration;
import org.jenkinsci.plugins.workflow.actions.ArgumentsAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepAtomNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepEndNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.flow.StepListener;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.FlowStartNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;

import edu.umd.cs.findbugs.annotations.NonNull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Adapter to simplify the implementation of pipeline {@link org.jenkinsci.plugins.workflow.steps.Step} listeners.
 */
@Extension
public class GraphListenerAdapterToPipelineListener implements StepListener, GraphListener, GraphListener.Synchronous {
    private final static Logger LOGGER = Logger.getLogger(GraphListenerAdapterToPipelineListener.class.getName());

    @Override
    public final void onNewHead(FlowNode node) {
        WorkflowRun run = PipelineNodeUtil.getWorkflowRun(node);
        log(Level.FINE, () -> run.getFullDisplayName() + " - onNewHead - Process " + PipelineNodeUtil.getDetailedDebugString(node));
        for (FlowNode previousNode : node.getParents()) {
            log(Level.FINE, () -> run.getFullDisplayName() + " - Process previous node " + PipelineNodeUtil.getDetailedDebugString(previousNode) + " of node " + PipelineNodeUtil.getDetailedDebugString(node));
            if (previousNode instanceof StepAtomNode) {
                StepAtomNode stepAtomNode = (StepAtomNode) previousNode;
                fireOnAfterAtomicStep(stepAtomNode, run);
            } else if (isBeforeEndExecutorNodeStep(previousNode)) {
                String nodeName = PipelineNodeUtil.getDisplayName(((StepEndNode) previousNode).getStartNode());
                fireOnAfterEndNodeStep((StepEndNode) previousNode, nodeName, run);
            } else if (isBeforeEndStageStep(previousNode)) {
                String stageName = PipelineNodeUtil.getDisplayName(((StepEndNode) previousNode).getStartNode());
                fireOnAfterEndStageStep((StepEndNode) previousNode, stageName, run);
            } else if (isBeforeEndParallelBranch(previousNode)) {
                StepEndNode endParallelBranchNode = (StepEndNode) previousNode;
                StepStartNode beginParallelBranch = endParallelBranchNode.getStartNode();
                ThreadNameAction persistentAction = verifyNotNull(beginParallelBranch.getPersistentAction(ThreadNameAction.class), "Null ThreadNameAction on %s", beginParallelBranch);
                fireOnAfterEndParallelStepBranch(endParallelBranchNode, persistentAction.getThreadName(), run);
            } else {
                log(Level.FINE, () -> "Ignore previous node " + PipelineNodeUtil.getDetailedDebugString(previousNode));
            }
        }

        if (node instanceof FlowStartNode) {
            fireOnStartPipeline((FlowStartNode) node, run);
        } else if (node instanceof FlowEndNode) {
            fireOnEndPipeline((FlowEndNode) node, run);
        } else if (node instanceof StepAtomNode) {
            fireOnBeforeAtomicStep((StepAtomNode) node, run);
        } else if (PipelineNodeUtil.isStartStage(node)) {
            String stageName = PipelineNodeUtil.getDisplayName(node);
            fireOnBeforeStartStageStep((StepStartNode) node, stageName, run);
        } else if (PipelineNodeUtil.isStartParallelBranch(node)) {
            ThreadNameAction persistentAction = verifyNotNull(node.getPersistentAction(ThreadNameAction.class), "Null ThreadNameAction on %s", node);
            fireOnBeforeStartParallelStepBranch((StepStartNode) node, persistentAction.getThreadName(), run);
        } else if (PipelineNodeUtil.isStartParallelBlock(node)) {
            // begin parallel block
        } else if (PipelineNodeUtil.isEndParallelBlock(node)) {
            // ignore
        } else if (PipelineNodeUtil.isStartExecutorNode(node)) {
            final Map<String, Object> arguments = ArgumentsAction.getFilteredArguments(node);
            String label = Objects.toString(arguments.get("label"), null);
            fireOnStartNodeStep((StepStartNode) node, label, run);
        } else if (PipelineNodeUtil.isStartExecutorNodeExecution(node)) {
            final Map<String, Object> arguments = ArgumentsAction.getFilteredArguments(node);
            String label = Objects.toString(arguments.get("label"), null);
            fireOnAfterStartNodeStep((StepStartNode) node, label, run);
        } else if (node instanceof StepNode) {
            logFlowNodeDetails(node, run);
//            final Map<String, Object> arguments = ArgumentsAction.getFilteredArguments(node);
//            String label = Objects.toString(arguments.get("label"), null);
//            fireOnGeneralNodeStep((StepNode) node, label, run);
        }
        else {
            logFlowNodeDetails(node, run);
        }
    }

    @Override
    public void notifyOfNewStep(@NonNull Step step, @NonNull StepContext context) {
        try {
            Run run = context.get(Run.class);
            FlowNode flowNode = context.get(FlowNode.class);
            Computer computer = context.get(Computer.class);
            String computerHostname = computer == null ? "#null#" : computer.getHostName();
            String computerActions = computer == null ? "#null#" :  computer.getAllActions().stream().map(action -> action.getClass().getSimpleName()).collect(Collectors.joining(", "));
            String computerName= computer == null ? "#null#" : computer.getName() + "/" + computer.getDisplayName();
            log(Level.FINE, () -> run.getFullDisplayName() + " - notifyOfNewStep - Process " + PipelineNodeUtil.getDetailedDebugString(flowNode) + " - computer[name: " + computerName + ", hostname: " + computerHostname + "," + computerActions + "]");
        } catch (IOException | InterruptedException | RuntimeException e) {
            e.printStackTrace();
        }
    }

    private void fireOnBeforeStartParallelStepBranch(@NonNull StepStartNode node, @NonNull String branchName, @NonNull WorkflowRun run) {
        for (PipelineListener pipelineListener : PipelineListener.all()) {
            log(Level.FINE, () -> "onBeforeStartParallelStepBranch(branchName: " + branchName + ", " + node.getDisplayName() + "): " + pipelineListener.toString());
            try {
                pipelineListener.onStartParallelStepBranch(node, branchName, run);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Exception invoking `onBeforeStartParallelStepBranch` on " + pipelineListener);
            }
        }
    }

    private void fireOnAfterEndParallelStepBranch(@NonNull StepEndNode node, @NonNull String branchName, @NonNull WorkflowRun run) {
        for (PipelineListener pipelineListener : PipelineListener.all()) {
            log(Level.FINE, () -> "onAfterEndParallelStepBranch(branchName: " + branchName + ", node[name:" + node.getDisplayName() + ", id: " + node.getId() + "]): " + pipelineListener.toString());
            try {
                pipelineListener.onEndParallelStepBranch(node, branchName, run);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Exception invoking `onAfterEndParallelStepBranch` on " + pipelineListener);
            }
        }
    }

    private void logFlowNodeDetails(@NonNull FlowNode node, @NonNull WorkflowRun run) {
        String message = run.getFullDisplayName() + " - " +
            "before " + node.getDisplayFunctionName() + " // " + PipelineNodeUtil.getDisplayName(node) + ",\n";

        if (node instanceof StepNode) {
            StepNode stepNode = (StepNode) node;
            StepDescriptor descriptor = stepNode.getDescriptor();
            message += "descriptor (class:" + descriptor.getClass().getName() + ", " + descriptor.getFunctionName() + "), \n";
        }
        message += node.getAllActions().stream().map(action -> Objects.toString(action.getDisplayName(), action.getClass().toString())).collect(Collectors.joining(", "));
        message += "///////\n";
        for (Action action : node.getAllActions()) {
            if (action instanceof ArgumentsAction) {
                ArgumentsAction augAction = (ArgumentsAction) action;
                ObjectMapper mapper = new ObjectMapper();
                JsonNode map = mapper.valueToTree(augAction.getArguments());
                message += "harness-attribute: " +  map.toPrettyString() + "\n";
            }
            else {
                message +="harness-attribute-extra-debug: " + action + "\n";
            }
        }
        message += "///////\n";
        message += ", node.parent: " + Iterables.getFirst(node.getParents(), null);
        writeToFile(message, node.getId() + run.getId());
    }

    private void writeToFile(String content, String fileName) {
        fileName = fileName.replaceAll("[^a-zA-Z0-9.-]", "_");
        String directoryPath = JenkinsOpenTelemetryPluginConfiguration.get().getDirectory() + "debug/";
        try {
            Path path = Paths.get(directoryPath);
            // Create the directory and its parent directories if they do not exist
            Files.createDirectories(path);
            File myObj = new File(JenkinsOpenTelemetryPluginConfiguration.get().getDirectory() + "debug/" + fileName);
            if (myObj.createNewFile()) {
                FileWriter myWriter = new FileWriter(myObj);
                myWriter.write(content);
                myWriter.close();
            } else {
                System.out.println("File already exists.");
            }
        } catch (IOException ignore) {}
    }

    public void fireOnAfterAtomicStep(@NonNull StepAtomNode stepAtomNode, @NonNull WorkflowRun run) {
        for (PipelineListener pipelineListener : PipelineListener.all()) {
            log(() -> "onAfterAtomicStep(" + stepAtomNode.getDisplayName() + "): " + pipelineListener.toString());
            try {
                pipelineListener.onAfterAtomicStep(stepAtomNode, run);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Exception invoking `onAfterAtomicStep` on " + pipelineListener);
            }
        }
    }

    public void fireOnEndPipeline(@NonNull FlowEndNode node, @NonNull WorkflowRun run) {
        for (PipelineListener pipelineListener : PipelineListener.all()) {
            log(() -> "onEndPipeline(" + node.getDisplayName() + "): " + pipelineListener.toString());
            try {
                pipelineListener.onEndPipeline(node, run);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Exception invoking `onEndPipeline` on " + pipelineListener);
            }
        }
    }

    public void fireOnStartPipeline(@NonNull FlowStartNode node, @NonNull WorkflowRun run) {
        for (PipelineListener pipelineListener : PipelineListener.all()) {
            log(() -> "onStartPipeline(" + node.getDisplayName() + ") run " + run.getFullDisplayName() + ", pipelineListenerStep:" + pipelineListener.toString());
            try {
                pipelineListener.onStartPipeline(node, run);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Exception invoking `onStartPipeline` on " + pipelineListener);
            }
        }
    }

    public void fireOnAfterEndNodeStep(@NonNull StepEndNode node, @NonNull String nodeName, @NonNull WorkflowRun run) {
        for (PipelineListener pipelineListener : PipelineListener.all()) {
            log(() -> "onAfterEndNodeStep(" + node.getDisplayName() + "): " + pipelineListener.toString() + (node.getError() != null ? ("error: " + node.getError().getError()) : ""));
            try {
                pipelineListener.onEndNodeStep(node, nodeName, run);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Exception invoking `onAfterEndNodeStep` on " + pipelineListener);
            }
        }
    }

    public void fireOnAfterEndStageStep(@NonNull StepEndNode node, @NonNull String stageName, @NonNull WorkflowRun run) {
        for (PipelineListener pipelineListener : PipelineListener.all()) {
            log(() -> "onAfterEndStageStep(" + node.getDisplayName() + "): " + pipelineListener.toString() + (node.getError() != null ? ("error: " + node.getError().getError()) : ""));
            try {
                pipelineListener.onEndStageStep(node, stageName, run);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Exception invoking `onAfterEndStageStep` on " + pipelineListener);
            }
        }
    }

    public void fireOnBeforeAtomicStep(@NonNull StepAtomNode node, @NonNull WorkflowRun run) {
        for (PipelineListener pipelineListener : PipelineListener.all()) {
            log(() -> "onBeforeAtomicStep(" + node.getDisplayName() + "): " + pipelineListener.toString());
            try {
                pipelineListener.onAtomicStep(node, run);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Exception invoking `onBeforeAtomicStep` on " + pipelineListener);
            }
        }
    }

    public void fireOnStartNodeStep(@NonNull StepStartNode node, @NonNull String nodeLabel, @NonNull WorkflowRun run) {
        for (PipelineListener pipelineListener : PipelineListener.all()) {
            log(() -> "onStartNodeStep(" + node.getDisplayName() + "): " + pipelineListener.toString());
            try {
                pipelineListener.onStartNodeStep(node, nodeLabel, run);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Exception invoking `onStartNodeStep` on " + pipelineListener);
            }
        }
    }

    public void fireOnAfterStartNodeStep(@NonNull StepStartNode node, @NonNull String nodeLabel, @NonNull WorkflowRun run) {
        for (PipelineListener pipelineListener : PipelineListener.all()) {
            log(() -> "onAfterStartNodeStep(" + node.getDisplayName() + "): " + pipelineListener.toString());
            try {
                pipelineListener.onAfterStartNodeStep(node, nodeLabel, run);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Exception invoking `onAfterStartNodeStep` on " + pipelineListener);
            }
        }
    }

    public void fireOnGeneralNodeStep(@NonNull StepNode node, @NonNull String nodeLabel, @NonNull WorkflowRun run) {
        for (PipelineListener pipelineListener : PipelineListener.all()) {
            log(() -> "onAfterStartNodeStep(" + node.getDescriptor() + "): " + pipelineListener.toString());
            try {
                pipelineListener.onStepNodeStep(node, nodeLabel, run);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Exception invoking `onAfterStartNodeStep` on " + pipelineListener);
            }
        }
    }

    public void fireOnBeforeStartStageStep(@NonNull StepStartNode node, @NonNull String stageName, @NonNull WorkflowRun run) {
        for (PipelineListener pipelineListener : PipelineListener.all()) {
            log(() -> "onBeforeStartStageStep(" + node.getDisplayName() + "): " + pipelineListener.toString());
            try {
                pipelineListener.onStartStageStep(node, stageName, run);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Exception invoking `onBeforeStartStageStep` on " + pipelineListener);
            }
        }
    }

    private boolean isBeforeEndExecutorNodeStep(@NonNull FlowNode node) {
        return (node instanceof StepEndNode) && PipelineNodeUtil.isStartExecutorNode(((StepEndNode) node).getStartNode());
    }

    private boolean isBeforeEndStageStep(@NonNull FlowNode node) {
        return (node instanceof StepEndNode) && PipelineNodeUtil.isStartStage(((StepEndNode) node).getStartNode());
    }

    private boolean isBeforeEndParallelBranch(@NonNull FlowNode node) {
        return (node instanceof StepEndNode) && PipelineNodeUtil.isStartParallelBranch(((StepEndNode) node).getStartNode());
    }

    protected void log(@NonNull Supplier<String> message) {
        log(Level.FINE, message);
    }

    protected void log(@NonNull Level level, @NonNull Supplier<String> message) {
        LOGGER.log(level, message);
    }
}
