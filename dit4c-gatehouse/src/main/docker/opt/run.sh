#!/bin/bash

export DOCKER_ADDR=${DOCKER_ADDR:-172.17.42.1}
export GATEHOUSE_URL=${GATEHOUSE_URL:-"http://127.0.0.1:8080"}
export MACHINESHOP_URL=${MACHINESHOP_URL:-"http://172.17.42.1:8080"}

cat > /etc/nginx/conf.d/variables.conf <<VARIABLES
set \$portal "$PORTAL_URL";
set \$docker "$DOCKER_ADDR";
set \$gatehouse "$GATEHOUSE_URL";
set \$machineshop "$MACHINESHOP_URL";
VARIABLES

nginx -t

exec /usr/bin/supervisord -n
