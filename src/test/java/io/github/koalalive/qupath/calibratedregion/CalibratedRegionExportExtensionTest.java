package io.github.koalalive.qupath.calibratedregion;

import loci.formats.MetadataTools;
import loci.formats.in.OMETiffReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CalibratedRegionExportExtensionTest {
    @TempDir
    Path tempDir;

    @Test
    void exportedOmeTiffRetainsNativePixelsAndPhysicalCalibration() throws Exception {
        var pixels = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        try (var server = new WrappedBufferedImageServer("synthetic-slide", pixels)) {
            server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata())
                    .pixelSizeMicrons(0.5, 0.75)
                    .build());
            var roi = ROIs.createRectangleROI(10, 8, 20, 12, ImagePlane.getDefaultPlane());
            var output = tempDir.resolve("selected.ome.tif").toFile();

            CalibratedRegionExportExtension.writeCalibratedRegion(server, roi, output);

            try (var reader = new OMETiffReader()) {
                var metadata = MetadataTools.createOMEXMLMetadata();
                reader.setMetadataStore(metadata);
                reader.setId(output.getAbsolutePath());
                assertEquals(20, reader.getSizeX());
                assertEquals(12, reader.getSizeY());
                assertEquals(0.5, metadata.getPixelsPhysicalSizeX(0).value().doubleValue(), 1e-9);
                assertEquals(0.75, metadata.getPixelsPhysicalSizeY(0).value().doubleValue(), 1e-9);
            }
        }
    }
}
