package io.xdag.rpc.model.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor  // Add this annotation for default constructor
public class TransactionRequest {
    @JsonProperty("from")
    private String from;  // Change to private for better encapsulation

    @JsonProperty("to")
    private String to;

    @JsonProperty("value")
    private String value;

    @JsonProperty("remark")
    private String remark;

    // Add all-args constructor with JsonCreator
    @JsonCreator
    public TransactionRequest(
            @JsonProperty("from") String from,
            @JsonProperty("to") String to,
            @JsonProperty("value") String value,
            @JsonProperty("remark") String remark) {
        this.from = from;
        this.to = to;
        this.value = value;
        this.remark = remark;
    }
}
