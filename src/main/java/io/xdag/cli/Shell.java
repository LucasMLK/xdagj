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
import io.xdag.crypto.keys.AddressUtils;
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
        // Account & Wallet Commands
        commandExecute.put("account", new CommandMethods(this::processAccount, this::defaultCompleter));
        commandExecute.put("balance", new CommandMethods(this::processBalance, this::defaultCompleter));
        commandExecute.put("address", new CommandMethods(this::processAddress, this::defaultCompleter));
        commandExecute.put("nonce", new CommandMethods(this::processNonce, this::defaultCompleter));
        commandExecute.put("maxbalance", new CommandMethods(this::processMaxBalance, this::defaultCompleter));
        commandExecute.put("keygen", new CommandMethods(this::processKeygen, this::defaultCompleter));

        // Transaction Commands
        commandExecute.put("transfer", new CommandMethods(this::processTransfer, this::defaultCompleter));
        commandExecute.put("consolidate", new CommandMethods(this::processConsolidate, this::defaultCompleter));

        // Block & Chain Commands
        commandExecute.put("block", new CommandMethods(this::processBlock, this::defaultCompleter));
        commandExecute.put("chain", new CommandMethods(this::processChain, this::defaultCompleter));
        commandExecute.put("mined", new CommandMethods(this::processMined, this::defaultCompleter));
        commandExecute.put("epoch", new CommandMethods(this::processEpoch, this::defaultCompleter));

        // Network & Mining Commands
        commandExecute.put("network", new CommandMethods(this::processNetwork, this::defaultCompleter));
        commandExecute.put("pool", new CommandMethods(this::processPool, this::defaultCompleter));
        commandExecute.put("stats", new CommandMethods(this::processStats, this::defaultCompleter));
        commandExecute.put("state", new CommandMethods(this::processState, this::defaultCompleter));

        // System Commands
        commandExecute.put("monitor", new CommandMethods(this::processMonitor, this::defaultCompleter));
        commandExecute.put("stop", new CommandMethods(this::processStop, this::defaultCompleter));

        registerCommands(commandExecute);
    }

    /**
     * Process consolidate command - Consolidate account balances to default address
     */
    private void processConsolidate(CommandInput input) {
        final String[] usage = {
                "consolidate - consolidate all account balances to default address",
                "Usage: consolidate",
                "  -? --help         Show help",
                "",
                "Description:",
                "  This command transfers all confirmed account balances to the default address.",
                "  Each account's balance (minus fee) will be transferred in a separate transaction.",
                "",
                "  Note: Requires wallet password for authorization.",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }

            // Verify wallet password
            Wallet wallet = new Wallet(dagKernel.getConfig());
            if (!wallet.unlock(readPassword())) {
                println("The password is incorrect");
                return;
            }

            // Execute consolidation
            println(commands.consolidate());

        } catch (Exception e) {
            saveException(e);
        }
    }

    private void processAddress(CommandInput input) {
        final String[] usage = {
                "address-  print extended info for the account corresponding to the address, page size 100",
                "Usage: address [PUBLIC ADDRESS] [PAGE]",
                "  -? --help                    Show help",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            List<String> argv = opt.args();
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }

            if (argv.isEmpty()) {
                println("Need hash or address");
                return;
            }

            String address = argv.get(0);
            int page = StringUtils.isNumeric(argv.get(1))?Integer.parseInt(argv.get(1)):1;
            try {
                org.apache.tuweni.bytes.Bytes addressBytes;
                if (WalletUtils.checkAddress(address)) {
                    addressBytes = AddressUtils.fromBase58Address(address);
                } else {
                    println("Incorrect address");
                    return;
                }
                println(commands.address(addressBytes, page));
            } catch (Exception e) {
                println("Argument is incorrect.");
            }
        } catch (Exception e) {
            saveException(e);
        }
    }

    /**
     * Process maxbalance command - Show maximum transferable balance
     */
    private void processMaxBalance(CommandInput input) {
        final String[] usage = {
                "maxbalance - print maximum transferable balance",
                "Usage: maxbalance",
                "  -? --help                    Show help",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }
            println(commands.balanceMaxXfer());

        } catch (Exception e) {
            saveException(e);
        }
    }

    private void println(final String msg) {
        PrintWriter writer = reader.getTerminal().writer();
        writer.println(msg);
        writer.flush();
    }

    private void processAccount(CommandInput input) {
        final String[] usage = {
                "account -  print first [SIZE] (20 by default) our addresses with their amounts",
                "Usage: account [SIZE]",
                "  -? --help                    Show help",
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

    private void processBalance(CommandInput input) {
        final String[] usage = {
                "balance -  print balance of the address [ADDRESS] or total balance for all our addresses\n",
                "Usage: balance [ADDRESS]",
                "  -? --help                    Show help",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }
            List<String> argv = opt.args();
            println(commands.balance(!argv.isEmpty() ? argv.getFirst() : null));
        } catch (Exception e) {
            saveException(e);
        }
    }

    /**
     * Process nonce command - Show transaction nonce
     */
    private void processNonce(CommandInput input) {
        final String[] usage = {
                "nonce - print transaction nonce of the address [ADDRESS] or total nonce of all addresses",
                "Usage: nonce [ADDRESS](optional)",
                "  -? --help                    Show help",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }
            List<String> argv = opt.args();
            println(commands.txQuantity(!argv.isEmpty() ? argv.getFirst() : null));
        } catch (Exception error) {
            saveException(error);
        }
    }

    private void processBlock(CommandInput input) {
        final String[] usage = {
                "block -  print extended info for the block corresponding to the address or hash [A]",
                "Usage: block [ADDRESS|HASH]",
                "  -? --help                    Show help",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            List<String> argv = opt.args();
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }

            if (argv.isEmpty()) {
                println("Need hash or address");
                return;
            }

            String address = argv.getFirst();
            try {
                Bytes32 hash;
                if (address.length() == 32) {
                    // as address
                    hash = address2Hash(address);
                } else {
                    // as hash
                    hash = BasicUtils.getHash(address);
                }
                if (hash == null) {
                    println("No param");
                    return;
                }
                println(commands.block(Bytes32.wrap(hash)));
            } catch (Exception e) {
                println("Argument is incorrect.");
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
                "chain - print latest [SIZE] (20 by default, max limit 100) main chain blocks",
                "Usage: chain [SIZE]",
                "  -? --help                    Show help",
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
            println(commands.mainblocks(num));
        } catch (Exception e) {
            saveException(e);
        }
    }

    /**
     * Process mined command - List mined blocks
     */
    private void processMined(CommandInput input) {
        final String[] usage = {
                "mined - print list of [SIZE] (20 by default) main blocks mined by this node",
                "Usage: mined [SIZE]",
                "  -? --help                    Show help",
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

    private void processState(CommandInput input) {
        final String[] usage = {
                "state -  print the program state",
                "Usage: state",
                "  -? --help                    Show help",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }
            println(commands.state());

        } catch (Exception e) {
            saveException(e);
        }
    }

    private void processStats(CommandInput input) {
        final String[] usage = {
                "stats -  print statistics for loaded and all known blocks",
                "Usage: stats",
                "  -? --help                    Show help",
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

    /**
     * Process epoch command - Query epoch information (CLI Redesign v5.1)
     */
    private void processEpoch(CommandInput input) {
        final String[] usage = {
                "epoch - query epoch information",
                "Usage: epoch [EPOCH_NUMBER]",
                "  -? --help                    Show help",
                "",
                "Examples:",
                "  epoch                        # Show current epoch information",
                "  epoch 23693854               # Show specific epoch information",
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

    /**
     * Process transfer command - Transfer XDAG to another address
     */
    private void processTransfer(CommandInput input) {
        final String[] usage = {
                "transfer - transfer XDAG to another address",
                "Usage: transfer <AMOUNT> <ADDRESS> [REMARK] [FEE_MILLI_XDAG]",
                "  AMOUNT            Amount to send in XDAG",
                "  ADDRESS           Recipient address (Base58 format)",
                "  REMARK            (Optional) Transaction remark",
                "  FEE_MILLI_XDAG    (Optional) Transaction fee in milli-XDAG (default: 100 = 0.1 XDAG)",
                "  -? --help         Show help",
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
                println("Missing required parameters: AMOUNT and ADDRESS");
                println("Usage: transfer <AMOUNT> <ADDRESS> [REMARK] [FEE_MILLI_XDAG]");
                return;
            }

            // Parse amount
            double amount = BasicUtils.getDouble(argv.get(0));
            if (amount < 0) {
                println("The transfer amount must be greater than 0");
                return;
            }

            // Parse address
            String addressStr = argv.get(1);
            if (!WalletUtils.checkAddress(addressStr)) {
                println("Incorrect address format. Please use Base58 address.");
                return;
            }

            // Parse optional remark
            String remark = argv.size() >= 3 ? argv.get(2) : null;

            // Parse optional fee (in milli-XDAG)
            double feeMilliXdag = 100.0; // Default: 0.1 XDAG
            if (argv.size() >= 4) {
                try {
                    feeMilliXdag = Double.parseDouble(argv.get(3));
                    if (feeMilliXdag < 0) {
                        println("Fee must be non-negative");
                        return;
                    }
                } catch (NumberFormatException e) {
                    println("Invalid fee format: " + argv.get(3));
                    return;
                }
            }

            // Verify wallet password
            Wallet wallet = new Wallet(dagKernel.getConfig());
            if (!wallet.unlock(readPassword())) {
                println("The password is incorrect");
                return;
            }

            // Execute transfer
            println(commands.transfer(amount, addressStr, remark, feeMilliXdag));

        } catch (Exception e) {
            saveException(e);
        }
    }

    private void processPool(CommandInput input){
        final String[] usage = {
                "pool - for pool, print list of recent connected pool",
                "Usage: pool ",
                "  -? --help                    Show help",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }
            println(commands.pool());
        } catch (Exception e) {
            saveException(e);
        }
    }

    private void processKeygen(CommandInput input) {
        final String[] usage = {
                "keygen - generate new private/public key pair and set it by default",
                "Usage: keygen",
                "  -? --help                    Show help",
        };
        try {
            Options opt = parseOptions(usage, input.args());
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }
            println(commands.keygen());
        } catch (Exception e) {
            saveException(e);
        }
    }

    /**
     * Process network command - Network operations
     */
    private void processNetwork(CommandInput input) {
        Pattern p = Pattern.compile("^\\s*(.*?):(\\d+)\\s*(.*?)$");
        final String[] usage = {
                "network - network operations, try 'network --help'",
                "Usage: network [OPTIONS]",
                "  -? --help                        Show help",
                "  -l --list                        List connections",
                "  -c --connect=IP:PORT             Connect to this host",
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
                println("connect to :" + opt.get("connect"));
                Matcher m = p.matcher(opt.get("connect"));
                if (m.matches()) {
                    String host = m.group(1);
                    int port = Integer.parseInt(m.group(2));
                    commands.connect(host, port);
                } else {
                    println("Node ip:port Error");
                }
            }
        } catch (Exception e) {
            saveException(e);
        }
    }

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
                "  -? --help                       Displays command help"
        };
        try {
            Options opt = parseOptions(usage, input.args());
            if (opt.isSet("help")) {
                throw new Options.HelpException(opt.usage());
            }
            // before stop must verify admin password(config at AdminSpec)
            if (!readPassword("Enter Admin password> ", true)) {
                return;
            }
            commands.stop();
            println("Stop.");
        } catch (Exception e) {
            saveException(e);
        }
    }

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
                .variable(LineReader.LIST_MAX, 50)   // max tab completion candidates
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
