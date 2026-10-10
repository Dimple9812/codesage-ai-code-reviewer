package com.codesage.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.codesage.ai.LlmProvider;

/** Deterministic LLM used in integration tests; never calls a real provider. */
public class StubLlmProvider implements LlmProvider {

    private volatile Function<Request, String> responder = r -> "{\"summary\":\"No issues.\",\"overallScore\":95,\"findings\":[]}";
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    public void respond(Function<Request, String> responder) {
        this.responder = responder;
    }

    public List<Request> requests() {
        return requests;
    }

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public String complete(Request request) {
        requests.add(request);
        return responder.apply(request);
    }
}
