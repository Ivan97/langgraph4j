package org.bsc.langgraph4j.agentexecutor;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import org.bsc.langgraph4j.langchain4j.tool.LC4jToolMapBuilder;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.serializer.StateSerializer;

public abstract class AgentExecutorBuilder<State extends MessagesState<ChatMessage>, B extends AgentExecutorBuilder<State,B> > extends LC4jToolMapBuilder<B> {

    StateSerializer<State> stateSerializer;
    ChatModel chatModel;
    StreamingChatModel streamingChatModel;
    SystemMessage systemMessage;
    ResponseFormat responseFormat;

    @SuppressWarnings("unchecked")
    protected B result() {
        return (B)this;
    }

    public B stateSerializer(StateSerializer<State> stateSerializer) {
        this.stateSerializer = stateSerializer;
        return result();
    }

    public B chatModel(ChatModel chatModel ) {
        if( this.chatModel == null ) {
            this.chatModel = chatModel;
        }
        return result();
    }

    public B chatModel(StreamingChatModel streamingChatModel ) {
        if( this.streamingChatModel == null ) {
            this.streamingChatModel = streamingChatModel;
        }
        return result();
    }

    public B systemMessage(SystemMessage systemMessage ) {
        if( this.systemMessage == null ) {
            this.systemMessage = systemMessage;
        }
        return result();
    }

    public B responseFormat(ResponseFormat responseFormat ) {
        this.responseFormat = responseFormat;
        return result();
    }

}
