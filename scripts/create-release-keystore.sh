#!/usr/bin/env bash
set -euo pipefail
FILE="${1:-vaultgram-release.jks}"
if [[ -e "$FILE" ]]; then
  echo "Keystore exists: $FILE; refusing overwrite." >&2
  exit 1
fi
command -v keytool >/dev/null || { echo "Install a JDK with keytool first." >&2; exit 1; }
read -r -s -p 'New release keystore password (save it permanently): ' VG_STORE_PASSWORD
echo
if [[ ${#VG_STORE_PASSWORD} -lt 12 ]]; then echo "Password must have at least 12 characters." >&2; exit 1; fi
export VG_STORE_PASSWORD
umask 077
keytool -genkeypair -noprompt -alias vaultgram -keyalg RSA -keysize 3072 \
  -validity 10000 -keystore "$FILE" -storepass:env VG_STORE_PASSWORD \
  -keypass:env VG_STORE_PASSWORD -dname "CN=VaultGram Release, OU=Android, O=Personal, C=ID"
unset VG_STORE_PASSWORD
echo "Keystore created: $FILE"
echo "Set GitHub secrets: VG_KEYSTORE_BASE64 (base64 of this file),"
echo "VG_KEYSTORE_PASSWORD, VG_KEY_ALIAS=vaultgram, VG_KEY_PASSWORD (same password)."
echo "Back up this keystore offline. Never commit it to Git."
