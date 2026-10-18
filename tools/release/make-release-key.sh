#!/usr/bin/env bash
# Creates the OpenUTM release signing key, once, for whoever owns releases (docs/12, Distribution).
# Every update must be signed with the same key: keep the keystore and its password in the
# organisation's password manager and a second offline copy. Losing it means pilots must uninstall
# (and lose unsynced flights) to move to a new key.
#
#   tools/release/make-release-key.sh [keystore path]     default: ~/.openutm/openutm-release.jks
#
# Writes the four OPENUTM_RELEASE_* lines to ~/.gradle/gradle.properties (mode 600), never into the repo,
# and prints the key's SHA-256 certificate fingerprint (labs can compare it with `apksigner verify --print-certs`).
set -euo pipefail
store=${1:-$HOME/.openutm/openutm-release.jks}
alias=openutm-release
props=$HOME/.gradle/gradle.properties
[[ -e "$store" ]] && { echo "$store already exists: refusing to overwrite a release key" >&2; exit 2; }
grep -q '^OPENUTM_RELEASE_STORE_FILE=' "$props" 2>/dev/null && { echo "$props already names a release key" >&2; exit 2; }
command -v keytool >/dev/null || { echo "needs keytool (a JDK 17)" >&2; exit 2; }
mkdir -p "$(dirname "$store")" "$(dirname "$props")"
pass=$(openssl rand -base64 30 | tr -d '/+=' | cut -c1-32)
keytool -genkeypair -keystore "$store" -storetype PKCS12 -alias "$alias" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$pass" -keypass "$pass" -dname "CN=OpenUTM Flight release, O=OpenUTM" >/dev/null
chmod 600 "$store"
umask 077
{
  echo "OPENUTM_RELEASE_STORE_FILE=$store"
  echo "OPENUTM_RELEASE_STORE_PASSWORD=$pass"
  echo "OPENUTM_RELEASE_KEY_ALIAS=$alias"
  echo "OPENUTM_RELEASE_KEY_PASSWORD=$pass"
} >> "$props"
chmod 600 "$props"
echo "key: $store (alias $alias); password stored in $props"
keytool -list -v -keystore "$store" -storepass "$pass" -alias "$alias" | grep -E "SHA256:" | sed 's/^\s*/certificate /'
echo "Now copy the keystore and the password from $props into the password manager."
