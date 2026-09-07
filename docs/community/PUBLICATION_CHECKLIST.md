# Publication checklist

## Prepared locally

- [x] Full tests, overview/source integrity checks, setup failure cases and final review passed.
- [x] Reviewed screenshots, immutable source snapshot, binary bundle and checksums retained.
- [x] Clean snapshot contains no private history, paths, laboratory data or runtime/model caches.
- [x] Fresh package smoke passed; native GUI evidence and unverified platforms stated accurately.
- [x] Approved native macOS installation/restart and batch smoke completed with rollback backups.

## Publication approvals and remaining distribution work

- [x] Approve creation of public `meghamsh738/atlasalign` from the clean snapshot.
- [x] Approve uploading source and `0.1.0-beta.1` release artifacts.
- [ ] Repeat the prepared CI workflow in the new public repository after creation;
  report automated build/smoke evidence separately from native GUI validation.
- [ ] Approve the hosting/account request; account owner completes credentials/terms.
- [ ] Approve Updater upload after site assignment and review its dependency metadata.
- [ ] Verify clean Fiji installation from the update site.
- [ ] Approve ImageJ documentation and listing request submission.

Keep the existing private development repository private. Do not publish its
history or simply change its visibility. No merge into its default branch is
part of publication approval.
