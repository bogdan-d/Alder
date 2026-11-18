# Crash worker

Alder's **Send report** posts the saved crash here, and this Cloudflare Worker files it as a GitHub issue with the
`crash` label. If the same error is already open, the worker adds a comment to that issue. The GitHub token stays in
Cloudflare and is never in the APK.

A report holds the Alder version, the Android version, the TV model and the stack trace. Web addresses are cut to
their host and long keys are removed on the TV before it sends anything. Reports have no account details.

## Deploy once

1. Create a fine-grained GitHub token: only the Alder repository, **Issues: Read and write**.
2. Create the `crash` label in the repository.
3. From this folder:

   ```
   npx wrangler login
   npx wrangler secret put GITHUB_TOKEN
   npx wrangler deploy
   ```

4. Put the URL that `deploy` prints into `Crashes.ENDPOINT` in `app/src/main/java/dev/alder/Crashes.kt`.

Reading a report: release builds are shrunk by R8. Decode a trace with the `mapping.txt` from that release build
(`retrace mapping.txt trace.txt`).
