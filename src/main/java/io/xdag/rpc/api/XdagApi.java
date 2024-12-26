/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.xdag.rpc.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.xdag.config.XdagLifecycle;
import io.xdag.rpc.model.response.BlockResponse;
import io.xdag.rpc.model.request.TransactionRequest;
import io.xdag.rpc.model.response.NetConnResponse;
import io.xdag.rpc.model.response.ProcessResponse;
import io.xdag.rpc.model.response.XdagStatusResponse;

import java.util.List;

@OpenAPIDefinition(
    info = @Info(
        title = "XDAG JSON-RPC API",
        version = "2.0",
        description = "JSON-RPC 2.0 API for XDAG Blockchain"
    )
)
@Tag(name = "XDAG JSON-RPC", description = "All JSON-RPC methods are called via POST request")
public interface XdagApi extends XdagLifecycle {

    @Operation(
        summary = "Get block information",
        description = "Get detailed block information by block hash",
        method = "POST"
    )
    @ApiResponse(
        responseCode = "200",
        description = "Successfully returned block information",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(implementation = BlockResponse.class)
        )
    )
    BlockResponse xdag_getBlockByHash(
        @Parameter(description = "Block hash") String hash,
        @Parameter(description = "Page number") int page
    );
    BlockResponse xdag_getBlockByHash(String hash, int page, int pageSize);
    BlockResponse xdag_getBlockByHash(String hash, int page, String startTime, String endTime);
    BlockResponse xdag_getBlockByHash(String hash, int page, String startTime, String endTime, int pageSize);

    @Operation(
        summary = "Get block by number",
        description = "Get block information by block number or ID",
        method = "POST"
    )
    @ApiResponse(
        responseCode = "200",
        description = "Successfully returned block information",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(implementation = BlockResponse.class)
        )
    )
    BlockResponse xdag_getBlockByNumber(
        @Parameter(description = "Block number or ID") String bnOrId,
        @Parameter(description = "Page number") int page
    );
    BlockResponse xdag_getBlockByNumber(String bnOrId, int page, int pageSize);

    @Operation(
        summary = "Get current block number",
        description = "Get the latest block number",
        method = "POST"
    )
    @ApiResponse(
        responseCode = "200",
        description = "Successfully returned block number",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(example = "\"12345\"")
        )
    )
    String xdag_blockNumber();

    @Operation(
        summary = "Get mining address",
        description = "Get the mining address of current node",
        method = "POST"
    )
    @ApiResponse(
        responseCode = "200",
        description = "Successfully returned mining address",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(example = "\"XDAG_ADDRESS\"")
        )
    )
    String xdag_coinbase();

    @Operation(
        summary = "Get account balance",
        description = "Get XDAG balance of specified address",
        method = "POST"
    )
    @ApiResponse(
        responseCode = "200",
        description = "Successfully returned balance",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(example = "\"1234.000000000\"")
        )
    )
    String xdag_getBalance(@Parameter(description = "XDAG address") String address);

    @Operation(
        summary = "Get total balance",
        description = "Get total balance of all addresses",
        method = "POST"
    )
    @ApiResponse(
        responseCode = "200",
        description = "Successfully returned total balance",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(example = "\"1234567.000000000\"")
        )
    )
    String xdag_getTotalBalance();

    @Operation(
        summary = "Get node status",
        description = "Get current node status information",
        method = "POST"
    )
    @ApiResponse(
        responseCode = "200",
        description = "Successfully returned node status",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(implementation = XdagStatusResponse.class)
        )
    )
    XdagStatusResponse xdag_getStatus();

    @Operation(
        summary = "Send transaction",
        description = "Send an XDAG transaction",
        method = "POST"
    )
    @ApiResponse(
        responseCode = "200",
        description = "Successfully returned transaction result",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(implementation = ProcessResponse.class)
        )
    )
    ProcessResponse xdag_personal_sendTransaction(
        @Parameter(description = "Transaction request object") TransactionRequest request,
        @Parameter(description = "Wallet passphrase") String passphrase
    );

    @Operation(
        summary = "Get block reward",
        description = "Get mining reward of specified block",
        method = "POST"
    )
    @ApiResponse(
        responseCode = "200",
        description = "Successfully returned block reward",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(example = "\"1024.000000000\"")
        )
    )
    String xdag_getRewardByNumber(@Parameter(description = "Block number or ID") String bnOrId);

    @Operation(
        summary = "Send raw transaction",
        description = "Send a raw transaction data",
        method = "POST"
    )
    @ApiResponse(
        responseCode = "200",
        description = "Successfully returned transaction hash",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(example = "\"0x...\"")
        )
    )
    String xdag_sendRawTransaction(@Parameter(description = "Raw transaction data") String rawData);

    @Operation(
        summary = "Get network connections",
        description = "Get network connection information of current node",
        method = "POST"
    )
    @ApiResponse(
        responseCode = "200",
        description = "Successfully returned network connections",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(implementation = NetConnResponse.class)
        )
    )
    List<NetConnResponse> xdag_netConnectionList() throws Exception;

    @Operation(
        summary = "Get network type",
        description = "Get current network type (mainnet/testnet)",
        method = "POST"
    )
    @ApiResponse(
        responseCode = "200",
        description = "Successfully returned network type",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(example = "\"mainnet\"")
        )
    )
    String xdag_netType();
}
