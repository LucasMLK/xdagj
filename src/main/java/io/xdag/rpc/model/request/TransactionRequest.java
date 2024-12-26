package io.xdag.rpc.model.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransactionRequest {
    public String from;
    public String to;
    public String value;
    public String remark;
    @Override
    public String toString() {
        return "CallArguments{" +
                "from='" + from + '\'' +
                ", to='" + to + '\'' +
                ", value='" + value + '\'' +
                ", remark='" + remark + '\'' +
                '}';
    }
}
