# Publishing with local Kokoro server config

This project intentionally supports a local Kokoro server URL for private LAN/VPN use, but that URL should not live in committed source code.

## Local machine setup

Use the committed example file as a template:

```bash
cp .env.example .env
$EDITOR .env
```

Set your private endpoint in `.env`:

```dotenv
KOKORO_SERVER_BASE=http://YOUR-LAN-OR-VPN-HOST:8880
```

`.env`, `.env.local`, and `local.properties` are ignored by git. Gradle embeds the resolved value into `BuildConfig.DEFAULT_SERVER_BASE` when it builds the APK.

Resolution order:

1. environment variable `KOKORO_SERVER_BASE`
2. Gradle property `kokoro.serverBase`, for example `./gradlew assembleDebug -Pkokoro.serverBase=http://YOUR-LAN-OR-VPN-HOST:8880`
3. `.env.local`
4. `.env`
5. `local.properties` using either `kokoro.serverBase=...` or `KOKORO_SERVER_BASE=...`
6. public default `http://10.0.2.2:8880`

The value is not in the repo, but it is in the built APK. Do not distribute a debug APK if the endpoint should remain private.

## Build metadata

Every APK embeds non-secret source metadata into `BuildConfig`: short Git SHA, Git commit date, branch, `git describe`, and clean/dirty tree state. Settings shows these values under **Build information** and includes a **Copy Build Info** button. A wall-clock build timestamp is deliberately omitted so otherwise identical builds are not made different merely by when they ran.

If the checkout does not have `.git` metadata, the Git values fall back to `unknown`; builds should still succeed from source archives. When `build_debug.sh` must download Gradle, it verifies the distribution's published SHA-256 checksum and ZIP integrity before execution.

## Before the first public GitHub push

If a private endpoint was committed previously, rewrite local history before pushing publicly. Replace the placeholder below with the old committed URL or host.

```bash
python3 -m pip install --user git-filter-repo

cat > /tmp/kokoro-reader-replacements.txt <<'EOF_REPLACEMENTS'
literal:http://OLD-LAN-OR-VPN-HOST:8880==>http://10.0.2.2:8880
literal:OLD-LAN-OR-VPN-HOST==>YOUR-LAN-OR-VPN-HOST
EOF_REPLACEMENTS

git filter-repo --replace-text /tmp/kokoro-reader-replacements.txt --force
```

Verify the old value no longer appears anywhere in reachable history:

```bash
git grep -n "OLD-LAN-OR-VPN-HOST" $(git rev-list --all) || true
git log --all -S"OLD-LAN-OR-VPN-HOST" --oneline
```

Then add your GitHub remote and push the rewritten history:

```bash
git remote add origin git@github.com:YOUR-USER/YOUR-REPO.git
git push -u origin main
```
