# QuPath Calibrated Region Export

A small QuPath 0.7.x extension for life science researchers who need to locate a region of interest in a whole-slide image, crop it at native resolution, and retain its physical pixel size for downstream semi-quantitative analysis.

## What it does

- **Create rectangle by physical size**: Enter width and height in micrometers. The first-use defaults are 1000 × 600 µm. The rectangle is centered on the current view and selected automatically. The last entered size is remembered until QuPath closes.
- **Export selected region as OME-TIFF**: Choose an output filename in a save dialog. An axis-aligned rectangle is cropped directly from the source at its highest resolution (`downsample = 1.0`). A rotated rectangular annotation is straightened and cropped along its own edges, so its exported width and height follow the annotation rather than its larger axis-aligned bounding box. The `.ome.tif` stores calibrated pixel sizes in micrometers. LZW lossless compression is used when supported; otherwise the export is uncompressed.
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

The extension copies QuPath's calibration from the opened image. It cannot establish whether the scanner's calibration is correct; verify that against the slide/scanner metadata. Version 1.3.1 rounds each crop dimension to the nearest whole source pixel, avoiding the extra 1–2 border pixels that could be included when a rectangle has fractional pixel coordinates. The export notification reports the written pixel dimensions and their physical width and height. A requested micrometer size cannot always be represented exactly at the source pixel spacing: `exported µm = round(requested µm / µm-per-pixel) × µm-per-pixel`, so the remaining difference can be up to half a source pixel. An axis-aligned crop keeps the original pixels. Straightening a rotated crop resamples the image onto a grid with the same pixel spacing, which can change individual pixel values slightly; account for this in quantitative intensity analysis.

QuPath's rectangular ROI does not retain orientation when a rotation makes it axis aligned again, such as 90° or 180°. In that case the extension exports the rectangle as it appears in image coordinates; it cannot reconstruct its earlier width/height order or a 180° flip. Rotated export also requires equal X and Y source pixel sizes; the extension stops with an error when they differ because QuPath's transformed server cannot preserve a faithful two-axis physical calibration in that case.

For non-rectangular annotations, the export contains the **bounding rectangle**, not a masked polygon. It contains pixel data from the image server, without QuPath's display overlays, scale bar, or brightness/contrast rendering. Existing compression or processing in the scanned source cannot be reversed. This extension prepares calibrated crops; it does not perform semi-quantitative analysis or normalize staining between slides.

## Build from source

Use a Java 25 JDK and run `gradlew build` (Windows: `gradlew.bat build`). The installable JAR is created in `build/libs/`. The Gradle tests create synthetic images and read the exported OME-TIFF files back to check dimensions, image pixels, and physical pixel sizes.

The extension entry point is [`src/main/java/io/github/koalalive/qupath/calibratedregion/CalibratedRegionExportExtension.java`](src/main/java/io/github/koalalive/qupath/calibratedregion/CalibratedRegionExportExtension.java). The service registration file is in `src/main/resources/META-INF/services/`.

This project uses QuPath's [extension template](https://github.com/qupath/qupath-extension-template). The [QuPath export documentation](https://qupath.readthedocs.io/en/stable/docs/advanced/exporting_images.html) explains the difference between original pixels and rendered snapshots; the [OME-TIFF writer API](https://qupath.github.io/javadoc/docs/qupath/lib/images/writers/ome/OMEPyramidWriter.html) describes the exported metadata.
