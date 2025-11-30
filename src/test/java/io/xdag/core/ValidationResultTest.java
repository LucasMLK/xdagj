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

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for ValidationResult
 *
 * @since @since XDAGJ
 */
public class ValidationResultTest {

    @Test
    public void testSuccessCreation() {
        // Create success result
        ValidationResult result = ValidationResult.success();

        // Verify
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertFalse(result.isError());
        assertNull(result.getError());
    }

    @Test
    public void testErrorCreation() {
        // Create error result
        String errorMessage = "Insufficient balance";
        ValidationResult result = ValidationResult.error(errorMessage);

        // Verify
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.isError());
        assertEquals(errorMessage, result.getError());
    }

    @Test
    public void testErrorWithNullMessage() {
        // Create error result with null message
        ValidationResult result = ValidationResult.error(null);

        // Verify - still an error even with null message
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.isError());
        assertNull(result.getError());
    }

    @Test
    public void testErrorWithEmptyMessage() {
        // Create error result with empty message
        ValidationResult result = ValidationResult.error("");

        // Verify
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.isError());
        assertEquals("", result.getError());
    }

    @Test
    public void testToStringSuccess() {
        // Create success result
        ValidationResult result = ValidationResult.success();

        // Verify toString
        String str = result.toString();
        assertNotNull(str);
        assertEquals("ValidationResult{success}", str);
    }

    @Test
    public void testToStringError() {
        // Create error result
        String errorMessage = "Invalid nonce";
        ValidationResult result = ValidationResult.error(errorMessage);

        // Verify toString
        String str = result.toString();
        assertNotNull(str);
        assertEquals("ValidationResult{error='Invalid nonce'}", str);
    }

    @Test
    public void testMultipleSuccessInstances() {
        // Create multiple success instances
        ValidationResult result1 = ValidationResult.success();
        ValidationResult result2 = ValidationResult.success();

        // Both should be success
        assertTrue(result1.isSuccess());
        assertTrue(result2.isSuccess());

        // They are not the same instance (factory creates new objects)
        assertNotSame(result1, result2);
    }

    @Test
    public void testMultipleErrorInstances() {
        // Create multiple error instances
        ValidationResult result1 = ValidationResult.error("Error 1");
        ValidationResult result2 = ValidationResult.error("Error 2");

        // Both should be errors
        assertTrue(result1.isError());
        assertTrue(result2.isError());

        // Different error messages
        assertNotEquals(result1.getError(), result2.getError());
    }

    @Test
    public void testIsSuccessAndIsErrorMutuallyExclusive() {
        // Success case
        ValidationResult success = ValidationResult.success();
        assertTrue(success.isSuccess() && !success.isError());

        // Error case
        ValidationResult error = ValidationResult.error("error");
        assertTrue(error.isError() && !error.isSuccess());
    }

    @Test
    public void testLongErrorMessage() {
        // Create error with long message
        String longMessage = "This is a very long error message that contains detailed information about what went wrong during validation of the transaction including account balance, nonce, and signature verification details";
        ValidationResult result = ValidationResult.error(longMessage);

        // Verify
        assertTrue(result.isError());
        assertEquals(longMessage, result.getError());
    }

    @Test
    public void testErrorMessageWithSpecialCharacters() {
        // Create error with special characters
        String specialMessage = "Error: balance=100, required=200 (shortfall: -100)";
        ValidationResult result = ValidationResult.error(specialMessage);

        // Verify
        assertTrue(result.isError());
        assertEquals(specialMessage, result.getError());
    }
}
