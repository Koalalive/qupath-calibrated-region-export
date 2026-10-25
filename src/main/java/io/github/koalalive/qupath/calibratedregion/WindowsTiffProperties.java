package io.github.koalalive.qupath.calibratedregion;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Keep OME-XML in ImageDescription while leaving Windows Explorer's Title and Subject empty. */
final class WindowsTiffProperties {
    private static final int IMAGE_DESCRIPTION = 270;
    private static final int XMP = 700;
    private static final int XP_SUBJECT = 40095;
    private static final byte[] EMPTY_TITLE_XMP = ("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
            + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
            + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
            + "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\"></rdf:li></rdf:Alt></dc:title>"
            + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>")
            .getBytes(StandardCharsets.UTF_8);

    private WindowsTiffProperties() {}

    static void clearTitleAndSubject(Path path) throws IOException {
        try (var file = new RandomAccessFile(path.toFile(), "rw")) {
            int first = file.readUnsignedByte();
            int second = file.readUnsignedByte();
            if (first != second || (first != 'I' && first != 'M'))
                throw new IOException("Exported file has no valid TIFF byte order.");
            boolean littleEndian = first == 'I';
            int version = (int) readUnsigned(file, 2, littleEndian);
            boolean bigTiff;
            if (version == 42) {
                bigTiff = false;
            } else if (version == 43) {
                bigTiff = true;
                if (readUnsigned(file, 2, littleEndian) != 8 || readUnsigned(file, 2, littleEndian) != 0)
                    throw new IOException("Unsupported BigTIFF header.");
            } else {
                throw new IOException("Exported file is not TIFF or BigTIFF.");
            }

            int offsetSize = bigTiff ? 8 : 4;
            int countSize = bigTiff ? 8 : 2;
            int entrySize = bigTiff ? 20 : 12;
            long headerOffsetPosition = bigTiff ? 8 : 4;
            long firstIfdOffset = readUnsigned(file, offsetSize, littleEndian);
            if (firstIfdOffset < 8 || firstIfdOffset >= file.length())
                throw new IOException("Invalid first TIFF directory offset.");
            file.seek(firstIfdOffset);
            long oldCount = readUnsigned(file, countSize, littleEndian);
            if (oldCount < 1 || oldCount > 100_000)
                throw new IOException("Unsupported TIFF directory entry count: " + oldCount);

            List<byte[]> entries = new ArrayList<>((int) oldCount + 2);
            boolean hasDescription = false;
            for (int i = 0; i < oldCount; i++) {
                byte[] entry = new byte[entrySize];
                file.readFully(entry);
                int tag = tagNumber(entry, littleEndian);
                if (tag == IMAGE_DESCRIPTION)
                    hasDescription = true;
                if (tag == XMP)
                    throw new IOException("The exported TIFF already has XMP metadata; it was not replaced.");
                if (tag != XP_SUBJECT)
                    entries.add(entry);
            }
            if (!hasDescription)
                throw new IOException("The exported TIFF has no OME ImageDescription metadata.");
            byte[] nextIfdOffset = new byte[offsetSize];
            file.readFully(nextIfdOffset);

            long xmpOffset = align(file.length(), 8);
            long newIfdOffset = align(Math.addExact(xmpOffset, EMPTY_TITLE_XMP.length), 8);
            long newCount = entries.size() + 2L;
            long end = Math.addExact(newIfdOffset,
                    Math.addExact(countSize + offsetSize, Math.multiplyExact(newCount, entrySize)));
            if (!bigTiff && end > 0xffff_ffffL)
                throw new IOException("Adding Windows property tags would exceed the classic TIFF size limit.");

            entries.add(newEntry(XMP, EMPTY_TITLE_XMP.length, xmpOffset, bigTiff, littleEndian));
            entries.add(newEntry(XP_SUBJECT, 2, 0, bigTiff, littleEndian));
            entries.sort(Comparator.comparingInt(entry -> tagNumber(entry, littleEndian)));

            file.seek(xmpOffset);
            file.write(EMPTY_TITLE_XMP);
            file.seek(newIfdOffset);
            writeUnsigned(file, newCount, countSize, littleEndian);
            for (byte[] entry : entries)
                file.write(entry);
            file.write(nextIfdOffset);
            file.getChannel().force(true);

            // Update the header only after the replacement directory is complete.
            file.seek(headerOffsetPosition);
            writeUnsigned(file, newIfdOffset, offsetSize, littleEndian);
            file.getChannel().force(true);
        } catch (ArithmeticException exception) {
            throw new IOException("TIFF directory size overflow.", exception);
        }
    }

    private static byte[] newEntry(int tag, long count, long value, boolean bigTiff, boolean littleEndian) {
        var buffer = ByteBuffer.allocate(bigTiff ? 20 : 12)
                .order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        buffer.putShort((short) tag);
        buffer.putShort((short) 1); // TIFF BYTE
        if (bigTiff) {
            buffer.putLong(count);
            buffer.putLong(value);
        } else {
            buffer.putInt((int) count);
            buffer.putInt((int) value);
        }
        return buffer.array();
    }

    private static int tagNumber(byte[] entry, boolean littleEndian) {
        return littleEndian
                ? (entry[0] & 0xff) | ((entry[1] & 0xff) << 8)
                : ((entry[0] & 0xff) << 8) | (entry[1] & 0xff);
    }

    private static long align(long value, int alignment) {
        return Math.addExact(value, alignment - 1) & -alignment;
    }

    private static long readUnsigned(RandomAccessFile file, int bytes, boolean littleEndian) throws IOException {
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            int next = file.readUnsignedByte();
            if (littleEndian)
                value |= (long) next << (i * 8);
            else
                value = (value << 8) | next;
        }
        return value;
    }

    private static void writeUnsigned(RandomAccessFile file, long value, int bytes, boolean littleEndian) throws IOException {
        for (int i = 0; i < bytes; i++) {
            int shift = littleEndian ? i * 8 : (bytes - 1 - i) * 8;
            file.write((int) (value >>> shift) & 0xff);
        }
    }
}
