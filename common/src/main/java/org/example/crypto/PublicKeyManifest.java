package org.example.crypto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.HashMap;
import java.util.Map;

public class PublicKeyManifest {
    private final Map<String, String> keys = new HashMap<>();

    @JsonAnySetter
    public void addKey(String id, String pem) {
        keys.put(id, pem);
    }

    public Map<String, String> getKeys() {
        return keys;
    }
}
