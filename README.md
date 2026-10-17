# Artifact apps

Real Android apps for Claude artifacts. Each app opens one artifact full screen and shows up in the
phone's app list like any other app. It shows the live artifact, so data and updates are always the
same as on claude.ai.

## Install on a phone

1. Open the **[latest release](../../releases/tag/apps)** on the phone and download the app's `.apk`.
2. Open the file and allow your browser to install apps when Android asks.
3. Open the app and sign in to Claude with **email and code** (Google sign-in doesn't work inside apps).

A newer version installs over the old one; nothing needs uninstalling.

## Add another artifact

Add an entry to [`apps.json`](apps.json) and commit. GitHub builds every app again (about 5 minutes)
and replaces the files on the release page.

```jsonc
{
  "id": "thesis",            // lowercase letters/digits, never change it later
  "name": "Thesis Tracker",  // the name under the icon
  "label": "TT",             // letters on the icon if there's no picture
  "color": "#2E6B4F",        // icon and status-bar colour
  "url": "https://claude.ai/artifact/..."
}
```

Put the app's picture in `icons/<id>.png` (or `.jpg`), square, at least 192 px. It becomes the
icon in every launcher shape.

## Security

See [SECURITY.md](SECURITY.md). In short: Google sign-in never happens inside the app, only
claude.ai pages can show in it, and it has no permissions beyond the internet.

## Notes

- Links to other websites (Google Sheets, papers…) open in the phone's browser.
- Downloads from the artifact are saved to the phone's Downloads folder.
- The signing key lives in the repository secrets `KEYSTORE_B64` and `KEYSTORE_PASSWORD`. Keep it:
  apps signed with a different key can't update the installed ones.
