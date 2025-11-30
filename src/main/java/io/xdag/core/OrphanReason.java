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
package io.xdag.core;

/**
 * OrphanReason - 孤块原因分类
 *
 * <p>区分两种完全不同的orphan blocks：
 * <ul>
 *   <li><strong>MISSING_DEPENDENCY</strong>: 缺少父block，等待依赖到达后需要重试</li>
 *   <li><strong>LOST_COMPETITION</strong>: 输掉epoch竞争，永远不会成为main block</li>
 * </ul>
 *
 * <h2>为什么需要区分？</h2>
 * <p>在XDAG 1.0b中，每个epoch（64秒）最多16个候选blocks，只有1个能成为main block。
 * 其他15个会永久成为orphans（输掉竞争），但它们的父blocks都存在，不需要重试。
 *
 * <p><strong>BUG-ORPHAN-001修复</strong>：OrphanManager只应该重试MISSING_DEPENDENCY类型的orphans，
 * 而不应该重试LOST_COMPETITION类型的orphans。
 *
 * @since XDAGJ 5.2.0
 * @see OrphanManager
 */
public enum OrphanReason {
  /**
   * 缺少父block依赖
   *
   * <p>场景：P2P网络中blocks乱序到达，子block先到，父block还未到达
   * <p>示例：Block C引用Block B，但Block B还未从网络同步到本地
   * <p>行为：需要重试，等父block到达后会成功导入
   */
  MISSING_DEPENDENCY((byte) 0),

  /**
   * 输掉epoch竞争
   *
   * <p>场景：同一个epoch内有多个候选blocks，只有difficulty最小的能成为main block
   * <p>示例：Epoch 27569886有2个blocks：
   * <ul>
   *   <li>Block A (difficulty=20) - 输家，成为orphan</li>
   *   <li>Block B (difficulty=36) - 赢家，成为main block</li>
   * </ul>
   * <p>行为：不需要重试，永远不会成为main block（除非chain reorganization）
   */
  LOST_COMPETITION((byte) 1);

  private final byte code;

  OrphanReason(byte code) {
    this.code = code;
  }

  /**
   * 获取存储编码
   *
   * @return 1字节编码
   */
  public byte getCode() {
    return code;
  }

  /**
   * 从存储编码解析
   *
   * @param code 1字节编码
   * @return OrphanReason枚举值
   * @throws IllegalArgumentException 如果编码无效
   */
  public static OrphanReason fromCode(byte code) {
    switch (code) {
      case 0:
        return MISSING_DEPENDENCY;
      case 1:
        return LOST_COMPETITION;
      default:
        throw new IllegalArgumentException("Unknown OrphanReason code: " + code);
    }
  }

  /**
   * 是否需要重试
   *
   * @return true表示需要重试
   */
  public boolean shouldRetry() {
    return this == MISSING_DEPENDENCY;
  }
}
