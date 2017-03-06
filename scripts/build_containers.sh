#!/bin/bash

set -ex

DOCKER2ACI=$(which docker2aci)
ACBUILD=$(which acbuild)
GPG=$(which gpg2)
# Print versions (and fail if not found)
$DOCKER2ACI -version
$ACBUILD version
$GPG --version | head -2

SCRIPT_DIR=$(dirname $(readlink -f $0))
BASE_DIR=$(dirname "$SCRIPT_DIR")
WORKING_DIR="${BASE_DIR}/target/containers"

# Build distributions
cd "$BASE_DIR"
sbt universal:packageZipTarball

mkdir -p "$WORKING_DIR"
cd "$WORKING_DIR"
BASE_ACI="${WORKING_DIR}/library-openjdk-8-jre-alpine.aci"
if [[ ! -e "$BASE_ACI" ]]; then
  docker2aci docker://library/openjdk:8-jre-alpine
fi

extract_project_tarball () {
  local PROJECT_NAME
  PROJECT_NAME=$1
  rm -rf "$WORKING_DIR/$PROJECT_NAME"
  mkdir -p "$WORKING_DIR/$PROJECT_NAME"
  tar xvf "$BASE_DIR/$PROJECT_NAME/target/universal/"*".tgz" \
    --strip-components 1 -C "$WORKING_DIR/$PROJECT_NAME"
  rm -rf "$WORKING_DIR/$PROJECT_NAME/bin/$PROJECT_NAME.bat" "$WORKING_DIR/$PROJECT_NAME/share/doc"
}

# Portal
extract_project_tarball "dit4c-portal"
sudo rm -rf .acbuild
sudo $ACBUILD begin "$BASE_ACI"
sudo "PATH=$PATH" $ACBUILD run --engine chroot -- sh -c "apk update && apk add bash zsh && rm -rf /var/cache/apk/*"
sudo $ACBUILD copy "$WORKING_DIR/dit4c-portal" /opt/dit4c-portal
sudo $ACBUILD copy "$SCRIPT_DIR/extra/start_portal.zsh" /start_portal
sudo $ACBUILD set-name dit4c-portal
sudo $ACBUILD set-user nobody
sudo $ACBUILD set-group nogroup
sudo $ACBUILD port add http tcp 9000
sudo $ACBUILD port add ssh tcp 2222
sudo $ACBUILD set-exec /start_portal
sudo $ACBUILD --debug write --overwrite dit4c-portal.linux.amd64.aci
sudo $ACBUILD end
sudo chown $(whoami) dit4c-portal.linux.amd64.aci

# Scheduler
extract_project_tarball "dit4c-scheduler"
sudo rm -rf .acbuild
sudo $ACBUILD begin "$BASE_ACI"
sudo "PATH=$PATH" $ACBUILD run --engine chroot -- sh -c "apk update && apk add bash && rm -rf /var/cache/apk/*"
sudo $ACBUILD copy "$WORKING_DIR/dit4c-scheduler" /opt/dit4c-scheduler
sudo $ACBUILD set-name dit4c-scheduler
sudo $ACBUILD set-user nobody
sudo $ACBUILD set-group nogroup
sudo $ACBUILD set-exec -- /opt/dit4c-scheduler/bin/dit4c-scheduler
sudo $ACBUILD --debug write --overwrite dit4c-scheduler.linux.amd64.aci
sudo $ACBUILD end
sudo chown $(whoami) dit4c-scheduler.linux.amd64.aci

# Signing
SIGNING_KEY="$BASE_DIR/signing.key"
if [[ -e "$SIGNING_KEY" ]]; then
  mkdir -p "$WORKING_DIR/tmp"
  TMP_KEYRING=$(mktemp -p "$WORKING_DIR/tmp")
  GPG_FLAGS="--batch --no-default-keyring --keyring $TMP_KEYRING"
  $GPG $GPG_FLAGS --import "$SIGNING_KEY"
  for f in "$WORKING_DIR/dit4c-"*".aci"; do
    rm -f "${f}.asc"
    $GPG $GPG_FLAGS --armour --detach-sign "$f"
  done
  rm $TMP_KEYRING
fi
