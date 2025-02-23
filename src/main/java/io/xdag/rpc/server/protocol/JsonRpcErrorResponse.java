package io.xdag.rpc.server.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.xdag.rpc.error.JsonRpcError;
import lombok.Getter;
import lombok.Setter;

/**
 *
 */
@Getter
public class JsonRpcErrorResponse {
    @JsonProperty("jsonrpc")
    private String jsonrpc = "2.0";

    @JsonProperty("id")
    private int id;

    @JsonProperty("error")
    private JsonRpcError error;

    public JsonRpcErrorResponse(int id, JsonRpcError error) {
        this.id = id;
        this.error = error;
    }
}
