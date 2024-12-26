package io.xdag.config;

public interface XdagLifecycle {
    void start();

    void stop();

    boolean isRunning();

}
