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

package io.xdag.config.cli;

import static io.xdag.crypto.keys.AddressUtils.toBytesAddress;
import static io.xdag.utils.WalletUtils.WALLET_PASSWORD_PROMPT;

import com.google.common.collect.Lists;
import io.xdag.DagKernel;
import io.xdag.Launcher;
import io.xdag.Wallet;
import io.xdag.api.http.HttpApiServer;
import io.xdag.config.Config;
import io.xdag.config.Constants;
import io.xdag.crypto.bip.Bip39Mnemonic;
import io.xdag.crypto.encoding.Base58;
import io.xdag.crypto.keys.AddressUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BytesUtils;
import org.apache.commons.io.FileUtils;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.Strings;

@Slf4j
public class XdagCli extends Launcher {

  private static final Scanner scanner = new Scanner(
      new InputStreamReader(System.in, StandardCharsets.UTF_8));

  /**
   * Creates a new Xdag CLI instance.
   */
  public XdagCli() {
    Option helpOption = Option.builder()
        .longOpt(XdagOption.HELP.toString())
        .desc("print help")
        .build();
    addOption(helpOption);

    Option versionOption = Option.builder()
        .longOpt(XdagOption.VERSION.toString())
        .desc("show version")
        .build();
    addOption(versionOption);

    Option accountOption = Option.builder()
        .longOpt(XdagOption.ACCOUNT.toString())
        .desc("init|create|list")
        .hasArg(true).numberOfArgs(1).optionalArg(false).argName("action").type(String.class)
        .build();
    addOption(accountOption);

    Option changePasswordOption = Option.builder()
        .longOpt(XdagOption.CHANGE_PASSWORD.toString()).desc("change wallet password").build();
    addOption(changePasswordOption);

    Option dumpPrivateKeyOption = Option.builder()
        .longOpt(XdagOption.DUMP_PRIVATE_KEY.toString())
        .desc("print hex key")
        .hasArg(true).optionalArg(false).argName("address").type(String.class)
        .build();
    addOption(dumpPrivateKeyOption);

    Option importPrivateKeyOption = Option.builder()
        .longOpt(XdagOption.IMPORT_PRIVATE_KEY.toString())
        .desc("import hex key")
        .hasArg(true).optionalArg(false).argName("key").type(String.class)
        .build();
    addOption(importPrivateKeyOption);

    Option importMnemonicOption = Option.builder()
        .longOpt(XdagOption.IMPORT_MNEMONIC.toString())
        .desc("import HDWallet mnemonic")
        .hasArg(true).optionalArg(false).argName("mnemonic").type(String.class)
        .build();
    addOption(importMnemonicOption);

    Option convertOldWalletOption = Option.builder()
        .longOpt(XdagOption.CONVERT_OLD_WALLET.toString())
        .desc("convert xdag old wallet.dat to private key hex")
        .hasArg(true).optionalArg(false).argName("filename").type(String.class)
        .build();
    addOption(convertOldWalletOption);
  }

  public static void main(String[] args, XdagCli cli) throws Exception {
    try {
      cli.start(args);
    } catch (IOException exception) {
      System.err.println(exception.getMessage());
    }
  }

  public static void main(String[] args) throws Exception {
    main(args, new XdagCli());
  }

  public void start(String[] args) throws Exception {
    Config config = buildConfig(args);
    setConfig(config);
    // move old args
    List<String> argsList = Lists.newArrayList();
    for (String arg : args) {
      if (Strings.CS.equalsAny(arg, "-d", "-t")) {
        // only devnet or testnet
      } else {
        argsList.add(arg);
      }
    }
    String[] newArgs = argsList.toArray(new String[0]);

    // Parse command line options
    CommandLine cmd;
    try {
      cmd = parseOptions(newArgs);
    } catch (ParseException exception) {
      System.err.println("Failed to parse command line options: " + exception.getMessage());
      printHelp();
      exit(-1);
      return;
    }

    // Handle command line options
    if (cmd.hasOption(XdagOption.HELP.toString())) {
      printHelp();
    } else if (cmd.hasOption(XdagOption.VERSION.toString())) {
      printVersion();
    } else if (cmd.hasOption(XdagOption.ACCOUNT.toString())) {
      String action = cmd.getOptionValue(XdagOption.ACCOUNT.toString()).trim();
      switch (action) {
        case "init" -> initHDAccount();
        case "create" -> createAccount();
        case "list" -> listAccounts();
        default -> System.out.println("No Action!");
      }
    } else if (cmd.hasOption(XdagOption.CHANGE_PASSWORD.toString())) {
      changePassword();
    } else if (cmd.hasOption(XdagOption.DUMP_PRIVATE_KEY.toString())) {
      dumpPrivateKey(cmd.getOptionValue(XdagOption.DUMP_PRIVATE_KEY.toString()).trim());
    } else if (cmd.hasOption(XdagOption.IMPORT_PRIVATE_KEY.toString())) {
      importPrivateKey(cmd.getOptionValue(XdagOption.IMPORT_PRIVATE_KEY.toString()).trim());
    } else if (cmd.hasOption(XdagOption.IMPORT_MNEMONIC.toString())) {
      importMnemonic(cmd.getOptionValue(XdagOption.IMPORT_MNEMONIC.toString()).trim());
    } else {
      start();
    }
  }

