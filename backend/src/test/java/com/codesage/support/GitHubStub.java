package com.codesage.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

/** Programmable fake of the GitHub REST API used by integration tests (no network access). */
public class GitHubStub extends Dispatcher {

    private final Map<String, Function<RecordedRequest, MockResponse>> routes = new ConcurrentHashMap<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    public void on(String method, String path, Function<RecordedRequest, MockResponse> handler) {
        routes.put(method + " " + path, handler);
    }

    public void json(String method, String path, String body) {
        on(method, path, r -> new MockResponse().setHeader("Content-Type", "application/json").setBody(body));
    }

    /** Route that only answers for a specific bearer token (simulates per-user GitHub visibility). */
    public void jsonForToken(String token, String path, String body) {
        routes.put("GET " + path + " @" + token,
                r -> new MockResponse().setHeader("Content-Type", "application/json").setBody(body));
    }

    public void raw(String path, String content) {
        on("GET", path, r -> new MockResponse().setHeader("Content-Type", "application/vnd.github.raw").setBody(content));
    }

    public List<RecordedRequest> requests() {
        return requests;
    }

    @Override
    public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String path = request.getPath() == null ? "" : request.getPath();
        String noQuery = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String auth = request.getHeader("Authorization");
        String token = auth == null ? "" : auth.replace("Bearer ", "");
        for (String key : new String[] {request.getMethod() + " " + noQuery + " @" + token,
                request.getMethod() + " " + path, request.getMethod() + " " + noQuery}) {
            Function<RecordedRequest, MockResponse> h = routes.get(key);
            if (h != null) {
                return h.apply(request);
            }
        }
        return new MockResponse().setResponseCode(404).setBody("{\"message\":\"Not Found\"}");
    }
}
