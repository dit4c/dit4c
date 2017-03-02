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

PROTOBUF_TEXTFORMAT_INPUT=$(mktemp -p $SCRIPTS_TMPDIR --suffix=.txt remove-node.XXXXXXXXXXXX)

read -p "Cluster ID: " CLUSTER_ID && test "$CLUSTER_ID" != ""
CLUSTER_ID=${CLUSTER_ID//\"/\\\"}
printf 'clusterId: "%s"\n' "$CLUSTER_ID" >> $PROTOBUF_TEXTFORMAT_INPUT

while read -p "SSH Fingerprint (enter to finish): " NODE_FINGERPRINT && test "$NODE_FINGERPRINT" != ""
do
  NODE_FINGERPRINT=${NODE_FINGERPRINT//\"/\\\"}
  printf 'sshHostKeyFingerprints: "%s"\n' "$NODE_FINGERPRINT" >> $PROTOBUF_TEXTFORMAT_INPUT
done

echo ""
echo "###################"
echo "TEXT:"
cat $PROTOBUF_TEXTFORMAT_INPUT

PROTOBUF_COOLDOWN_OUTPUT="$SCRIPTS_TMPDIR/$(basename "$PROTOBUF_TEXTFORMAT_INPUT" .txt).cooldown.bin"
sed -e '1icoolDownNodes: \{' -e '$a\}' $PROTOBUF_TEXTFORMAT_INPUT | \
  $PROTOC \
    -I dit4c-scheduler/target/protobuf_external/ \
    -I dit4c-scheduler/src/main/protobuf/ \
    --encode=dit4c.scheduler.api.ApiMessage \
    dit4c-scheduler/src/main/protobuf/dit4c/scheduler/api.proto \
    > $PROTOBUF_COOLDOWN_OUTPUT

PROTOBUF_DECOMMISSION_OUTPUT="$SCRIPTS_TMPDIR/$(basename "$PROTOBUF_TEXTFORMAT_INPUT" .txt).decommission.bin"
sed -e '1idecommissionNodes: \{' -e '$a\}' $PROTOBUF_TEXTFORMAT_INPUT | \
  $PROTOC \
    -I dit4c-scheduler/target/protobuf_external/ \
    -I dit4c-scheduler/src/main/protobuf/ \
    --encode=dit4c.scheduler.api.ApiMessage \
    dit4c-scheduler/src/main/protobuf/dit4c/scheduler/api.proto \
    > $PROTOBUF_DECOMMISSION_OUTPUT

echo "Cool Down BINARY:"
hexdump -C $PROTOBUF_COOLDOWN_OUTPUT
echo "Decommission BINARY:"
hexdump -C $PROTOBUF_DECOMMISSION_OUTPUT
echo "###################"

SIGNING_KEYS=()
while read -p "GPG key to sign with (enter to finish): " SIGNING_KEY && test "$SIGNING_KEY" != ""
do
  SIGNING_KEYS+=("$SIGNING_KEY")
done

echo "Signing cool down message:"
ARMORED_COOLDOWN_MESSAGE=$(gpg2 --sign \
  --armor \
  --digest-algo SHA512 \
  "${SIGNING_KEYS[@]/#/--sign-with=}" \
  --ask-sig-expire \
  < "$PROTOBUF_COOLDOWN_OUTPUT")
echo ""
echo "Signing decommission message:"
ARMORED_DECOMMISSION_MESSAGE=$(gpg2 --sign \
  --armor \
  --digest-algo SHA512 \
  "${SIGNING_KEYS[@]/#/--sign-with=}" \
  --ask-sig-expire \
  < "$PROTOBUF_DECOMMISSION_OUTPUT")

echo "###################"
echo ""
echo "Signed requests"
echo "Using:"
gpg2 --list-keys --with-fingerprint --with-colons "${SIGNING_KEYS[@]}" | \
  sed -ne '/^fpr/p' | cut -d: -f 10
echo ""
echo "Armored cool down request:"
echo "$ARMORED_COOLDOWN_MESSAGE"
echo ""
echo "Armored decommission request:"
echo "$ARMORED_DECOMMISSION_MESSAGE"

rm "$PROTOBUF_TEXTFORMAT_INPUT" "$PROTOBUF_COOLDOWN_OUTPUT" "$PROTOBUF_DECOMMISSION_OUTPUT"
