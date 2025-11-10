#!/bin/sh

ulimit -n unlimited

XDAG_VERSION="${project.version}"
XDAG_JARNAME="xdagj-${XDAG_VERSION}-executable.jar"
XDAG_OPTS="-t"

# Auto-detect Java 21+ on macOS
if [ "$(uname)" = "Darwin" ] && [ -x /usr/libexec/java_home ]; then
  DETECTED_JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null)
  if [ -n "$DETECTED_JAVA_HOME" ]; then
    JAVA_HOME="$DETECTED_JAVA_HOME"
    export JAVA_HOME
  fi
fi

# Linux Java Home (uncomment and set if needed)
#JAVA_HOME="/usr/local/java/"

# MacOS Java Home (uncomment and set if auto-detection fails)
#JAVA_HOME=/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home/

# Use JAVA_HOME if set, otherwise use system java
if [ -n "$JAVA_HOME" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
else
  JAVA_CMD="java"
fi

# default JVM options
JAVA_OPTS="--add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED -Xms4g -Xmx4g -XX:+ExitOnOutOfMemoryError -XX:+UseZGC"

JAVA_HEAPDUMP="-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./logs/xdag-heapdump"

JAVA_GC_LOG="-Xlog:gc*,gc+heap=trace,gc+age=trace,safepoint:file=./logs/xdag-gc-%t.log:time,level,tid,tags:filecount=8,filesize=10m"

XDAGJ_VERSION="-Dxdagj.version=${XDAG_VERSION}"

if [ ! -d "logs" ];then
  mkdir "logs"
fi

# start kernel
"${JAVA_CMD}" ${JAVA_OPTS} ${JAVA_HEAPDUMP} ${JAVA_GC_LOG} ${XDAGJ_VERSION} -cp .:${XDAG_JARNAME} io.xdag.Bootstrap "$@"