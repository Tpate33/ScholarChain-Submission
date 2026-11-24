# Future human publication steps — approval required

```text
FUTURE HUMAN STEPS — NOT EXECUTED; publication requires explicit approval

Local publication export (never the parent directory):
/private/tmp/scholarchain-publication-01/publication
Verification source commit Q:
69d63538a57914f471eb199380e07570182a491f

The final publication SHA may include later evidence-only commits. Approval must
name the final SHA in the completion report; Q is NOT automatically that approval.

The clean-clone gate, privacy review and final evidence commit must identify the actual final
approved full commit first. If later evidence changes HEAD, replace the Q value
below with the newly approved exact full SHA; never silently publish a different
commit. No final tag exists yet. The commands below deliberately name a NEW
repository, not the original ScholarChain-Project repository. If that proposed
name already exists, stop and obtain approval of a different new name. Do not
fork, transfer, change original visibility, mirror, force-push or push all refs.

AFTER explicit approval of repository name, exact SHA, tag and public disclosure:

1. Human creates an EMPTY independent public repository (no source/clone/push,
   template, README, license or .gitignore flags):

   gh repo create Tpate33/ScholarChain-Submission --public --description 'ScholarChain sanitized course submission'

   Verify the created repository is public and independent:

   gh repo view Tpate33/ScholarChain-Submission --json nameWithOwner,url,isPrivate,isFork

   Require nameWithOwner=Tpate33/ScholarChain-Submission, isPrivate=false,
   isFork=false. An existing unrelated repository is not an acceptable target.

2. Human verifies the exact approved local commit and creates ONLY the approved
   lightweight final tag. Set the explicitly approved final SHA, then stop if the
   equality check fails. Do not substitute Q merely because it appears above:

   git -C /private/tmp/scholarchain-publication-01/publication rev-parse HEAD
   git -C /private/tmp/scholarchain-publication-01/publication status --porcelain=v1 --untracked-files=all
   SCHOLARCHAIN_APPROVED_SHA='REPLACE_WITH_THE_EXPLICITLY_APPROVED_FINAL_40_HEX_SHA'
   test "$(git -C /private/tmp/scholarchain-publication-01/publication rev-parse HEAD)" = "$SCHOLARCHAIN_APPROVED_SHA"
   git -C /private/tmp/scholarchain-publication-01/publication -c tag.gpgSign=false tag submission-2026-11-25 "$SCHOLARCHAIN_APPROVED_SHA"

   Require HEAD=the approved full SHA, clean status and tag resolving to that SHA.
   The local metadata-only tag is not a human acceptance signature or a claim of
   AWS/course completion.

3. Human pushes EXACTLY main and that one tag. The two per-command overrides are
   needed because this export intentionally rejects network transport/push hooks.
   They apply only to this explicitly approved push, not global Git configuration.
   Authenticate personally if required; agents do not inspect credentials:

   git -C /private/tmp/scholarchain-publication-01/publication -c protocol.https.allow=always -c core.hooksPath=/dev/null push https://github.com/Tpate33/ScholarChain-Submission.git refs/heads/main:refs/heads/main refs/tags/submission-2026-11-25:refs/tags/submission-2026-11-25

4. After public push, verify a genuinely anonymous HTTPS clone in a fresh empty
   directory. This command drops the inherited environment, disables system/global
   Git configuration, prompts, askpass, helpers and cookies, and suppresses any
   generated Authorization/Cookie header. HOME is never assigned or repurposed:

   ANON_DIR=$(mktemp -d /private/tmp/scholarchain-anonymous.XXXXXX)
   env -i PATH=/usr/bin:/bin:/usr/sbin:/sbin GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0 GIT_ASKPASS=/usr/bin/false SSH_ASKPASS=/usr/bin/false /usr/bin/git -c credential.helper= -c core.askPass= -c credential.interactive=never -c http.extraHeader= -c 'http.extraHeader=Authorization:' -c 'http.extraHeader=Cookie:' -c http.cookieFile=/dev/null -c http.saveCookies=false -c http.followRedirects=false -c protocol.allow=never -c protocol.https.allow=always clone --single-branch --branch main --no-tags https://github.com/Tpate33/ScholarChain-Submission.git "$ANON_DIR/main"

   GIT_OPTIONAL_LOCKS=0 git -C "$ANON_DIR/main" rev-parse HEAD
   GIT_OPTIONAL_LOCKS=0 git -C "$ANON_DIR/main" status --porcelain=v1 --untracked-files=all
   env -i PATH=/usr/bin:/bin:/usr/sbin:/sbin GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0 GIT_ASKPASS=/usr/bin/false SSH_ASKPASS=/usr/bin/false /usr/bin/git -c credential.helper= -c core.askPass= -c credential.interactive=never -c http.extraHeader= -c 'http.extraHeader=Authorization:' -c 'http.extraHeader=Cookie:' -c http.cookieFile=/dev/null -c http.saveCookies=false -c http.followRedirects=false -c protocol.allow=never -c protocol.https.allow=always ls-remote https://github.com/Tpate33/ScholarChain-Submission.git refs/heads/main refs/tags/submission-2026-11-25

   Require anonymous clone success, matching approved SHA and clean initial status;
   both advertised refs must resolve to the exact approved commit. Archive outputs
   and observed public URL. Do not claim anonymous access until these succeed.
   Candidate test success and anonymous access are separate verification gates.

5. Human submits only the verified NEW repository URL and any other required
   deliverables through the course's actual Teams assignment. Preserve the real
   receipt/timestamp; this preparation has not submitted anything.

These are future-only steps, not an executed shell script. Run one step at a time;
stop on every failed check. No command in this file has been run online. Original private repository and
/private/tmp/scholarchain-publication-01/private-source-copy are never push sources.
```
