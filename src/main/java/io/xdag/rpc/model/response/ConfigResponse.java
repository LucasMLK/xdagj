package io.xdag.rpc.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 *
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigResponse {
        String poolIp;
        int poolPort;
        String nodeIp;
        int nodePort;
        int globalMinerLimit;
        int maxConnectMinerPerIp;
        int maxMinerPerAccount;

        String poolFeeRation;
        String poolRewardRation;
        String poolDirectRation;
        String poolFundRation;
}
