package com.storynpcs.admin;

import java.util.Map;

/** Read-only view of the server's live runtime configuration. */
public interface RuntimeTunablesView {
    Map<String, String> snapshot();

    long revision();

    long longValue(String key);
}
