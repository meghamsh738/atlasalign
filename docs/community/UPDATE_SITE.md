# Dedicated Fiji update-site preparation

Status: local updater input prepared; no site URL or account has been assigned,
and no upload or community request has been submitted.

The `*-updater-input` folder contains the plugin and Jackson JARs to install in
an isolated Fiji for publishing. It is **not** itself a functioning update site.
Use the [official Fiji/ImageJ Updater process](https://imagej.net/update-sites/setup)
to generate dependency metadata and upload it; do not manufacture `db.xml.gz` or
copy JARs into a web directory as a substitute.

After publication approval and account/site assignment:

1. Install the reviewed bundle in an isolated Fiji with Java 17+. Verify the
   command menus and a manual synthetic workflow before publishing.
2. Open **Help → Update → Manage Update Sites**. Add the assigned dedicated
   AtlasAlign Lite site using the exact URL and upload account from its host.
   The account owner handles credentials and required terms.
3. In advanced mode, select the AtlasAlign JARs, assign them to that site, and
   inspect the Updater's dependency analysis. Reuse dependencies already supplied
   by enabled Fiji sites; include required Jackson artifacts only where appropriate.
4. Review selected uploads and metadata. Upload using the Updater after approval.
5. In a second clean Fiji, enable the new site, install, restart, verify version
   and manual workflow, and record actual platform evidence.
6. Publish the documentation page and request listing only after the site works.

A dedicated community site is not inclusion in Fiji's default distribution.
Hosting credentials, site availability, dependency decisions and native GUI
validation remain release gates. The current personal Fiji installation is not
used as the publishing workspace.
