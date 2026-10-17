# How these apps protect your accounts

Each app is a locked-down window onto one Claude artifact. It has no access to anything on the
phone beyond the internet.

## Your Google account
- **Google sign-in never happens inside the app.** Only `https://claude.ai` pages can fill the app
  window. Any other site, including `accounts.google.com`, opens in the phone's real browser.
- So a Google password is never typed into these apps, and no fake Google page can appear in them.
- Sign in to Claude with **email and code**.

## Your Claude account
- **No look-alike login pages.** A link or redirect to any non-Claude address is stopped and sent to
  the browser, where you can see the real address bar. This is checked twice: before a page loads
  and again when it starts.
- **Your sign-in stays in the app's private storage.** Android keeps it away from other apps, and it
  is **excluded from backups**, so it never leaves the phone.
- **HTTPS only**, with certificates from the phone's built-in authorities only. Certificates added by
  other apps or VPN profiles are not trusted. Google Safe Browsing is on.
- **No JavaScript bridge** is exposed to web pages. Downloads use a one-off private message channel
  that only the claude.ai page can receive, with a random key for each download.
- **No other permissions:** no files, camera, microphone, location or contacts. No debugging in
  release builds. No deep links, so other apps can't make it open a different page.
- Only `https`, `mailto` and `tel` links are passed on to other apps; anything else is ignored.

## The download itself
- Apps are built by GitHub Actions from the code in this repository. Every action is pinned to
  an exact version.
- The release notes list the **SHA-256** of every file, so you can check a download is genuine.
- Apps are signed with a private key kept only in this repository's secrets. Android refuses to
  install an "update" signed with any other key, so nobody else can replace your installed app.

## What you should still do
- Install only from this repository's release page.
- Keep Android and **Android System WebView** updated (Play Store), since the app uses the phone's
  built-in web engine.
- If you lose the phone: sign out of all sessions from Claude's settings on another device.
