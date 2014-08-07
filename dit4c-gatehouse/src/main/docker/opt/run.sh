#!/bin/bash

echo "set \$portal \"$PORTAL_URL\";" > /etc/nginx/conf.d/portal_url.conf

nginx -t

exec /usr/bin/supervisord -n