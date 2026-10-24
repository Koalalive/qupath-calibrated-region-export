# QuPath Calibrated Region Export

A small QuPath 0.7.x extension for life science researchers who need to locate a region of interest in a whole-slide image, crop it at native resolution, and retain its physical pixel size for downstream semi-quantitative analysis.

## What it does

- **Create rectangle by physical size**: Enter width and height in micrometers. The first-use defaults are 1000 × 600 µm. The rectangle is centered on the current view and selected automatically. The last entered size is remembered until QuPath closes.
- **Export selected region as OME-TIFF**: Choose an output filename in a save dialog. The selected object's bounding rectangle is exported at the source image's highest resolution (`downsample = 1.0`) as `.ome.tif`. The OME-TIFF stores the source X and Y pixel sizes in micrometers. LZW lossless compression is used when supported; otherwise the export is uncompressed.
- The extension refuses physical-size creation or export when the source image has no valid micrometers-per-pixel calibration.

The commands are available under **Extensions → Calibrated Region Export**. The default shortcuts are `Ctrl+Alt+1` (create) and `Ctrl+Alt+2` (export), provided QuPath has not already assigned them.

## Install

1. Download the release JAR for QuPath 0.7.x.
2. Remove older versions of this extension from the QuPath extensions directory.
3. Drag the JAR onto the QuPath window and restart QuPath.

## Typical workflow

1. Open a scanned slide in QuPath and verify its pixel calibration.
2. Navigate to the area of interest.
3. Choose **Create rectangle by physical size...**, enter the desired width and height, and adjust the resulting annotation if needed.
4. Choose **Export selected region as OME-TIFF...** and enter a filename.
5. Open the OME-TIFF in an OME-compatible image analysis tool and confirm that it reports the expected pixel size before making measurements.

## Physical-size and analysis notes

The extension copies QuPath's calibration from the opened image. It cannot establish whether the scanner's calibration is correct; verify that against the slide/scanner metadata. Because image boundaries fall on whole pixels, the exported physical width and height can differ from the entered rectangle size by approximately one source pixel. The output keeps native pixel spacing instead of resampling to force an exact micrometer width.

For non-rectangular annotations, the export contains the **bounding rectangle**, not a masked polygon. It contains pixel data from the image server, without QuPath's display overlays, scale bar, or brightness/contrast rendering. Existing compression or processing in the scanned source cannot be reversed. This extension prepares calibrated crops; it does not perform semi-quantitative analysis or normalize staining between slides.

## Build from source

Use a Java 25 JDK and run `gradlew build` (Windows: `gradlew.bat build`). The installable JAR is created in `build/libs/`. The Gradle test creates a synthetic image, exports a cropped OME-TIFF, and reads it back to check pixel dimensions and physical pixel sizes.

The extension entry point is [`src/main/java/io/github/koalalive/qupath/calibratedregion/CalibratedRegionExportExtension.java`](src/main/java/io/github/koalalive/qupath/calibratedregion/CalibratedRegionExportExtension.java). The service registration file is in `src/main/resources/META-INF/services/`.

This project uses QuPath's [extension template](https://github.com/qupath/qupath-extension-template). The [QuPath export documentation](https://qupath.readthedocs.io/en/stable/docs/advanced/exporting_images.html) explains the difference between original pixels and rendered snapshots; the [OME-TIFF writer API](https://qupath.github.io/javadoc/docs/qupath/lib/images/writers/ome/OMEPyramidWriter.html) describes the exported metadata.