  protected void printHelp() {
    HelpFormatter formatter = new HelpFormatter();
    formatter.setWidth(200);
    formatter.printHelp("./xdag.sh [options]", getOptions());
  }

  protected void printVersion() {
    System.out.println(Constants.CLIENT_VERSION);
  }

  protected void start() throws IOException {
    // create/unlock wallet
    Wallet wallet = loadWallet().exists() ? loadAndUnlockWallet() : createNewWallet();
    if (wallet == null) {
      return;
    }

    if (!wallet.isHdWalletInitialized()) {
      initializedHdSeed(wallet, System.out);
    }

    // create a new account if the wallet is empty
    List<ECKeyPair> accounts = wallet.getAccounts();
    if (accounts.isEmpty()) {
      ECKeyPair key = wallet.addAccountWithNextHdKey();
      wallet.flush();
      System.out.println(
          "New Address (Hex):" + BytesUtils.toHexString(toBytesAddress(key).toArray()));
      System.out.println("New Address (Base58):" + Base58.encodeCheck(toBytesAddress(key)));
    }

    // start kernel
    try {
      DagKernel dagKernel = startDagKernel(getConfig(), wallet);
      Launcher.registerShutdownHook("dagkernel", dagKernel::stop);

      // Start RPC server if enabled
      if (getConfig().getHttpSpec().isRpcHttpEnabled()) {
        startRpcServer(dagKernel);
      }

      // Keep main thread alive to prevent JVM from exiting
      // The background threads (HybridSyncManager, PoW Algorithm) will keep running
      System.out.println("XDAG node is running. Press Ctrl+C to stop.");
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      System.out.println("Node interrupted, shutting down...");
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      System.err.println("Uncaught exception during kernel startup:" + e.getMessage());
      log.error("Fatal error during kernel startup", e);
      exit(-1);
    }
  }

  protected void startRpcServer(DagKernel dagKernel) {
    try {
      System.out.println("Starting HTTP API server...");

      HttpApiServer apiServer =
          new HttpApiServer(getConfig().getHttpSpec(), dagKernel);
      apiServer.start();

      Launcher.registerShutdownHook("api", apiServer::stop);

      String host = getConfig().getHttpSpec().getRpcHttpHost();
      int port = getConfig().getHttpSpec().getRpcHttpPort();
      String displayHost =
          ("0.0.0.0".equals(host) || "::".equals(host)) ? "127.0.0.1" : host;
      String baseUrl = "http://" + displayHost + ":" + port;

      System.out.println("HTTP API server started on " + host + ":" + port);
      System.out.println("  - RESTful API:  " + baseUrl + "/api/v1/");

    } catch (Exception e) {
      System.err.println("Failed to start API server: " + e.getMessage());
      log.error("Failed to start API server", e);
    }
  }

  protected DagKernel startDagKernel(Config config, Wallet wallet) {
    DagKernel dagKernel = new DagKernel(config, wallet);
    dagKernel.start();
    return dagKernel;
  }

  protected void initHDAccount() {
    // create/unlock wallet
    Wallet wallet;
    if (loadWallet().exists()) {
      wallet = loadAndUnlockWallet();
    } else {
      wallet = createNewWallet();
    }

    if (wallet == null) {
      return;
    }
    if (!wallet.isHdWalletInitialized()) {
      initializedHdSeed(wallet, System.out);
    } else {
      System.out.println("HD Wallet Account already init.");
    }
  }

