package org.cytoscape.massql.result;

import java.util.List;

import com.google.gson.annotations.SerializedName;

/** The serialized form of a {@code scaninfo} run. */
public record ResultJson(@SerializedName("results") List<ScanInfoResult> results) {

    public ResultJson {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
