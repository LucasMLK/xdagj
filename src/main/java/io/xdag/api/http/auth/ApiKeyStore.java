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
package io.xdag.api.http.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ApiKeyStore {

  private final Map<String, Permission> apiKeys = new ConcurrentHashMap<>();
  @Getter
  private final boolean authEnabled;

  public ApiKeyStore(boolean authEnabled) {
    this.authEnabled = authEnabled;
  }

  public void addApiKey(String apiKey, Permission permission) {
    apiKeys.put(apiKey, permission);
    log.info("Added API key with {} permission", permission);
  }

  /**
   * Get the number of registered API keys
   *
   * @return number of API keys in the store
   */
  public int size() {
    return apiKeys.size();
  }

  public Permission validate(String apiKey) {
    if (!authEnabled) {
      return Permission.WRITE;
    }
    return apiKeys.getOrDefault(apiKey, null);
  }

  public boolean hasPermission(String apiKey, Permission required) {
    if (!authEnabled) {
      return true;
    }

    Permission granted = validate(apiKey);
    if (granted == null) {
      return false;
    }

    return switch (required) {
      case PUBLIC -> true;
      case READ -> granted == Permission.READ || granted == Permission.WRITE;
      case WRITE -> granted == Permission.WRITE;
    };
  }
}