  /**
   * Create a new account in the wallet using HD wallet derivation
   */
  protected void createAccount() {
    Wallet wallet = loadAndUnlockWallet();

    if (!wallet.isHdWalletInitialized()) {
      System.out.println("Please init HD Wallet account first!");
      return;
    }

    ECKeyPair key = wallet.addAccountWithNextHdKey();
    if (wallet.flush()) {
      System.out.println("New Address:" + AddressUtils.toBase58Address(key));
      System.out.println("PublicKey:" + key.getPublicKey().toUnprefixedHex());
    }
  }

  /**
   * List all accounts in the wallet
   */
  protected void listAccounts() {
    Wallet wallet = loadAndUnlockWallet();
    List<ECKeyPair> accounts = wallet.getAccounts();

    if (accounts.isEmpty()) {
      System.out.println("Account Missing");
    } else {
      for (int i = 0; i < accounts.size(); i++) {
        System.out.println("Address:" + i + " " + AddressUtils.toBase58Address(accounts.get(i)));
      }
    }
  }

  /**
   * Change wallet password
   */
  protected void changePassword() {
    Wallet wallet = loadAndUnlockWallet();

    // Note: loadAndUnlockWallet() already ensures wallet is unlocked,
    // but we keep this defensive check for safety
    if (!wallet.isUnlocked()) {
      System.err.println("Wallet is not unlocked");
      return;
    }

    String newPassword = readNewPassword("EnterNewPassword:", "ReEnterNewPassword:");
    if (newPassword == null) {
      return;
    }

    wallet.changePassword(newPassword);
    if (!wallet.flush()) {
      System.out.println("Wallet File Cannot Be Updated");
      return;
    }

    System.out.println("Password Changed Successfully!");
  }

  protected void exit(int code) {
    System.exit(code);
  }

  /**
   * Dump private key for a given address
   *
   * @param address Hex string address
   */
  protected void dumpPrivateKey(String address) {
    Wallet wallet = loadAndUnlockWallet();
    byte[] addressBytes = BytesUtils.hexStringToBytes(address);
    ECKeyPair account = wallet.getAccount(addressBytes);

    if (account == null) {
      System.out.println("Address Not In Wallet");
    } else {
      System.out.println("Private:" + account.getPrivateKey().toUnprefixedHex());
      System.out.println("Private Key Dumped Successfully!");
    }
  }

  /**
   * Import a private key into the wallet
   *
   * @param key Hex string private key
   * @return true if import succeeded, false otherwise
   */
  protected boolean importPrivateKey(String key) {
    Wallet wallet = loadWallet().exists() ? loadAndUnlockWallet() : createNewWallet();
    ECKeyPair account = ECKeyPair.fromHex(key);

    if (!wallet.addAccount(account)) {
      System.out.println("Private Key Already In Wallet");
      return false;
    }

    if (!wallet.flush()) {
      System.out.println("Wallet File Cannot Be Updated");
      return false;
    }

    System.out.println("Address:" + AddressUtils.toBase58Address(account));
    System.out.println("PublicKey:" + account.getPublicKey().toUnprefixedHex());
    System.out.println("Private Key Imported Successfully!");
    return true;
  }

  /**
   * Import HD wallet from mnemonic phrase
   *
   * @param mnemonic BIP39 mnemonic phrase
   * @return true if import succeeded, false otherwise
   */
  protected boolean importMnemonic(String mnemonic) {
    Wallet wallet = loadWallet().exists() ? loadAndUnlockWallet() : createNewWallet();

    if (wallet.isHdWalletInitialized()) {
      System.out.println("HDWallet Mnemonic Already In Wallet");
      return false;
    }

    if (!Bip39Mnemonic.isValid(mnemonic)) {
      System.out.println("Wrong Mnemonic");
      return false;
    }

    wallet.initializeHdWallet(mnemonic);
    if (!wallet.flush()) {
      System.out.println("HDWallet File Cannot Be Updated");
      return false;
    }

    // default add one hd key
    createAccount();

    System.out.println("HDWallet Mnemonic Imported Successfully!");
    return true;
  }

  public Wallet loadWallet() {
    return new Wallet(getConfig());
  }

