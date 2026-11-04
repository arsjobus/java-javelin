package com.javelin.ai;

import java.util.List;
import java.util.function.Consumer;

/**
 * Where AI answers come from. The editor depends on this interface and never on a vendor: Ollama is one
 * implementation, another local server or an external service would be another. A provider is only ever used after the
 * user invoked an AI action and approved what is sent.
 */
public interface AiProvider {
    /** Short stable id, as written in the {@code ai.provider} setting. */
    String id();

    /** Name for people, for the provider indicator. */
    String displayName();

    /** Where requests go ("127.0.0.1:11434"), for the network indicator. */
    String location();

    /** True when requests never leave this computer. */
    boolean isLocal();

    /** The models the provider can run now, sorted. */
    List<String> models(AiCancel cancel) throws AiException;

    /**
     * Sends the request and waits for the whole answer, passing each piece of text to {@code onText} as it arrives (from
     * the calling thread). Stops with {@link AiException.Kind#CANCELLED} when {@code cancel} is triggered.
     */
    AiReply complete(AiRequest request, Consumer<String> onText, AiCancel cancel) throws AiException;
}
