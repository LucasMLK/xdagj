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

package io.xdag.utils;

import java.util.Date;
import org.apache.commons.lang3.time.FastDateFormat;

/**
 * XDAG Time Utilities
 *
 * <p>XDAG uses two time representations:
 * <ul>
 *   <li><b>Epoch</b>: Full XDAG timestamp with 1/1024 second precision (e.g., 1552800481279)</li>
 *   <li><b>Epoch Number</b>: 64-second period number calculated as epoch >> 16 (e.g., 23693854)</li>
 * </ul>
 *
 * <p>Block stores only epoch number. Use this utility to convert between representations.
 */
public class XdagTime {

  // ==================== Current Time ====================

  /**
   * Get current XDAG epoch (full timestamp with 1/1024 second precision)
   *
   * @return Current XDAG epoch
   */
  public static long getCurrentEpoch() {
    long time_ms = System.currentTimeMillis();
    return timeMillisToEpoch(time_ms);
  }

  /**
   * Get current epoch number (64-second period)
   *
   * @return Current epoch number
   */
  public static long getCurrentEpochNumber() {
    return getEpochNumber(getCurrentEpoch());
  }

  /**
   * Get current main time (end of current epoch, used for creating main blocks)
   *
   * @return Current epoch end time
   */
  public static long getMainTime() {
    return getEndOfEpoch(getCurrentEpoch());
  }

  // ==================== Milliseconds <-> Epoch ====================

  /**
   * Convert milliseconds to XDAG epoch
   *
   * <p>XDAG uses 1/1024 second precision (not 1/1000).
   * Formula: XDAG_epoch = (milliseconds * 1024) / 1000
   *
   * @param ms Milliseconds since Unix epoch
   * @return XDAG epoch (1/1024 second units)
   */
  public static long timeMillisToEpoch(long ms) {
    // XDAG timestamp = (ms * 1024) / 1000
    // Using bit shift for efficiency: ms << 10 = ms * 1024
    return (ms << 10) / 1000;
  }

  /**
   * Convert XDAG epoch to milliseconds
   *
   * <p>XDAG uses 1/1024 second precision.
   * Formula: milliseconds = (XDAG_epoch * 1000) / 1024
   *
   * @param epoch XDAG epoch (1/1024 second units)
   * @return Milliseconds since Unix epoch
   */
  public static long epochToTimeMillis(long epoch) {
    // Milliseconds = (timestamp * 1000) / 1024
    // Using bit shift for efficiency: >> 10 = / 1024
    return (epoch * 1000) >> 10;
  }

  // ==================== Epoch <-> Epoch Number ====================

  /**
   * Get epoch number from full XDAG epoch
   *
   * <p>Epoch number represents 64-second periods.
   * Formula: epoch_number = epoch >> 16
   *
   * @param epoch Full XDAG epoch
   * @return Epoch number (64-second period)
   */
  public static long getEpochNumber(long epoch) {
    return epoch >> 16;
  }

  /**
   * Convert epoch number to XDAG epoch (start of the 64-second period)
   *
   * <p>Formula: epoch = epoch_number << 16
   *
   * @param epochNumber Epoch number (64-second period)
   * @return XDAG epoch at the start of the period
   */
  public static long epochNumberToEpoch(long epochNumber) {
    return epochNumber << 16;
  }

  /**
   * Convert epoch number to XDAG epoch timestamp at end of period (main block time).
   *
   * @param epochNumber Epoch number (64-second period)
   * @return XDAG epoch timestamp at end of the period
   */
  public static long epochNumberToMainTime(long epochNumber) {
    return (epochNumber << 16) | 0xffff;
  }

  /**
   * Convert epoch number directly to milliseconds at end of period.
   *
   * @param epochNumber Epoch number
   * @return Milliseconds corresponding to the end of the epoch
   */
  public static long epochNumberToTimeMillis(long epochNumber) {
    return epochToTimeMillis(epochNumberToMainTime(epochNumber));
  }

  // ==================== Epoch Utilities ====================

  /**
   * Get the end of epoch for a given XDAG epoch
   *
   * <p>Sets the lower 16 bits to 0xffff, representing the last moment of the epoch.
   * Used for main block creation.
   *
   * @param epoch XDAG epoch
   * @return Epoch end time
   */
  public static long getEndOfEpoch(long epoch) {
    return epoch | 0xffff;
  }

  /**
   * Check if the epoch is at the end of a 64-second period
   *
   * <p>Main blocks must have epoch at period end (lower 16 bits = 0xffff).
   *
   * @param epoch XDAG epoch
   * @return true if at epoch end
   */
  public static boolean isEndOfEpoch(long epoch) {
    return (epoch & 0xffff) == 0xffff;
  }

  // ==================== Compatibility Helpers ====================

  /**
   * Alias for legacy usage: convert XDAG epoch timestamp to epoch number.
   *
   * @param epochTimestamp XDAG epoch timestamp (1/1024s units)
   * @return epoch number
   */
  public static long getEpoch(long epochTimestamp) {
    return getEpochNumber(epochTimestamp);
  }

  /**
   * Get the end of epoch timestamp (legacy helper used in tests).
   *
   * @param epochTimestamp XDAG epoch timestamp
   * @return XDAG epoch timestamp with lower 16 bits set
   */
  public static long getEndTimeMillisOfEpoch(long epochTimestamp) {
    return getEndOfEpoch(epochTimestamp);
  }

  /**
   * Convert epoch number to XDAG epoch timestamp (start of epoch).
   *
   * @param epochNumber epoch number
   * @return XDAG epoch timestamp at epoch start
   */
  public static long epochNumberToTimestamp(long epochNumber) {
    return epochNumberToEpoch(epochNumber);
  }

  // ==================== Formatting ====================

  /**
   * Format date to string in "yyyy-MM-dd HH:mm:ss" pattern
   *
   * @param date Date to format
   * @return Formatted date string
   */
  public static String format(Date date) {
    return FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss").format(date);
  }

}
