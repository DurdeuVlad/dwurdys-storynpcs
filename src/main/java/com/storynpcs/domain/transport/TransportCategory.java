package com.storynpcs.domain.transport;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.storynpcs.domain.common.NamespacedId;

/** A named group of transport locations. */
public class TransportCategory {

    @JsonProperty(required = true)
    private NamespacedId id;

    @JsonProperty(required = true)
    private String name;

    @JsonProperty
    private List<TransportLocation> locations = new ArrayList<>();

    public TransportCategory() {}

    public NamespacedId getId() { return id; }
    public void setId(NamespacedId id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<TransportLocation> getLocations() { return List.copyOf(locations); }
    public void setLocations(List<TransportLocation> locations) {
        this.locations = locations == null ? new ArrayList<>() : new ArrayList<>(locations);
    }
}
