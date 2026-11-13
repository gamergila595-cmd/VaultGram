# VaultGram — Android release signing & build

Repository branch: `feature/vaultgram-clean-gallery-signed`.

## Important
- No backend required: VaultGram uses TDLib to connect to Telegram directly.
- This branch keeps Android `applicationId = id.vaultgram.app`.
- Future updates require **the same signing keystore**, the same applicationId, and increasing versionCode.
- Never publish a keystore, token, Telegram login code or passwords.
- The existing `main` branch has not been modified.

## One-time release signing
Use a trusted PC, Linux shell or Termux with a JDK. From the repository root:

```bash
bash scripts/create-release-keystore.sh
```

The script creates `vaultgram-release.jks` only if it doesn't already exist. Back it up securely OFF GitHub.

In GitHub repo → Settings → Secrets and variables → Actions, create four repository secrets:

| Name | Value |
|---|---|
| `VG_KEYSTORE_BASE64` | Exact base64 contents of the keystore file |
| `VG_KEYSTORE_PASSWORD` | Keystore password chosen above |
| `VG_KEY_ALIAS` | `vaultgram` |
| `VG_KEY_PASSWORD` | Same password chosen above |

Generate base64 locally: `base64 -w 0 vaultgram-release.jks` (GNU/Linux) or `base64 vaultgram-release.jks | tr -d '\\n'` (some other systems). Do not share this value in chat.

## Build
Go to GitHub Actions → **VaultGram Android CI and Signed Release** → Run workflow and choose branch `feature/vaultgram-clean-gallery-signed`.

On every branch push, GitHub compiles a **debug APK for CI testing**, which is NOT suitable for long-term updates.
If all four secrets exist, it additionally builds and verifies **VaultGram-v2-signed-release**. Install ONLY the signed release APK for normal use.

The signing workflow increments versionCode using 10000 + GitHub workflow run number. Keep this same workflow for future releases; don't reuse an old run number with a lower versionCode.

If a debug APK is already installed and signed with a different certificate, Android may require uninstall ONCE when switching to the permanent release key. Back up data before doing that.

## Telegram first launch
Obtain your own Telegram API ID/hash via the official Telegram API developer site; these are Telegram user API credentials, not the bot token. Log in using your own Telegram account, select the bot username, and let TDLib index the bot conversation. Your password/login code stays in the app, not in the repository.

## Release status
GitHub Actions compilation and Android-device behavior must be verified from an actual run. Do not treat the appearance of a workflow file as proof that an APK is working.