  /**
   * Load and unlock wallet. This method ensures the wallet is unlocked before returning.
   * If unlocking fails, the application exits.
   *
   * @return Unlocked wallet (never null, never locked)
   */
  public Wallet loadAndUnlockWallet() {
    Wallet wallet = loadWallet();

    // Try empty password first if no password provided
    if (getPassword() == null) {
      if (wallet.unlock("")) {
        setPassword("");
        return wallet;
      }
      // Empty password failed, prompt for password
      setPassword(readPassword(WALLET_PASSWORD_PROMPT));
    }

    // Unlock with provided password
    if (!wallet.unlock(getPassword())) {
      System.err.println("Invalid password");
      exit(-1);
    }

    return wallet;
  }

  /**
   * Create a new wallet with a new password
   */
  public Wallet createNewWallet() {
    System.out.println("Create New Wallet...");

    // Use password from --password flag if provided, otherwise prompt
    String newPassword = getPassword();
    if (newPassword == null) {
      newPassword = readNewPassword("EnterNewPassword:", "ReEnterNewPassword:");
      if (newPassword == null) {
        return null;
      }
      setPassword(newPassword);
    }

    Wallet wallet = loadWallet();

    if (!wallet.unlock(newPassword) || !wallet.flush()) {
      System.err.println("Create New WalletError");
      System.exit(-1);
      return null;
    }

    return wallet;
  }

  /**
   * Read a new password from input and require confirmation
   */
  public String readNewPassword(String newPasswordMessageKey, String reEnterNewPasswordMessageKey) {
    String newPassword = readPassword(newPasswordMessageKey);
    String newPasswordRe = readPassword(reEnterNewPasswordMessageKey);

    if (!newPassword.equals(newPasswordRe)) {
      System.err.println("ReEnter NewPassword Incorrect");
      System.exit(-1);
      return null;
    }

    return newPassword;
  }

  /**
   * Reads a line from the console.
   */
  public String readLine(String prompt) {
    if (prompt != null) {
      System.out.print(prompt);
      System.out.flush();
    }

    return scanner.nextLine();
  }

  public boolean initializedHdSeed(Wallet wallet, PrintStream printer) {
    if (wallet.isUnlocked() && !wallet.isHdWalletInitialized()) {
      // HD Mnemonic
      printer.println("HdWallet Initializing...");
      try {
        String phrase = Bip39Mnemonic.generateString();
        printer.println("HdWallet Mnemonic:" + phrase);

        // In non-interactive mode (password from --password flag), skip mnemonic repetition
        // This is safe for testing/automation - mnemonic is logged above
        if (getPassword() == null || System.console() != null) {
          // Interactive mode: require mnemonic repetition
          String repeat = readLine("HdWallet Mnemonic Repeat:");
          repeat = String.join(" ", repeat.trim().split("\\s+"));

          if (!repeat.equals(phrase)) {
            printer.println("HdWallet Initialized Failure");
            return false;
          }
        } else {
          // Non-interactive mode: auto-confirm
          printer.println(
              "Non-interactive mode: Auto-initializing HD wallet (mnemonic logged above)");
        }

        wallet.initializeHdWallet(phrase);
        wallet.flush();
        printer.println("HdWallet Initialized Successfully!");
        return true;
      } catch (Exception e) {
        printer.println("HdWallet Initialization Failed: " + e.getMessage());
        return false;
      }
    }
    return false;
  }

  public String readPassword(String prompt) {
    Console console = System.console();
    if (console == null) {
      if (prompt != null) {
        System.out.print(prompt);
        System.out.flush();
      }
      return scanner.nextLine();
    }
    return new String(console.readPassword(prompt));
  }

  // TODO: Snapshot functionality temporarily disabled, will be re-implemented later
  // SnapshotStore was removed in refactoring
  public void makeSnapshot() {
    System.out.println("Snapshot functionality temporarily disabled");
    System.out.println("Will be re-implemented in future version");
    System.out.println("Please use --enable-snapshot option to load existing snapshots");
  }

  /**
   * Copy directory recursively
   */
  public static void copyDir(String sourcePath, String newPath) {
    try {
      FileUtils.copyDirectory(new File(sourcePath), new File(newPath));
    } catch (IOException e) {
      log.error("Failed to copy directory from {} to {}", sourcePath, newPath, e);
    }
  }

  /**
   * Copy single file
   */
  public static void copyFile(String sourcePath, String newPath) {
    try {
      FileUtils.copyFile(new File(sourcePath), new File(newPath));
    } catch (IOException e) {
      log.error("Failed to copy file from {} to {}", sourcePath, newPath, e);
    }
  }
}
