#!/bin/bash

set -e

SCRIPT_DIR=$(dirname $0)
BASE_DIR=$(dirname $SCRIPT_DIR)
SCRIPTS_TMPDIR="$BASE_DIR/target/script_scratch"
PROTOC="$SCRIPTS_TMPDIR/protobuf/bin/protoc"

if [[ ! -x "$PROTOC" ]]; then
  mkdir -p "$SCRIPTS_TMPDIR/protobuf"
  curl -sL "https://github.com/google/protobuf/releases/download/v3.1.0/protoc-3.1.0-linux-x86_64.zip" > \
    "$SCRIPTS_TMPDIR/protoc.zip"
  unzip -d "$SCRIPTS_TMPDIR/protobuf" "$SCRIPTS_TMPDIR/protoc.zip"
fi

echo "Using" $($PROTOC --version)

PROTOBUF_TEXTFORMAT_INPUT=$(mktemp -p $SCRIPTS_TMPDIR --suffix=.txt add-node.XXXXXXXXXXXX)

printf 'addNode: {\n' >> $PROTOBUF_TEXTFORMAT_INPUT

read -p "Cluster ID: " CLUSTER_ID && test "$CLUSTER_ID" != ""
CLUSTER_ID=${CLUSTER_ID//\"/\\\"}
printf 'clusterId: "%s"\n' "$CLUSTER_ID" >> $PROTOBUF_TEXTFORMAT_INPUT

read -p "Host: " NODE_HOST && test "$NODE_HOST" != ""
printf 'host: "%s"\n' "$NODE_HOST" >> $PROTOBUF_TEXTFORMAT_INPUT

read -p "Port: " NODE_PORT && test "$NODE_PORT" != ""
printf 'port: %d\n' $NODE_PORT >> $PROTOBUF_TEXTFORMAT_INPUT

read -p "Username: " NODE_USERNAME && test "$NODE_USERNAME" != ""
printf 'username: "%s"\n' "$NODE_USERNAME" >> $PROTOBUF_TEXTFORMAT_INPUT

while read -p "SSH Fingerprint (enter to finish): " NODE_FINGERPRINT && test "$NODE_FINGERPRINT" != ""
do
  NODE_FINGERPRINT=${NODE_FINGERPRINT//\"/\\\"}
  printf 'sshHostKeyFingerprints: "%s"\n' "$NODE_FINGERPRINT" >> $PROTOBUF_TEXTFORMAT_INPUT
done

printf '}\n' >> $PROTOBUF_TEXTFORMAT_INPUT

echo ""

PROTOBUF_OUTPUT="$(basename "$PROTOBUF_TEXTFORMAT_INPUT" .txt).bin"
$PROTOC \
  -I dit4c-scheduler/target/protobuf_external/ \
  -I dit4c-scheduler/src/main/protobuf/ \
  --encode=dit4c.scheduler.api.ApiMessage \
  dit4c-scheduler/src/main/protobuf/dit4c/scheduler/api.proto \
  < $PROTOBUF_TEXTFORMAT_INPUT > $PROTOBUF_OUTPUT

echo "###################"
echo "Add node request"
echo "TEXT:"
cat $PROTOBUF_TEXTFORMAT_INPUT
echo "BINARY:"
hexdump -C $PROTOBUF_OUTPUT
echo "###################"

SIGNING_KEYS=()
while read -p "GPG key to sign with (enter to finish): " SIGNING_KEY && test "$SIGNING_KEY" != ""
do
  SIGNING_KEYS+=("$SIGNING_KEY")
done

ARMORED_MESSAGE=$(gpg2 --sign \
  --armor \
  --digest-algo SHA512 \
  "${SIGNING_KEYS[@]/#/--sign-with=}" \
  --ask-sig-expire \
  < $PROTOBUF_OUTPUT)

echo "###################"
echo "Signed add node request"
echo "Using:"
gpg2 --list-keys --with-fingerprint --with-colons "${SIGNING_KEYS[@]}" | \
  sed -ne '/^fpr/p' | cut -d: -f 10
echo "Armored:"
echo "$ARMORED_MESSAGE"

rm "$PROTOBUF_TEXTFORMAT_INPUT" "$PROTOBUF_OUTPUT"
