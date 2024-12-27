package io.xdag.core;

public interface XdagLifecycle {
    void start();

    void stop();

    boolean isRunning();

}
