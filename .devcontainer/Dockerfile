# Toolbox for the release bootstrap tasks (java, gh, gcloud); runs natively on arm64 and x86_64.
# App builds happen on the macOS host and in CI: Google ships the Linux Android build tools
# (AAPT2) for x86_64 only, and iOS needs Xcode.
# Everything comes straight from its vendor: no devcontainer features or third-party images.
FROM eclipse-temurin:21-jdk-noble

ARG USERNAME=dev
ENV ANDROID_HOME=/home/${USERNAME}/android-sdk

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl git gnupg unzip \
 && install -d -m 0755 /etc/apt/keyrings \
 && curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg -o /etc/apt/keyrings/githubcli.gpg \
 && echo "deb [signed-by=/etc/apt/keyrings/githubcli.gpg] https://cli.github.com/packages stable main" \
      > /etc/apt/sources.list.d/github-cli.list \
 && curl -fsSL https://packages.cloud.google.com/apt/doc/apt-key.gpg | gpg --dearmor -o /etc/apt/keyrings/cloud.google.gpg \
 && echo "deb [signed-by=/etc/apt/keyrings/cloud.google.gpg] https://packages.cloud.google.com/apt cloud-sdk main" \
      > /etc/apt/sources.list.d/google-cloud-sdk.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends gh google-cloud-cli \
 && rm -rf /var/lib/apt/lists/*

# Replace the image's default user with one that owns the mounted caches.
RUN (userdel -r ubuntu || true) \
 && useradd -m -u 1000 -s /bin/bash ${USERNAME} \
 && mkdir -p /home/${USERNAME}/.gradle /home/${USERNAME}/.config/gcloud /home/${USERNAME}/.config/gh /home/${USERNAME}/.hashiru-secrets

# AGP downloads the SDK platform and build tools it needs on the first build. This file records
# that the Android SDK license is accepted; building in this container implies accepting it.
RUN mkdir -p ${ANDROID_HOME}/licenses \
 && echo "24333f8a63b6825ea9c5514f83c2829b004d1fee" > ${ANDROID_HOME}/licenses/android-sdk-license \
 && chown -R ${USERNAME}:${USERNAME} /home/${USERNAME}

USER ${USERNAME}
