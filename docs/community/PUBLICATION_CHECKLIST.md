# Publication checklist

## Prepared locally

- [x] Full tests, overview/source integrity checks, setup failure cases and final review passed.
- [x] Reviewed screenshots, immutable source snapshot, binary bundle and checksums retained.
- [x] Clean snapshot contains no private history, paths, laboratory data or runtime/model caches.
- [x] Fresh package smoke passed; native GUI evidence and unverified platforms stated accurately.
- [x] Approved native macOS installation/restart and batch smoke completed with rollback backups.

## Publication approvals and remaining distribution work

- [x] Approved public `meghamsh738/atlasalign` created from the clean snapshot.
- [x] Approved source and `0.1.0-beta.1` release artifacts published.
- [x] Public Windows/macOS/Linux tests, packaging and Java 17 loading checks
  passed in [run 34169378054](https://github.com/meghamsh738/atlasalign/actions/runs/34169378054).
  Native GUI evidence remains limited to the documented macOS checks.
- [ ] Approve the hosting/account request; account owner completes credentials/terms.
- [ ] Approve Updater upload after site assignment and review its dependency metadata.
- [ ] Verify clean Fiji installation from the update site.
- [ ] Approve ImageJ documentation and listing request submission.

Keep the existing private development repository private. Do not publish its
history or simply change its visibility. No merge into its default branch is
part of publication approval.
