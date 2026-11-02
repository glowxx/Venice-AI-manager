FROM debian:bookworm-slim

# Enable multiarch for amd64 so x86_64 aapt2 from Android SDK runs via binfmt
RUN dpkg --add-architecture amd64 && \
    apt-get update -qq && \
    apt-get install -y -qq --no-install-recommends \
        openjdk-17-jdk-headless \
        libc6:amd64 \
        libstdc++6:amd64 \
        zlib1g:amd64 \
        wget \
        unzip \
        curl \
        git \
        ca-certificates && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/34.0.0

# Install Android SDK Command-line Tools
RUN mkdir -p /opt/android-sdk/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d /opt/android-sdk/cmdline-tools && \
    mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

# Accept licenses and install platform-tools, platforms;android-34, build-tools;34.0.0
RUN yes | sdkmanager --licenses > /dev/null && \
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" > /dev/null

WORKDIR /project
