package cn.hip.platform.integration.ofd;

import org.ofdrw.core.attachment.Attachments;
import org.ofdrw.core.basicStructure.ofd.docInfo.CustomDatas;
import org.ofdrw.core.basicType.ST_Loc;
import org.ofdrw.font.Font;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.PageLayout;
import org.ofdrw.layout.element.Paragraph;
import org.ofdrw.layout.element.TextAlign;
import org.ofdrw.layout.edit.Attachment;
import org.ofdrw.pkg.container.VirtualContainer;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** Creates a single unsigned OFD-H preview from an already authorized source snapshot. */
@Component
public class OfdPreviewWriter {
    private static final String FONT_FILE = "NotoSansCJKsc-Regular.otf";
    private static final String FONT_RESOURCE = "/fonts/ofd/" + FONT_FILE;
    private static final String METADATA_FILE = "Attach_0.json";
    private static final String METADATA_NAME = "source-metadata.json";

    public byte[] write(String docId, String title, String text, byte[] metadataJson) throws IOException {
        if (docId == null || !docId.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException("OFD 文档标识须为 32 位十六进制 UUID");
        }
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(text, "text");
        byte[] metadata = Objects.requireNonNull(metadataJson, "metadataJson").clone();
        String metadataText = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(metadata)).toString();
        UUID documentId = new UUID(Long.parseUnsignedLong(docId.substring(0, 16), 16),
                Long.parseUnsignedLong(docId.substring(16), 16));
        String layoutText = text.replace("\r\n", "\n").replace('\r', '\n');

        Path directory = Files.createTempDirectory("hip-ofd-preview-");
        Path fontFile = directory.resolve(FONT_FILE);
        Path metadataFile = directory.resolve(METADATA_FILE);
        try {
            try (InputStream resource = OfdPreviewWriter.class.getResourceAsStream(FONT_RESOURCE)) {
                if (resource == null) throw new IOException("OFD 预览字型资源缺失");
                Files.copy(resource, fontFile);
            }
            Font font = new Font("Noto Sans CJK SC", "Noto Sans CJK SC", fontFile);
            requireGlyphs(font, title);
            requireGlyphs(font, text);
            Files.write(metadataFile, metadata);

            var output = new ByteArrayOutputStream();
            try (var document = new OFDDoc(output)) {
                var entry = document.getOfdDir().getOfd();
                // OFDRW 2.3.9's getter returns its default constant. Set the serialized attribute.
                entry.addAttribute("DocType", "OFD-H");
                entry.getDocBody().getDocInfo()
                        .setDocID(documentId)
                        .setTile(title)
                        .setCreator("HIP / OFDRW")
                        .setCreatorVersion("2.3.9")
                        .setCustomDatas(new CustomDatas().addCustomData("SourceMetadata", metadataText));
                document.setDefaultPageLayout(PageLayout.A4().setMargin(18d));
                document.add(new Paragraph(title, 5.5d, font).setTextAlign(TextAlign.center));
                document.add(new Paragraph(layoutText, 4d, font).setLineSpace(2d));

                // Keep both the attachment list and its original bytes in the standard Attachs directory.
                var attachments = document.getOfdDir().obtainDocDefault()
                        .obtainContainer("Attachs", VirtualContainer::new);
                attachments.putObj("Attachments.xml", new Attachments());
                document.getOfdDocument().setAttachments(new ST_Loc("/Doc_0/Attachs/Attachments.xml"));
                // The internal basename follows GB/T 48666 Table 1; Name remains descriptive for readers.
                document.addAttachment("/Doc_0/Attachs",
                        new Attachment(METADATA_NAME, metadataFile).setFormat("JSON"));
            }
            return output.toByteArray();
        } catch (org.dom4j.DocumentException | RuntimeException error) {
            throw new IOException("OFD 预览生成失败", error);
        } finally {
            Files.deleteIfExists(metadataFile);
            Files.deleteIfExists(fontFile);
            Files.deleteIfExists(directory);
        }
    }

    private void requireGlyphs(Font font, String text) throws IOException {
        // The pinned layout engine handles UTF-16 code units, so a supported font alone
        // cannot make supplementary characters safe to export. Reject before layout.
        for (int offset = 0; offset < text.length(); offset++) {
            int codePoint = text.codePointAt(offset);
            if (Character.isSupplementaryCodePoint(codePoint)) {
                throw new IOException("OFD 预览暂不支持补充平面字符 U+"
                        + Integer.toHexString(codePoint).toUpperCase(java.util.Locale.ROOT));
            }
        }
        int missing = font.getFontObj().canDisplayUpTo(text);
        if (missing >= 0) {
            throw new IOException("OFD 预览字型无法显示字符 U+"
                    + Integer.toHexString(text.codePointAt(missing)).toUpperCase(java.util.Locale.ROOT));
        }
    }
}
