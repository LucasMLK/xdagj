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
package io.xdag.config;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A sorted set of node capabilities
 */
public class CapabilityTreeSet {

    private final Set<Capability> capabilities;

    private CapabilityTreeSet(Set<Capability> capabilities) {
        this.capabilities = new TreeSet<>(capabilities);
    }

    /**
     * Create a CapabilityTreeSet from varargs of Capability
     */
    public static CapabilityTreeSet of(Capability... capabilities) {
        return new CapabilityTreeSet(Arrays.stream(capabilities).collect(Collectors.toSet()));
    }

    /**
     * Create a CapabilityTreeSet from an array of capability strings
     */
    public static CapabilityTreeSet of(String[] capabilityNames) {
        Set<Capability> caps = Arrays.stream(capabilityNames)
            .map(name -> {
                for (Capability cap : Capability.values()) {
                    if (cap.getName().equalsIgnoreCase(name)) {
                        return cap;
                    }
                }
                return null;
            })
            .filter(cap -> cap != null)
            .collect(Collectors.toSet());
        return new CapabilityTreeSet(caps);
    }

    /**
     * Convert to array of capability names
     */
    public String[] toArray() {
        return capabilities.stream()
            .map(Capability::getName)
            .toArray(String[]::new);
    }

    /**
     * Get the underlying set of capabilities
     */
    public Set<Capability> getCapabilities() {
        return capabilities;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CapabilityTreeSet that = (CapabilityTreeSet) o;
        return capabilities.equals(that.capabilities);
    }

    @Override
    public int hashCode() {
        return capabilities.hashCode();
    }

    @Override
    public String toString() {
        return capabilities.toString();
    }
}
