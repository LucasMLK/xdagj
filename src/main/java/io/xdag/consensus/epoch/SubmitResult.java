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
package io.xdag.consensus.epoch;

import lombok.Getter;

/**
 * SubmitResult - Result of submitting a solution to the SolutionCollector.
 *
 * <p>Indicates whether the solution was accepted for collection or rejected,
 * along with a descriptive message.
 *
 * <p>Part of BUG-CONSENSUS-002 fix - solutions are collected (not immediately imported)
 * and processed at epoch end.
 *
 * @see SolutionCollector
 */
@Getter
public class SubmitResult {

    /**
     * Whether the solution was accepted.
     * -- GETTER --
     *  Check if the solution was accepted.
     *
     * @return true if accepted, false if rejected

     */
    private final boolean accepted;

    /**
     * Descriptive message explaining the result.
     */
    private final String message;

    private SubmitResult(boolean accepted, String message) {
        this.accepted = accepted;
        this.message = message;
    }

    /**
     * Create an accepted result.
     *
     * @param message Success message
     * @return Accepted SubmitResult
     */
    public static SubmitResult accepted(String message) {
        return new SubmitResult(true, message);
    }

    /**
     * Create a rejected result.
     *
     * @param message Rejection reason
     * @return Rejected SubmitResult
     */
    public static SubmitResult rejected(String message) {
        return new SubmitResult(false, message);
    }

  /**
     * Get the error message (for rejected submissions).
     *
     * @return Error message, or success message if accepted
     */
    public String getErrorMessage() {
        return message;
    }

    @Override
    public String toString() {
        return String.format("SubmitResult{%s: %s}",
                accepted ? "ACCEPTED" : "REJECTED",
                message);
    }
}
