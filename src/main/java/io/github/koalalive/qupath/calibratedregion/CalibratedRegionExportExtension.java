package io.github.koalalive.qupath.calibratedregion;

import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.TransformedServerBuilder;
import qupath.lib.images.writers.ome.OMEPyramidWriter;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;

import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Two small commands for working with calibrated physical regions in QuPath. */
public final class CalibratedRegionExportExtension implements QuPathExtension {
    private static final Logger logger = LoggerFactory.getLogger(CalibratedRegionExportExtension.class);
    private static final String TITLE = "Calibrated Region Export";
    private static final double DEFAULT_WIDTH_UM = 1000.0;
    private static final double DEFAULT_HEIGHT_UM = 600.0;
    private double lastWidthUm = DEFAULT_WIDTH_UM;
    private double lastHeightUm = DEFAULT_HEIGHT_UM;
    private boolean installed;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed)
            return;
        installed = true;

        var menu = qupath.getMenu("Extensions>" + TITLE, true);
        var create = new MenuItem("Create rectangle by physical size...");
        create.setOnAction(event -> createRectangle(qupath));
        menu.getItems().add(create);

        var export = new MenuItem("Export selected region as OME-TIFF...");
        export.setOnAction(event -> exportSelected(qupath));
        menu.getItems().add(export);

        // Leave an existing user/application shortcut untouched if there is a conflict.
        addShortcutIfFree(qupath, create, "Ctrl+Alt+1");
        addShortcutIfFree(qupath, export, "Ctrl+Alt+2");
    }

    private static void addShortcutIfFree(QuPathGUI qupath, MenuItem item, String combination) {
        if (qupath.lookupAccelerator(combination) == null)
            qupath.setAccelerator(item, KeyCombination.valueOf(combination));
        else
            logger.warn("Shortcut {} is already in use; {} remains available in the menu", combination, item.getText());
    }

    private void createRectangle(QuPathGUI qupath) {
        var viewer = qupath.getViewer();
        if (viewer == null || viewer.getImageData() == null) {
            Dialogs.showErrorMessage(TITLE, "Open an image first.");
            return;
        }

        var imageData = viewer.getImageData();
        var server = imageData.getServer();
        var calibration = server.getPixelCalibration();
        double pixelWidthUm = calibration.getPixelWidthMicrons();
        double pixelHeightUm = calibration.getPixelHeightMicrons();
        if (!Double.isFinite(pixelWidthUm) || !Double.isFinite(pixelHeightUm)
                || pixelWidthUm <= 0 || pixelHeightUm <= 0) {
            Dialogs.showErrorMessage(TITLE, "This image has no valid pixel size in µm. Check its calibration first.");
            return;
        }

        var size = promptForSize(qupath, server.getWidth(), server.getHeight(), pixelWidthUm, pixelHeightUm);
        if (size == null)
            return;

        double widthPx = size.widthUm() / pixelWidthUm;
        double heightPx = size.heightUm() / pixelHeightUm;
        lastWidthUm = size.widthUm();
        lastHeightUm = size.heightUm();

        double x = Math.max(0, Math.min(viewer.getCenterPixelX() - widthPx / 2, server.getWidth() - widthPx));
        double y = Math.max(0, Math.min(viewer.getCenterPixelY() - heightPx / 2, server.getHeight() - heightPx));
        var roi = ROIs.createRectangleROI(x, y, widthPx, heightPx, viewer.getImagePlane());
        var annotation = PathObjects.createAnnotationObject(roi);
        imageData.getHierarchy().addObject(annotation);
        viewer.setSelectedObject(annotation);
        Dialogs.showInfoNotification(TITLE, "Created and selected a " + formatDimension(size.widthUm())
                + " × " + formatDimension(size.heightUm()) + " µm rectangle.");
    }

    private PhysicalSize promptForSize(QuPathGUI qupath, int imageWidth, int imageHeight,
                                       double pixelWidthUm, double pixelHeightUm) {
        var dialog = new Dialog<ButtonType>();
        dialog.setTitle("Create rectangle by physical size");
        dialog.setHeaderText("Enter the rectangle size in micrometers (µm)");
        dialog.initOwner(qupath.getStage());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var widthField = new TextField(formatDimension(lastWidthUm));
        var heightField = new TextField(formatDimension(lastHeightUm));
        var feedback = new Label();
        feedback.setStyle("-fx-text-fill: #b00020;");
        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("Width"), widthField, new Label("µm"));
        grid.addRow(1, new Label("Height"), heightField, new Label("µm"));
        grid.add(feedback, 0, 2, 3, 1);
        dialog.getDialogPane().setContent(grid);

        var okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);
        Runnable validate = () -> {
            double widthUm = parseDimension(widthField.getText());
            double heightUm = parseDimension(heightField.getText());
            String problem = "";
            if (!Double.isFinite(widthUm) || !Double.isFinite(heightUm)
                    || widthUm <= 0 || heightUm <= 0)
                problem = "Width and height must be positive numbers.";
            else if (widthUm / pixelWidthUm < 1 || heightUm / pixelHeightUm < 1)
                problem = "Each dimension must be at least one pixel.";
            else if (widthUm / pixelWidthUm > imageWidth || heightUm / pixelHeightUm > imageHeight)
                problem = "The requested size exceeds the image bounds.";
            feedback.setText(problem);
            okButton.setDisable(!problem.isEmpty());
        };
        widthField.textProperty().addListener((observable, oldValue, newValue) -> validate.run());
        heightField.textProperty().addListener((observable, oldValue, newValue) -> validate.run());
        validate.run();

        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK)
            return null;
        return new PhysicalSize(parseDimension(widthField.getText()), parseDimension(heightField.getText()));
    }

    private static double parseDimension(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }

    private static String formatDimension(double dimension) {
        return BigDecimal.valueOf(dimension).stripTrailingZeros().toPlainString();
    }

    private record PhysicalSize(double widthUm, double heightUm) {}

    private static void exportSelected(QuPathGUI qupath) {
        var viewer = qupath.getViewer();
        if (viewer == null || viewer.getImageData() == null || viewer.getSelectedObject() == null) {
            Dialogs.showErrorMessage(TITLE, "Open an image and select a region object first.");
            return;
        }

        var selected = viewer.getSelectedObject();
        var roi = selected.getROI();
        if (roi == null || !roi.isArea()) {
            Dialogs.showErrorMessage(TITLE, "Select an area object, such as a rectangle or polygon.");
            return;
        }

        var server = viewer.getImageData().getServer();
        var calibration = server.getPixelCalibration();
        if (!Double.isFinite(calibration.getPixelWidthMicrons())
                || !Double.isFinite(calibration.getPixelHeightMicrons())
                || calibration.getPixelWidthMicrons() <= 0
                || calibration.getPixelHeightMicrons() <= 0) {
            Dialogs.showErrorMessage(TITLE, "This image has no valid pixel size in µm. Check its calibration before exporting.");
            return;
        }
        var chooser = new FileChooser();
        chooser.setTitle("Export selected region as calibrated OME-TIFF");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("OME-TIFF image (*.ome.tif, *.ome.tiff)", "*.ome.tif", "*.ome.tiff"));
        chooser.setInitialFileName(defaultFileName(server.getMetadata().getName()));
        var chosen = chooser.showSaveDialog(qupath.getStage());
        if (chosen == null)
            return;

        File destination = ensureOmeTiffExtension(chosen);
        if (destination.exists() && !Dialogs.showConfirmDialog(TITLE, "This file already exists. Overwrite it?\n" + destination))
            return;

        try {
            var request = writeCalibratedRegion(server, roi, destination);
            String physicalWidth = formatExportDimension(request.getWidth() * calibration.getPixelWidthMicrons());
            String physicalHeight = formatExportDimension(request.getHeight() * calibration.getPixelHeightMicrons());
            Dialogs.showInfoNotification(TITLE, "Exported " + request.getWidth() + " × " + request.getHeight()
                    + " pixels (" + physicalWidth + " × " + physicalHeight + " µm): "
                    + destination.getAbsolutePath());
        } catch (IOException | RuntimeException exception) {
            logger.error("Failed to export selected region to {}", destination, exception);
            Dialogs.showErrorMessage(TITLE, exception);
        }
    }

    static RegionRequest writeCalibratedRegion(ImageServer<BufferedImage> server, ROI roi, File destination) throws IOException {
        var rotated = RotatedRectangle.from(roi);
        ImageServer<BufferedImage> exportServer = server;
        RegionRequest request;
        if (rotated == null) {
            // Bounding-box requests round the left/top down and right/bottom up,
            // which can add one or two unwanted border pixels for fractional ROIs.
            int width = Math.toIntExact(Math.round(roi.getBoundsWidth()));
            int height = Math.toIntExact(Math.round(roi.getBoundsHeight()));
            if (width < 1 || height < 1 || width > server.getWidth() || height > server.getHeight())
                throw new IOException("The selected region has no valid native-resolution pixel dimensions.");
            int x = (int) Math.max(0, Math.min(Math.round(roi.getBoundsX()), server.getWidth() - width));
            int y = (int) Math.max(0, Math.min(Math.round(roi.getBoundsY()), server.getHeight() - height));
            request = RegionRequest.createInstance(server.getPath(), 1.0,
                    x, y, width, height, roi.getZ(), roi.getT());
        } else {
            var calibration = server.getPixelCalibration();
            double pixelWidth = calibration.getPixelWidthMicrons();
            double pixelHeight = calibration.getPixelHeightMicrons();
            if (Math.abs(pixelWidth - pixelHeight) > Math.max(pixelWidth, pixelHeight) * 1e-6)
                throw new IOException("Rotated export requires equal X and Y pixel sizes. This image has "
                        + pixelWidth + " × " + pixelHeight + " µm/px; QuPath cannot preserve its physical "
                        + "calibration after rotating the image.");
            exportServer = new TransformedServerBuilder(server).transform(rotated.transform()).build();
            var outputCalibration = exportServer.getPixelCalibration();
            if (!Double.isFinite(outputCalibration.getPixelWidthMicrons())
                    || !Double.isFinite(outputCalibration.getPixelHeightMicrons())
                    || Math.abs(outputCalibration.getPixelWidthMicrons() - pixelWidth) > pixelWidth * 1e-6
                    || Math.abs(outputCalibration.getPixelHeightMicrons() - pixelHeight) > pixelHeight * 1e-6)
                throw new IOException("QuPath did not preserve the source pixel calibration after rotation. "
                        + "The region was not exported.");
            request = RegionRequest.createInstance(exportServer.getPath(), 1.0, 0, 0,
                    rotated.width(), rotated.height(), roi.getZ(), roi.getT());
        }
        var compression = OMEPyramidWriter.CompressionType.LZW.supportsImage(exportServer)
                ? OMEPyramidWriter.CompressionType.LZW
                : OMEPyramidWriter.CompressionType.UNCOMPRESSED;
        OMEPyramidWriter.writeImage(exportServer, destination.getAbsolutePath(), compression, request);
        WindowsTiffProperties.clearTitleAndSubject(destination.toPath());
        return request;
    }

    private static String formatExportDimension(double micrometers) {
        return BigDecimal.valueOf(micrometers).setScale(3, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private record RotatedRectangle(int width, int height, AffineTransform transform) {
        static RotatedRectangle from(ROI roi) {
            if (roi instanceof RectangleROI)
                return null;
            var points = roi.getAllPoints();
            if (points.size() == 5 && points.getFirst().distance(points.getLast()) < 1e-3)
                points = points.subList(0, 4);
            if (points.size() != 4)
                return null;
            var topLeft = points.get(0);
            var bottomLeft = points.get(1);
            var bottomRight = points.get(2);
            var topRight = points.get(3);
            double hx = bottomLeft.getX() - topLeft.getX();
            double hy = bottomLeft.getY() - topLeft.getY();
            double wx = bottomRight.getX() - bottomLeft.getX();
            double wy = bottomRight.getY() - bottomLeft.getY();
            double width = Math.hypot(wx, wy);
            double height = Math.hypot(hx, hy);
            if (width < 1 || height < 1 || Math.abs(wx * hx + wy * hy) > width * height * 1e-3
                    || Math.hypot(topRight.getX() - topLeft.getX() - wx,
                            topRight.getY() - topLeft.getY() - wy) > 1e-3 * width)
                return null;
            double ux = wx / width;
            double uy = wy / width;
            double handedness = wx * hy - wy * hx >= 0 ? 1 : -1;
            double vx = -handedness * uy;
            double vy = handedness * ux;
            if (ux > 0.99999999995 && Math.abs(uy) < 1e-5
                    && vy > 0.99999999995 && Math.abs(vx) < 1e-5)
                return null;
            var transform = new AffineTransform(ux, vx, uy, vy,
                    -ux * topLeft.getX() - uy * topLeft.getY(),
                    -vx * topLeft.getX() - vy * topLeft.getY());
            return new RotatedRectangle((int) Math.round(width), (int) Math.round(height), transform);
        }
    }

    private static File ensureOmeTiffExtension(File file) {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ome.tif") || lower.endsWith(".ome.tiff"))
            return file;
        String name = file.getName();
        if (lower.endsWith(".tiff"))
            name = name.substring(0, name.length() - 5);
        else if (lower.endsWith(".tif"))
            name = name.substring(0, name.length() - 4);
        return new File(file.getParentFile(), name + ".ome.tif");
    }

    private static String defaultFileName(String imageName) {
        String name = imageName == null || imageName.isBlank() ? "region" : imageName;
        name = name.replaceFirst("(?i)\\.(ome\\.tiff|ome\\.tif|svs|ndpi|tiff|tif)$", "");
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return name + "_selected.ome.tif";
    }

    @Override
    public String getName() {
        return TITLE;
    }

    @Override
    public String getDescription() {
        return "Create a rectangle in micrometers (default 1000 × 600 µm) and export aligned or "
                + "rotated rectangular regions as calibrated OME-TIFF for downstream analysis.";
    }

    @Override
    public Version getQuPathVersion() {
        return Version.parse("v0.7.0");
    }
}
