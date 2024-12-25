package io.xdag.rpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcResponse {
    // Getters and Setters
    @JsonProperty("jsonrpc")
    private String jsonrpc = "2.0";
    
    @JsonProperty("result")
    private Object result;
    
    @JsonProperty("error")
    private JsonRpcError error;
    
    @JsonProperty("id")
    private String id;

    public JsonRpcResponse(String id, Object result) {
        this.id = id;
        this.result = result;
    }

    public JsonRpcResponse(String id, Object result, JsonRpcError error) {
        this.id = id;
        this.result = result;
        this.error = error;
    }

}
