# The viewer as served on the cluster, see deploy/. Build `dist` first (`npm run build`).
# Nothing runs at build time, so it builds for the cluster's arm64 nodes on any machine.
FROM nginxinc/nginx-unprivileged:1.31.7-alpine@sha256:b5047b9b76845eac124358373c6852377c52e4b05834a47647e955dd91cb8f1e
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/
