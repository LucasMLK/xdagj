package io.xdag.rpc;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * API Documentation for XDAG JSON-RPC Interface
 */
@Schema(description = "XDAG JSON-RPC API Documentation")
public class XdagApiDocs {

    @Data
    @Schema(description = "Protocol Version Request/Response")
    public static class ProtocolVersion {
        @Schema(description = "Protocol version request")
        public static class Request {
            public String jsonrpc = "2.0";
            public String method = "xdag_protocolVersion";
            public List<Object> params = List.of();
            public String id;
        }
        
        @Schema(description = "Protocol version response")
        public static class Response {
            public String jsonrpc = "2.0";
            public String result;  // protocol version
            public String id;
        }
    }

    @Data
    @Schema(description = "Sync Status Request/Response")
    public static class Syncing {
        @Schema(description = "Sync status request")
        public static class Request {
            public String jsonrpc = "2.0";
            public String method = "xdag_syncing";
            public List<Object> params = List.of();
            public String id;
        }
        
        @Schema(description = "Sync status response")
        public static class Response {
            public String jsonrpc = "2.0";
            public Boolean result;  // false if not syncing, true if syncing
            public String id;
        }
    }

    @Data
    @Schema(description = "Mining Address Request/Response")
    public static class Coinbase {
        @Schema(description = "Mining address request")
        public static class Request {
            public String jsonrpc = "2.0";
            public String method = "xdag_coinbase";
            public List<Object> params = List.of();
            public String id;
        }
        
        @Schema(description = "Mining address response")
        public static class Response {
            public String jsonrpc = "2.0";
            public String result;  // mining address
            public String id;
        }
    }

    @Data
    @Schema(description = "Block Number Request/Response")
    public static class BlockNumber {
        @Schema(description = "Block number request")
        public static class Request {
            public String jsonrpc = "2.0";
            public String method = "xdag_blockNumber";
            public List<Object> params = List.of();
            public String id;
        }
        
        @Schema(description = "Block number response")
        public static class Response {
            public String jsonrpc = "2.0";
            public String result;  // current block number
            public String id;
        }
    }

    @Data
    @Schema(description = "Account Balance Request/Response")
    public static class GetBalance {
        @Schema(description = "Account balance request")
        public static class Request {
            public String jsonrpc = "2.0";
            public String method = "xdag_getBalance";
            public List<String> params;  // [address]
            public String id;
        }
        
        @Schema(description = "Account balance response")
        public static class Response {
            public String jsonrpc = "2.0";
            public String result;  // balance in XDAG
            public String id;
        }
    }

    @Data
    @Schema(description = "Total Balance Request/Response")
    public static class GetTotalBalance {
        @Schema(description = "Total balance request")
        public static class Request {
            public String jsonrpc = "2.0";
            public String method = "xdag_getTotalBalance";
            public List<Object> params = List.of();
            public String id;
        }
        
        @Schema(description = "Total balance response")
        public static class Response {
            public String jsonrpc = "2.0";
            public String result;  // total balance in XDAG
            public String id;
        }
    }

    @Data
    @Schema(description = "Block By Number Request/Response")
    public static class GetBlockByNumber {
        @Schema(description = "Block by number request")
        public static class Request {
            public String jsonrpc = "2.0";
            public String method = "xdag_getBlockByNumber";
            public List<Object> params;  // [bnOrId, page, pageSize?]
            public String id;
        }
    }

    @Data
    @Schema(description = "Block By Hash Request/Response")
    public static class GetBlockByHash {
        @Schema(description = "Block by hash request")
        public static class Request {
            public String jsonrpc = "2.0";
            public String method = "xdag_getBlockByHash";
            public List<Object> params;  // [hash, page, startTime?, endTime?, pageSize?]
            public String id;
        }
    }

    @Data
    @Schema(description = "Node Status Request/Response")
    public static class GetStatus {
        @Schema(description = "Node status request")
        public static class Request {
            public String jsonrpc = "2.0";
            public String method = "xdag_getStatus";
            public List<Object> params = List.of();
            public String id;
        }
    }

    @Data
    @Schema(description = "Network Type Request/Response")
    public static class GetNetType {
        @Schema(description = "Network type request")
        public static class Request {
            public String jsonrpc = "2.0";
            public String method = "xdag_netType";
            public List<Object> params = List.of();
            public String id;
        }
    }

    @Data
    @Schema(description = "Pool Config Request/Response")
    public static class GetPoolConfig {
        @Schema(description = "Pool config request")
        public static class Request {
            public String jsonrpc = "2.0";
            public String method = "xdag_poolConfig";
            public List<Object> params = List.of();
            public String id;
        }
    }

    @Data
    @Schema(description = "Network Connection List Request/Response")
    public static class GetNetConnectionList {
        @Schema(description = "Network connection list request")
        public static class Request {
            public String jsonrpc = "2.0";
            public String method = "xdag_netConnectionList";
            public List<Object> params = List.of();
            public String id;
        }
    }

    @Data
    @Schema(description = "Max Xfer Balance Request/Response")
    public static class GetMaxXferBalance {
        @Schema(description = "Max xfer balance request")
        public static class Request {
            public String jsonrpc = "2.0";
            public String method = "xdag_getMaxXferBalance";
            public List<Object> params = List.of();
            public String id;
        }
    }
}
