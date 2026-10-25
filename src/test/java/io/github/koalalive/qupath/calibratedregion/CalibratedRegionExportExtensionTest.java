package io.github.koalalive.qupath.calibratedregion;

import loci.formats.MetadataTools;
import loci.formats.in.OMETiffReader;
import loci.formats.tiff.TiffParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

            var request = CalibratedRegionExportExtension.writeCalibratedRegion(server, roi, output);
            assertEquals(20, request.getWidth());
            assertEquals(12, request.getHeight());

            try (var parser = new TiffParser(output.getAbsolutePath())) {
                var firstIfd = parser.getFirstIFD();
                assertTrue(firstIfd.containsKey(700));
                assertTrue(firstIfd.containsKey(40095));
                assertTrue(firstIfd.getComment().contains("PhysicalSizeX"));
            }

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

    @Test
    void fractionalRectangleBoundsDoNotAddBorderPixels() throws Exception {
        var pixels = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        try (var server = new WrappedBufferedImageServer("fractional-slide", pixels)) {
            server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata())
                    .pixelSizeMicrons(0.5, 0.75)
                    .build());
            var roi = ROIs.createRectangleROI(10.4, 8.4, 20.2, 12.2, ImagePlane.getDefaultPlane());
            var output = tempDir.resolve("fractional.ome.tif").toFile();

            CalibratedRegionExportExtension.writeCalibratedRegion(server, roi, output);

            try (var reader = new OMETiffReader()) {
                reader.setId(output.getAbsolutePath());
                assertEquals(20, reader.getSizeX());
                assertEquals(12, reader.getSizeY());
            }
        }
    }

    @Test
    void rotatedRectangleExportsItsOwnDimensions() throws Exception {
        var pixels = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < pixels.getHeight(); y++)
            for (int x = 0; x < pixels.getWidth(); x++)
                pixels.setRGB(x, y, (x * 3 << 16) | (y * 5 << 8));
        try (var server = new WrappedBufferedImageServer("synthetic-rotated-slide", pixels)) {
            server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata())
                    .pixelSizeMicrons(0.5, 0.5)
                    .build());
            var rectangle = ROIs.createRectangleROI(22, 18, 20, 12, ImagePlane.getDefaultPlane());
            var rotation = AffineTransform.getRotateInstance(Math.PI / 4,
                    rectangle.getCentroidX(), rectangle.getCentroidY());
            var rotated = RoiTools.transformROI(rectangle, rotation);
            var output = tempDir.resolve("rotated.ome.tif").toFile();

            CalibratedRegionExportExtension.writeCalibratedRegion(server, rotated, output);

            try (var reader = new OMETiffReader()) {
                var metadata = MetadataTools.createOMEXMLMetadata();
                reader.setMetadataStore(metadata);
                reader.setId(output.getAbsolutePath());
                assertEquals(20, reader.getSizeX());
                assertEquals(12, reader.getSizeY());
                assertEquals(0.5, metadata.getPixelsPhysicalSizeX(0).value().doubleValue(), 1e-9);
                assertEquals(0.5, metadata.getPixelsPhysicalSizeY(0).value().doubleValue(), 1e-9);
            }
            var exported = ImageIO.read(output);
            assertEquals(20, exported.getWidth());
            assertEquals(12, exported.getHeight());
            int center = exported.getRGB(10, 6);
            assertEquals(32 * 3, (center >> 16) & 0xff, 6);
            assertEquals(24 * 5, (center >> 8) & 0xff, 10);
        }
    }

    @Test
    void fractionalRotatedRectangleUsesRoundedSideLengths() throws Exception {
        var pixels = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        try (var server = new WrappedBufferedImageServer("fractional-rotated-slide", pixels)) {
            server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata())
                    .pixelSizeMicrons(0.5, 0.5)
                    .build());
            var rectangle = ROIs.createRectangleROI(22.4, 18.4, 20.2, 12.2, ImagePlane.getDefaultPlane());
            var rotated = RoiTools.transformROI(rectangle, AffineTransform.getRotateInstance(
                    Math.PI / 4, rectangle.getCentroidX(), rectangle.getCentroidY()));
            var output = tempDir.resolve("fractional-rotated.ome.tif").toFile();

            CalibratedRegionExportExtension.writeCalibratedRegion(server, rotated, output);

            try (var reader = new OMETiffReader()) {
                reader.setId(output.getAbsolutePath());
                assertEquals(20, reader.getSizeX());
                assertEquals(12, reader.getSizeY());
            }
        }
    }

    @Test
    void rotatedAnisotropicPixelsCannotBeExportedWithFalseCalibration() throws Exception {
        var pixels = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        try (var server = new WrappedBufferedImageServer("anisotropic-slide", pixels)) {
            server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata())
                    .pixelSizeMicrons(0.5, 0.75)
                    .build());
            var rectangle = ROIs.createRectangleROI(22, 18, 20, 12, ImagePlane.getDefaultPlane());
            var rotated = RoiTools.transformROI(rectangle, AffineTransform.getRotateInstance(
                    Math.PI / 6, rectangle.getCentroidX(), rectangle.getCentroidY()));
            var output = tempDir.resolve("anisotropic.ome.tif").toFile();

            var error = assertThrows(IOException.class,
                    () -> CalibratedRegionExportExtension.writeCalibratedRegion(server, rotated, output));
            assertTrue(error.getMessage().contains("equal X and Y pixel sizes"));
            assertFalse(output.exists());
        }
    }

    @Test
    void windowsPropertyTagsCanBeAddedToBigTiff() throws Exception {
        var output = tempDir.resolve("synthetic-big.tif");
        var tiff = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 43);
        tiff.putShort((short) 8).putShort((short) 0).putLong(16);
        tiff.putLong(1);
        tiff.putShort((short) 270).putShort((short) 2).putLong(6);
        tiff.put(new byte[] {'<', '?', 'x', 'm', 'l', 0, 0, 0});
        tiff.putLong(0);
        Files.write(output, tiff.array());

        WindowsTiffProperties.clearTitleAndSubject(output);

        try (var parser = new TiffParser(output.toString())) {
            assertTrue(parser.isBigTiff());
            var firstIfd = parser.getFirstIFD();
            assertEquals("<?xml", firstIfd.getComment());
            assertTrue(firstIfd.containsKey(700));
            assertTrue(firstIfd.containsKey(40095));
        }
    }

}
