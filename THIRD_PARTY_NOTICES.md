# Third-party notices and scientific acknowledgements

Made by Meghamsh Teja Konda. AtlasAlign Lite code retains the existing MIT
license and AtlasAlign Lite contributor copyright notice in LICENSE.
This license does not relicense external tools, model weights or atlas data.

- **Fiji, ImageJ/ImageJ2 and SciJava:** supplied by the user's Fiji installation.
  Consult [ImageJ licensing](https://imagej.net/licensing/) and the notices in
  that distribution. Cite the tools used in the scientific workflow.
- **Bio-Formats:** supplied by Fiji, rather than redistributed in the plugin
  bundle. See [Bio-Formats](https://www.openmicroscopy.org/bio-formats/).
- **Jackson 2.18.0:** the binary bundle includes core, annotations and databind.
  These upstream JARs retain their embedded license/notice files. Jackson uses
  the Apache License 2.0; see [Jackson](https://github.com/FasterXML/jackson).
- **Allen Mouse Brain CCFv3 2017, 25 µm:** downloaded separately after terms
  acknowledgement. Source URLs, pinned lengths and SHA-256 values are in
  `atlasalign-atlas/src/main/resources/org/atlasalign/atlas/allen_mouse_25um.json`.
  Review [Allen terms](https://alleninstitute.org/legal/terms-of-use).
  Cite Wang Q et al. *The Allen Mouse Brain Common Coordinate Framework: A 3D
  Reference Atlas*. Cell. 2020;181(4):936–953.e20.
- **DeepSlice:** optional; neither runtime nor weights are redistributed.
  Consult [upstream source and terms](https://github.com/PolarBean/DeepSlice)
  for software, models and citation requirements.
- **ABBA adapter:** source remains isolated in `atlasalign-abba-adapter`; it is
  not included in the user plugin binary bundle. ABBA dependencies and notices
  remain governed by their upstream projects.

The synthetic demo macro is original project code under MIT. It contains no
laboratory data. Preserve upstream notices when redistributing binary bundles.
