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

PROTOBUF_TEXTFORMAT_INPUT=$(mktemp -p $SCRIPTS_TMPDIR --suffix=.txt cluster-access-pass.XXXXXXXXXXXX)

while read -p "Cluster ID (enter to finish): " CLUSTER_ID && test "$CLUSTER_ID" != ""
do
  CLUSTER_ID=${CLUSTER_ID//\"/\\\"}
  printf 'clusterIds: "%s"\n' "$CLUSTER_ID" >> $PROTOBUF_TEXTFORMAT_INPUT
done

echo ""

ret=0
read -p "Description (leave empty for none): " DESC || ret=$?
if [[ $ret -eq 0 ]]; then
  DESC=${DESC//\"/\\\"}
  printf 'description: "%b"\n' "$DESC" >> $PROTOBUF_TEXTFORMAT_INPUT
fi

echo ""

PROTOBUF_OUTPUT="$(basename "$PROTOBUF_TEXTFORMAT_INPUT" .txt).bin"
$PROTOC \
  -I dit4c-common/target/protobuf_external/ \
  -I dit4c-common/src/main/protobuf/ \
  --encode=dit4c.protobuf.ClusterAccessPass \
  dit4c-common/src/main/protobuf/tokens.proto \
  < $PROTOBUF_TEXTFORMAT_INPUT > $PROTOBUF_OUTPUT

echo "###################"
echo "Cluster Access Pass"
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

ARMORED_PASS=$(gpg2 --sign \
  --armor \
  --digest-algo SHA512 \
  "${SIGNING_KEYS[@]/#/--sign-with=}" \
  --ask-sig-expire \
  < $PROTOBUF_OUTPUT)

echo "###################"
echo "Signed Cluster Access Pass"
echo "Using:"
gpg2 --list-keys --with-fingerprint --with-colons "${SIGNING_KEYS[@]}" | \
  sed -ne '/^fpr/p' | cut -d: -f 10
echo "Armored:"
echo "$ARMORED_PASS"

URL_ENCODED_PASS=$(echo "$ARMORED_PASS" | \
  gpg2 --dearmor | \
  base64 -w 0 | \
  sed -e "y/+\//-_/;s/=*$//")

echo "URL-encoded:"
echo "$URL_ENCODED_PASS"

rm "$PROTOBUF_TEXTFORMAT_INPUT" "$PROTOBUF_OUTPUT"
