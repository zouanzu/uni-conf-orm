package com.ubi.orm.param;

import java.util.HashMap;
import java.util.Map;

public class StandardParams {
    private final Map<String, Object> pathParams = new HashMap<>();
    private final Map<String, Object> queryParams = new HashMap<>();
    private final Map<String, Object> bodyParams = new HashMap<>();

    public void addPathParam(String key, Object value) { pathParams.put(key, value); }
    public void addQueryParam(String key, Object value) { queryParams.put(key, value); }
    public void addBodyParam(String key, Object value) { bodyParams.put(key, value); }

    public Object getParam(String key) {
        if (pathParams.containsKey(key)) return pathParams.get(key);
        if (bodyParams.containsKey(key)) return bodyParams.get(key);
        return queryParams.get(key);
    }

    public Map<String, Object> getPathParams() { return new HashMap<>(pathParams); }
    public Map<String, Object> getQueryParams() { return new HashMap<>(queryParams); }
    public Map<String, Object> getBodyParams() { return new HashMap<>(bodyParams); }
}
