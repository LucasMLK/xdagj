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

package io.xdag.cli;

import static io.xdag.utils.BasicUtils.address2Hash;

import io.xdag.DagKernel;
import io.xdag.Wallet;
import io.xdag.utils.BasicUtils;
import io.xdag.utils.WalletUtils;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.tuweni.bytes.Bytes32;
import org.jline.builtins.Options;
import org.jline.builtins.TTop;
import org.jline.builtins.telnet.Telnet;
import org.jline.console.CommandInput;
import org.jline.console.CommandMethods;
import org.jline.console.CommandRegistry;
import org.jline.console.impl.JlineCommandRegistry;
import org.jline.console.impl.SystemRegistryImpl;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Parser;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;

/**
 * XDAG CLI Shell - Simplified Command Interface
 *
 * Supported commands:
 * - account       List accounts with balance and nonce
 * - transaction   Query transaction details by hash
 * - block         Query block details by hash
 * - chain         List main chain blocks
 * - epoch         Query epoch information
 * - mined         List blocks mined by this node
 * - transfer      Transfer XDAG to another address
 * - network       Network management (list/connect)
 * - stats         Node statistics
 * - monitor       System monitor
 * - stop          Stop node
 */
@Slf4j
public class Shell extends JlineCommandRegistry implements CommandRegistry, Telnet.ShellProvider {

    public static final int DEFAULT_LIST_NUM = 20;
    public static final String prompt = "xdag> ";
    public Map<String, CommandMethods> commandExecute = new HashMap<>();

    @Setter
    private DagKernel dagKernel;
    private Commands commands;
    @Setter
    private LineReader reader;

    public Shell() {
        super();

        // Core data query commands
        commandExecute.put("account", new CommandMethods(this::processAccount, this::defaultCompleter));
        commandExecute.put("transaction", new CommandMethods(this::processTransaction, this::defaultCompleter));
        commandExecute.put("block", new CommandMethods(this::processBlock, this::defaultCompleter));
        commandExecute.put("chain", new CommandMethods(this::processChain, this::defaultCompleter));
        commandExecute.put("epoch", new CommandMethods(this::processEpoch, this::defaultCompleter));
        commandExecute.put("mined", new CommandMethods(this::processMined, this::defaultCompleter));

        // Transaction operations
        commandExecute.put("transfer", new CommandMethods(this::processTransfer, this::defaultCompleter));

        // Network & node management
        commandExecute.put("network", new CommandMethods(this::processNetwork, this::defaultCompleter));
        commandExecute.put("stats", new CommandMethods(this::processStats, this::defaultCompleter));

        // System commands
        commandExecute.put("monitor", new CommandMethods(this::processMonitor, this::defaultCompleter));
        commandExecute.put("stop", new CommandMethods(this::processStop, this::defaultCompleter));

        registerCommands(commandExecute);
    }

    private void println(final String msg) {
        PrintWriter writer = reader.getTerminal().writer();
        writer.println(msg);
        writer.flush();
    }

    // ========== Account Management ==========

