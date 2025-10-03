package org.bsc.langgraph4j;

import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.exception.SubGraphInterruptionException;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.subgraph.SubGraphOutput;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static java.lang.String.format;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.utils.CollectionsUtils.mergeMap;
import static org.junit.jupiter.api.Assertions.*;

public class CompiledSubGraphTest {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompiledSubGraphTest.class);

    static class MyState extends MessagesState<String> {

        public MyState(Map<String, Object> initData) {
            super(initData);
        }

        boolean resumeSubgraph() {
            return this.<Boolean>value("resume_subgraph")
                    .orElse(false);
        }
    }

    private AsyncNodeAction<MyState> _makeNode(String withMessage) {
        return node_async(state ->
                Map.of("messages", format("[%s]", withMessage))
        );
    }

    private AsyncNodeAction<MyState> _makeNodeAndCheckState(String withMessage, String attributeKey) {
        return node_async(state -> {
                    var attributeValue = state.value(attributeKey).orElse("");

                    return Map.of("messages", format("[%s]", withMessage + attributeValue ));

                }

        );
    }

    private AsyncNodeAction<MyState> _makeSubgraphNode(String parentNodeId, CompiledGraph<MyState> subGraph) {
        final var runnableConfig = RunnableConfig.builder()
                .threadId(format("%s_subgraph", parentNodeId))
                .build();
        return node_async(state -> {

            var input = (state.resumeSubgraph()) ?
                    GraphInput.resume() :
                    GraphInput.args(state.data());

            var output = subGraph.stream(input, runnableConfig).stream()
                    .reduce((a, b) -> b)
                    .orElseThrow();

            if (!output.isEND()) {
                throw new SubGraphInterruptionException(parentNodeId,
                        output.node(),
                        mergeMap(output.state().data(), Map.of("resume_subgraph", true)));
            }
            return mergeMap(output.state().data(), Map.of("resume_subgraph", AgentState.MARK_FOR_REMOVAL));
        });
    }

    private CompiledGraph<MyState> subGraph(BaseCheckpointSaver saver) throws Exception {

        var compileConfig = CompileConfig.builder()
                .checkpointSaver(saver)
                .interruptAfter("NODE3.2")
                .build();

        var stateSerializer = new ObjectStreamStateSerializer<>(MyState::new);

        return new StateGraph<>(MyState.SCHEMA, stateSerializer)
                .addEdge(START, "NODE3.1")
                .addNode("NODE3.1", _makeNode("NODE3.1"))
                .addNode("NODE3.2", _makeNode("NODE3.2"))
                .addNode("NODE3.3", _makeNode("NODE3.3"))
                .addNode("NODE3.4", _makeNodeAndCheckState("NODE3.4", "newAttribute"))
                .addEdge("NODE3.1", "NODE3.2")
                .addEdge("NODE3.2", "NODE3.3")
                .addEdge("NODE3.3", "NODE3.4")
                .addEdge("NODE3.4", END)
                .compile(compileConfig);
    }

    @ParameterizedTest
    @EnumSource( CompiledGraph.StreamMode.class     )
    private void testCompileSubGraphInterruptionUsingException( CompiledGraph.StreamMode mode ) throws Exception {

        var saver = new MemorySaver();

        var stateSerializer = new ObjectStreamStateSerializer<>(MyState::new);

        var compileConfig = CompileConfig.builder()
                .checkpointSaver(saver)
                .build();

        var subGraph = subGraph(saver); // create subgraph

        var parentGraph =  new StateGraph<>(MyState.SCHEMA, stateSerializer)
                .addEdge(START, "NODE1")
                .addNode("NODE1", _makeNode("NODE1"))
                .addNode("NODE2", _makeNode("NODE2"))
                .addNode("NODE3", _makeSubgraphNode("NODE3", subGraph))
                .addNode("NODE4", _makeNode("NODE4"))
                .addNode("NODE5", _makeNode("NODE5"))
                .addEdge("NODE1", "NODE2")
                .addEdge("NODE2", "NODE3")
                .addEdge("NODE3", "NODE4")
                .addEdge("NODE4", "NODE5")
                .addEdge("NODE5", END)
                .compile(compileConfig);

        var runnableConfig = RunnableConfig.builder()
                                .streamMode(mode)
                                .build();

        var input = GraphInput.args(Map.of());

        do {
            try {
                for (var output : parentGraph.stream(input, runnableConfig)) {

                    log.info("output: {}", output);
                }

                break;
            }
            catch( Exception ex ) {
                var interruptException = SubGraphInterruptionException.from(ex);
                if( interruptException.isPresent() ) {

                    log.info("SubGraphInterruptionException: {}", interruptException.get().getMessage());
                    var interruptionState = interruptException.get().state();


                    // ==== METHOD 1 =====
                    // FIND NODE BEFORE SUBGRAPH AND RESUME
                    /*
                    StateSnapshot<?> lastNodeBeforeSubGraph = workflow.getStateHistory(runnableConfig).stream()
                                                                .skip(1)
                                                                .findFirst()
                                                                .orElseThrow( () -> new IllegalStateException("lastNodeBeforeSubGraph is null"));
                    var nodeBeforeSubgraph = lastNodeBeforeSubGraph.node();
                    runnableConfig = workflow.updateState( lastNodeBeforeSubGraph.config(), interruptionState );
                    */

                    // ===== METHOD 2 =======
                    // UPDATE STATE ASSUMING TO BE ON NODE BEFORE SUBGRAPH ('NODE2') AND RESUME
                    var nodeBeforeSubgraph = "NODE2";
                    runnableConfig = parentGraph.updateState( runnableConfig, interruptionState, nodeBeforeSubgraph );
                    input = GraphInput.resume();

                    log.info( "RESUME GRAPH FROM END OF NODE: {}", nodeBeforeSubgraph);
                    continue;
                }

                throw ex;
            }
        } while( true );

    }

    @ParameterizedTest
    @EnumSource( CompiledGraph.StreamMode.class     )
    public void testCompileSubGraphInterruptionSharingSaver(  CompiledGraph.StreamMode mode ) throws Exception {

        var saver = new MemorySaver();

        var stateSerializer = new ObjectStreamStateSerializer<>(MyState::new);

        var compileConfig = CompileConfig.builder()
                .checkpointSaver(saver)
                .build();

        var subGraph = subGraph(saver); // create subgraph

        var parentGraph =  new StateGraph<>(MyState.SCHEMA, stateSerializer)
                .addEdge(START, "NODE1")
                .addNode("NODE1", _makeNode("NODE1"))
                .addNode("NODE2", _makeNode("NODE2"))
                .addNode("NODE3", subGraph)
                .addNode("NODE4", _makeNode("NODE4"))
                .addNode("NODE5", _makeNodeAndCheckState("NODE5", "newAttribute"))
                .addEdge("NODE1", "NODE2")
                .addEdge("NODE2", "NODE3")
                .addEdge("NODE3", "NODE4")
                .addEdge("NODE4", "NODE5")
                .addEdge("NODE5", END)
                .compile(compileConfig);

        var runnableConfig = RunnableConfig.builder()
                .threadId("1")
                .streamMode(mode)
                .build();

        var input = GraphInput.args(Map.of());

        var graphIterator = parentGraph.stream(input, runnableConfig);

        var output = graphIterator.stream()
                .peek( out -> log.info("output: {}", out) )
                .reduce((a, b) -> b);

        assertTrue( output.isPresent() );

        assertFalse( output.get().isEND() );
        assertInstanceOf(SubGraphOutput.class,  output.get() );

        var iteratorResult = AsyncGenerator.resultValue(graphIterator);

        assertTrue( iteratorResult.isPresent() );
        assertInstanceOf(InterruptionMetadata.class, iteratorResult.get());

        runnableConfig = parentGraph.updateState( runnableConfig, Map.of( "newAttribute", "<myNewValue>") );

        input = GraphInput.resume();

        graphIterator = parentGraph.stream(input, runnableConfig);

        output = graphIterator.stream()
                .peek( out -> log.info("output: {}", out) )
                .reduce((a, b) -> b);

        assertTrue( output.isPresent() );
        assertTrue( output.get().isEND() );

        assertIterableEquals(List.of(
                "[NODE1]",
                "[NODE2]",
                "[NODE3.1]",
                "[NODE3.2]",
                "[NODE3.3]",
                "[NODE3.4<myNewValue>]",
                "[NODE4]",
                "[NODE5<myNewValue>]"), output.get().state().messages() );
    }

    @ParameterizedTest
    @EnumSource( CompiledGraph.StreamMode.class     )
    public void testCompileSubGraphInterruptionWithDifferentSaver( CompiledGraph.StreamMode mode ) throws Exception {

        var parentSaver = new MemorySaver();

        var stateSerializer = new ObjectStreamStateSerializer<>(MyState::new);

        BaseCheckpointSaver childSaver = new MemorySaver();
        var subGraph = subGraph( childSaver ); // create subgraph

        var compileConfig = CompileConfig.builder()
                .checkpointSaver(parentSaver)
                .build();

        var parentGraph =  new StateGraph<>(MyState.SCHEMA, stateSerializer)
                .addEdge(START, "NODE1")
                .addNode("NODE1", _makeNode("NODE1"))
                .addNode("NODE2", _makeNode("NODE2"))
                .addNode("NODE3", subGraph)
                .addNode("NODE4", _makeNodeAndCheckState("NODE4", "newAttribute"))
                .addNode("NODE5", _makeNode("NODE5"))
                .addEdge("NODE1", "NODE2")
                .addEdge("NODE2", "NODE3")
                .addEdge("NODE3", "NODE4")
                .addEdge("NODE4", "NODE5")
                .addEdge("NODE5", END)
                .compile(compileConfig);

        var runnableConfig = RunnableConfig.builder()
                                .streamMode(mode)
                                .build();

        var input = GraphInput.args(Map.of());

        var graphIterator = parentGraph.stream(input, runnableConfig);

        var output = graphIterator.stream()
                .peek( out -> log.info("output: {}", out) )
                .reduce((a, b) -> b);

        assertTrue( output.isPresent() );

        assertFalse( output.get().isEND() );
        assertInstanceOf( SubGraphOutput.class, output.get() );

        var iteratorResult = AsyncGenerator.resultValue(graphIterator);

        assertTrue( iteratorResult.isPresent() );
        assertInstanceOf(InterruptionMetadata.class, iteratorResult.get());

        runnableConfig = parentGraph.updateState( runnableConfig, Map.of( "newAttribute", "<myNewValue>") );

        input = GraphInput.resume();

        graphIterator = parentGraph.stream(input, runnableConfig);

        output = graphIterator.stream()
                .peek( out -> log.info("output: {}}", out) )
                .reduce((a, b) -> b);

        assertTrue( output.isPresent() );
        assertTrue( output.get().isEND() );

        assertIterableEquals(List.of(
                "[NODE1]",
                "[NODE2]",
                "[NODE3.1]",
                "[NODE3.2]",
                "[NODE3.3]",
                "[NODE3.4<myNewValue>]",
                "[NODE4<myNewValue>]",
                "[NODE5]"), output.get().state().messages() );
    }

    @ParameterizedTest
    @EnumSource( CompiledGraph.StreamMode.class     )
    public void testNestedCompiledSubgraphFormIssue216( CompiledGraph.StreamMode mode ) throws Exception {

        var subSubGraph = new StateGraph<>(MyState::new)
                .addNode("foo1", _makeNode("foo1"))
                .addNode("foo2", _makeNode("foo2"))
                .addNode("foo3", _makeNode("foo3"))
                .addEdge(StateGraph.START, "foo1")
                .addEdge("foo1", "foo2")
                .addEdge("foo2", "foo3")
                .addEdge("foo3", StateGraph.END)
                .compile();

        var subGraph = new StateGraph<>(MyState::new)
                .addNode("bar1", _makeNode("bar1"))
                .addNode("subgraph2", subSubGraph)
                .addNode("bar2", _makeNode("bar2"))
                .addEdge(StateGraph.START, "bar1")
                .addEdge("bar1", "subgraph2")
                .addEdge("subgraph2", "bar2")
                .addEdge("bar2", StateGraph.END)
                .compile();

        var stateGraph = new StateGraph<>(MyState::new)
                .addNode("main1", _makeNode("main1"))
                .addNode("subgraph1", subGraph)
                .addNode("main2", _makeNode("main2"))
                .addEdge(StateGraph.START, "main1")
                .addEdge("main1", "subgraph1")
                .addEdge("subgraph1", "main2")
                .addEdge("main2", StateGraph.END)
                .compile();

        var runnableConfig = RunnableConfig.builder()
                                .streamMode(mode)
                                .build();

        var input = GraphInput.args(Map.of());

        var graphIterator = stateGraph.stream(input, runnableConfig);

        var output = graphIterator.stream()
                .peek( out -> log.info("output: {}", out) )
                .reduce((a, b) -> b);

    }

}