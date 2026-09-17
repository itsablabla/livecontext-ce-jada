#!/bin/sh
set -eu
if [ "${BOOTSTRAP_AUTH_REALM:-Setup}" != "off" ]; then
    : "${BOOTSTRAP_PASSWORD:?Set a bootstrap password in Coolify}"
    printf '%s\n' "$BOOTSTRAP_PASSWORD" | htpasswd -niB jada-setup > /etc/nginx/bootstrap.htpasswd
    chmod 644 /etc/nginx/bootstrap.htpasswd
fi