    /**
     * Process account command - List accounts with balance and nonce
     */
    private void processAccount(CommandInput input) {
        final String[] usage = {
                "account - list wallet accounts with balance and nonce",
                "Usage: account [COUNT]",
                "  COUNT         Number of accounts to display (default: 20)",
                "  -? --help     Show help",
                "",
                "Examples:",
                "  account       # List all accounts",
                "  account 10    # List top 10 accounts by balance",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            List<String> argv = opt.args();
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }

            int num = DEFAULT_LIST_NUM;
            if (!argv.isEmpty() && NumberUtils.isDigits(argv.getFirst())) {
                num = NumberUtils.toInt(argv.getFirst());
            }

            println(commands.account(num));
        } catch (Exception e) {
            saveException(e);
        }
    }

    // ========== Transaction & Block Queries ==========

    /**
     * Process transaction command - Query transaction details by hash
     */
    private void processTransaction(CommandInput input) {
        final String[] usage = {
                "transaction - query transaction details by hash",
                "Usage: transaction <HASH>",
                "  HASH          Transaction hash (64 hex characters)",
                "  -? --help     Show help",
                "",
                "Examples:",
                "  transaction 0x1234567890abcdef...",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            List<String> argv = opt.args();
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }

            if (argv.isEmpty()) {
                println("Error: Transaction hash is required");
                println("Usage: transaction <HASH>");
                return;
            }

            String hashStr = argv.getFirst();
            try {
                Bytes32 hash = BasicUtils.getHash(hashStr);
                if (hash == null) {
                    println("Error: Invalid transaction hash format");
                    return;
                }
                println(commands.transaction(hash));
            } catch (Exception e) {
                println("Error: " + e.getMessage());
            }
        } catch (Exception e) {
            saveException(e);
        }
    }

    /**
     * Process block command - Query block details by hash
     */
    private void processBlock(CommandInput input) {
        final String[] usage = {
                "block - query block details by hash",
                "Usage: block <HASH>",
                "  HASH          Block hash (64 hex characters)",
                "  -? --help     Show help",
                "",
                "Examples:",
                "  block 0x1234567890abcdef...",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            List<String> argv = opt.args();
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }

            if (argv.isEmpty()) {
                println("Error: Block hash is required");
                println("Usage: block <HASH>");
                return;
            }

            String address = argv.getFirst();
            try {
                Bytes32 hash;
                if (address.length() == 32) {
                    hash = address2Hash(address);
                } else {
                    hash = BasicUtils.getHash(address);
                }
                if (hash == null) {
                    println("Error: Invalid block hash format");
                    return;
                }
                println(commands.block(Bytes32.wrap(hash)));
            } catch (Exception e) {
                println("Error: " + e.getMessage());
            }
        } catch (Exception e) {
            saveException(e);
        }
    }

    /**
     * Process chain command - List main chain blocks
     */
    private void processChain(CommandInput input) {
        final String[] usage = {
                "chain - list main chain blocks",
                "Usage: chain [COUNT]",
                "  COUNT         Number of blocks to display (default: 20, max: 100)",
                "  -? --help     Show help",
                "",
                "Examples:",
                "  chain         # List latest 20 blocks",
                "  chain 50      # List latest 50 blocks",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            List<String> argv = opt.args();
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }

            int num = DEFAULT_LIST_NUM;
            if (!argv.isEmpty() && NumberUtils.isDigits(argv.getFirst())) {
                num = NumberUtils.toInt(argv.getFirst());
            }

            println(commands.chain(num));
        } catch (Exception e) {
            saveException(e);
        }
    }

    /**
     * Process mined command - List blocks mined by this node
     */
    private void processMined(CommandInput input) {
        final String[] usage = {
                "mined - list blocks mined by this node",
                "Usage: mined [COUNT]",
                "  COUNT         Number of blocks to display (default: 20)",
                "  -? --help     Show help",
                "",
                "Examples:",
                "  mined         # List latest 20 mined blocks",
                "  mined 50      # List latest 50 mined blocks",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            List<String> argv = opt.args();
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }

            int num = DEFAULT_LIST_NUM;
            if (!argv.isEmpty() && NumberUtils.isDigits(argv.getFirst())) {
                num = NumberUtils.toInt(argv.getFirst());
            }

            println(commands.minedBlocks(num));
        } catch (Exception e) {
            saveException(e);
        }
    }

    /**
     * Process epoch command - Query epoch information
     */
    private void processEpoch(CommandInput input) {
        final String[] usage = {
                "epoch - query epoch information",
                "Usage: epoch [EPOCH_NUMBER]",
                "  EPOCH_NUMBER  Specific epoch to query (default: current epoch)",
                "  -? --help     Show help",
                "",
                "Examples:",
                "  epoch         # Show current epoch",
                "  epoch 23693854 # Show specific epoch",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            List<String> argv = opt.args();
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }

            Long epochNumber = null;
            if (!argv.isEmpty() && NumberUtils.isDigits(argv.getFirst())) {
                epochNumber = Long.parseLong(argv.getFirst());
            }

            println(commands.epoch(epochNumber));
        } catch (Exception e) {
            saveException(e);
        }
    }

    // ========== Transaction Operations ==========

    /**
     * Process transfer command - Transfer XDAG to another address
     */
    private void processTransfer(CommandInput input) {
        final String[] usage = {
                "transfer - transfer XDAG to another address",
                "Usage: transfer <AMOUNT> <ADDRESS> [REMARK] [FEE]",
                "  AMOUNT        Amount to send in XDAG",
                "  ADDRESS       Recipient address (Base58 format)",
                "  REMARK        (Optional) Transaction remark",
                "  FEE           (Optional) Fee in milli-XDAG (default: 100 = 0.1 XDAG)",
                "  -? --help     Show help",
                "",
                "Examples:",
                "  transfer 10.5 2gHjwW7kNTj8VTg7yoS5fMT1APU7gGFSXm8jFL9qLMNYSZPM",
                "  transfer 10.5 2gHjwW7k... \"payment for services\"",
                "  transfer 10.5 2gHjwW7k... \"payment\" 200",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            List<String> argv = opt.args();
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }

            if (argv.size() < 2) {
                println("Error: Missing required parameters");
                println("Usage: transfer <AMOUNT> <ADDRESS> [REMARK] [FEE]");
                return;
            }

            // Parse amount
            double amount = BasicUtils.getDouble(argv.get(0));
            if (amount <= 0) {
                println("Error: Amount must be greater than 0");
                return;
            }

            // Parse address
            String addressStr = argv.get(1);
            if (!WalletUtils.checkAddress(addressStr)) {
                println("Error: Invalid address format. Please use Base58 address.");
                return;
            }

            // Parse optional remark
            String remark = argv.size() >= 3 ? argv.get(2) : null;

            // Parse optional fee
            double feeMilliXdag = 100.0;
            if (argv.size() >= 4) {
                try {
                    feeMilliXdag = Double.parseDouble(argv.get(3));
                    if (feeMilliXdag < 0) {
                        println("Error: Fee must be non-negative");
                        return;
                    }
                } catch (NumberFormatException e) {
                    println("Error: Invalid fee format");
                    return;
                }
            }

            // Verify wallet password
            Wallet wallet = new Wallet(dagKernel.getConfig());
            if (!wallet.unlock(readPassword())) {
                println("Error: Incorrect password");
                return;
            }

            println(commands.transfer(amount, addressStr, remark, feeMilliXdag));

        } catch (Exception e) {
            saveException(e);
        }
    }

    // ========== Network & Node Management ==========

    /**
     * Process network command - Network operations
     */
    private void processNetwork(CommandInput input) {
        Pattern p = Pattern.compile("^\\s*(.*?):(\\d+)\\s*(.*?)$");
        final String[] usage = {
                "network - network management operations",
                "Usage: network [OPTIONS]",
                "  -l --list             List active connections",
                "  -c --connect=IP:PORT  Connect to a peer",
                "  -? --help             Show help",
                "",
                "Examples:",
                "  network --list",
                "  network --connect=127.0.0.1:8001",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }
            if (opt.isSet("list")) {
                println(commands.listConnect());
                return;
            }
            if (opt.isSet("connect")) {
                String connectStr = opt.get("connect");
                println("Connecting to: " + connectStr);
                Matcher m = p.matcher(connectStr);
                if (m.matches()) {
                    String host = m.group(1);
                    int port = Integer.parseInt(m.group(2));
                    commands.connect(host, port);
                } else {
                    println("Error: Invalid format. Use IP:PORT (e.g., 127.0.0.1:8001)");
                }
            }
        } catch (Exception e) {
            saveException(e);
        }
    }

    /**
     * Process stats command - Node statistics
     */
    private void processStats(CommandInput input) {
        final String[] usage = {
                "stats - display node statistics",
                "Usage: stats",
                "  -? --help     Show help",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }
            println(commands.stats());
        } catch (Exception e) {
            saveException(e);
        }
    }

    // ========== System Commands ==========

    /**
     * Process monitor command - System monitor
     */
    private void processMonitor(CommandInput input) {
        try {
            TTop.ttop(input.terminal(), input.out(), input.err(), input.args());
        } catch (Exception e) {
            saveException(e);
        }
    }

    /**
     * Process stop command - Stop node
     */
    private void processStop(CommandInput input) {
        final String[] usage = {
                "stop - stop the XDAG node",
                "Usage: stop",
                "  -? --help     Show help"
        };
        try {
            Options opt = parseOptions(usage, input.args());
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }

            if (!readPassword("Enter Admin password> ", true)) {
                return;
            }

            commands.stop();
            println("Node stopped.");
        } catch (Exception e) {
            saveException(e);
        }
    }

    // ========== Helper Methods ==========

    private boolean readPassword(String prompt, boolean isTelnet) {
        Character mask = '*';
        String line;
        do {
            line = reader.readLine(prompt, mask);
        } while (StringUtils.isEmpty(line));

        if (isTelnet) {
            return line.equals(dagKernel.getConfig().getAdminSpec().getAdminTelnetPassword());
        }
        return true;
    }

    private String readPassword() {
        Character mask = '*';
        String line;
        do {
            line = reader.readLine(WalletUtils.WALLET_PASSWORD_PROMPT, mask);
        } while (StringUtils.isEmpty(line));
        return line;
    }

    @Override
    public void shell(Terminal terminal, Map<String, String> environment) {
        if (commands == null) {
            commands = new Commands(dagKernel);
        }

        Parser parser = new DefaultParser();
        SystemRegistryImpl systemRegistry = new SystemRegistryImpl(parser, terminal, null, null);
        systemRegistry.setCommandRegistries(this);
        systemRegistry.setGroupCommandsInHelp(false);

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(systemRegistry.completer())
                .parser(parser)
                .variable(LineReader.LIST_MAX, 50)
                .build();

        this.setReader(reader);

        if (!readPassword("Enter Admin password>", true)) {
            return;
        }

        do {
            try {
                systemRegistry.cleanUp();
                String line = reader.readLine(prompt);
                if (Strings.CS.startsWith(line, "exit")) {
                    break;
                }
                systemRegistry.execute(line);
            } catch (Exception e) {
                systemRegistry.trace(e);
            }
        } while (true);
    }
}
