package cn.hip.platform.integration.ofd;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class OfdPreviewWriterTest {
    private static final String DOC_ID = "9364b1968a404be997b523d2df2bfdf3";
    private static final String NS = "http://www.ofdspec.org/2016";
    private final OfdPreviewWriter writer = new OfdPreviewWriter();

    @Test
    void genuinePackageEmbedsOriginalMetadataAndFontWithoutSignatures() throws Exception {
        String body = "未签署预览\r\n患者：测试髙\n病历内容：检查 & <检验>，罕见字龘。";
        byte[] metadata = "{\"patient\":\"测试𠮷\",\"sourceVersion\":7,\"unsigned\":true,\"value\":\"<&>\"}"
                .getBytes(StandardCharsets.UTF_8);
        var entries = unpack(writer.write(DOC_ID, "历史病历预览", body, metadata));
        var ofd = xml(entries, "OFD.xml");

        assertEquals("OFD-H", ofd.getDocumentElement().getAttribute("DocType"));
        assertEquals(NS, ofd.getDocumentElement().getNamespaceURI());
        assertEquals(1, ofd.getElementsByTagNameNS(NS, "DocBody").getLength());
        assertEquals(DOC_ID, ofd.getElementsByTagNameNS(NS, "DocID").item(0).getTextContent());
        assertEquals("Doc_0/Document.xml", ofd.getElementsByTagNameNS(NS, "DocRoot").item(0).getTextContent());
        assertEquals("历史病历预览", ofd.getElementsByTagNameNS(NS, "Title").item(0).getTextContent());
        var custom = (Element) ofd.getElementsByTagNameNS(NS, "CustomData").item(0);
        assertEquals("SourceMetadata", custom.getAttribute("Name"));
        assertEquals(new String(metadata, StandardCharsets.UTF_8), custom.getTextContent());
        assertTrue(entries.keySet().stream().noneMatch(name -> name.startsWith("Doc_1/")));
        assertTrue(entries.keySet().stream().noneMatch(name -> name.contains("/Signs/")
                || name.endsWith("Signature.xml") || name.endsWith("Signatures.xml")
                || name.endsWith("SignedValue.dat")));

        var document = xml(entries, "Doc_0/Document.xml");
        assertEquals("/Doc_0/Attachs/Attachments.xml",
                document.getElementsByTagNameNS(NS, "Attachments").item(0).getTextContent());
        var attachments = xml(entries, "Doc_0/Attachs/Attachments.xml");
        var attachment = (Element) attachments.getElementsByTagNameNS(NS, "Attachment").item(0);
        assertEquals("source-metadata.json", attachment.getAttribute("Name"));
        assertEquals("JSON", attachment.getAttribute("Format"));
        String metadataPath = attachments.getElementsByTagNameNS(NS, "FileLoc").item(0).getTextContent();
        assertEquals("/Doc_0/Attachs/Attach_0.json", metadataPath);
        assertFalse(entries.containsKey("Doc_0/Attachs/source-metadata.json"));
        assertArrayEquals(metadata, entries.get(metadataPath.substring(1)));

        var resources = xml(entries, "Doc_0/PublicRes.xml");
        assertEquals(1, resources.getElementsByTagNameNS(NS, "Font").getLength());
        String fontPath = resources.getElementsByTagNameNS(NS, "FontFile").item(0).getTextContent();
        try (var font = getClass().getResourceAsStream("/fonts/ofd/NotoSansCJKsc-Regular.otf")) {
            assertNotNull(font);
            assertArrayEquals(font.readAllBytes(), entries.get("Doc_0/Res/" + fontPath));
        }
        assertTrue(pageText(entries).contains(body.replace("\r", "").replace("\n", "")),
                "Original text must survive XML escaping");
    }

    @Test
    void longClinicalTextPaginatesThroughItsLastCharacter() throws Exception {
        var body = new StringBuilder("未签署预览\n");
        for (int i = 0; i < 200; i++) {
            body.append("第").append(i).append("段临床病历，患者髙的历史正文应完整保留，不能在分页时截断。\n");
        }
        body.append("最终校验字龘");
        var entries = unpack(writer.write(DOC_ID, "长病历预览", body.toString(), "{}".getBytes(StandardCharsets.UTF_8)));
        long pages = entries.keySet().stream().filter(name -> name.endsWith("/Content.xml")).count();
        assertTrue(pages > 1, "The real layout engine must paginate the long body");
        assertTrue(pageText(entries).contains(body.toString().replace("\n", "")),
                "All 200 paragraphs and the final marker must remain in order");
    }

    @Test
    void unsupportedGlyphIsAnExplicitFailure() {
        String missing = "\uFFFF";
        IOException failure = assertThrows(IOException.class,
                () -> writer.write(DOC_ID, "病历预览", "未签署预览" + missing, new byte[]{'{', '}'}));
        assertTrue(failure.getMessage().contains("U+FFFF"));
    }

    @Test
    void supplementaryNameGlyphCannotSilentlyBecomeTwoMissingGlyphs() {
        IOException failure = assertThrows(IOException.class,
                () -> writer.write(DOC_ID, "病历预览", "患者：测试𠮷", new byte[]{'{', '}'}));
        assertTrue(failure.getMessage().contains("补充平面字符 U+20BB7"));
    }

    @Test
    void malformedUtf8MetadataCannotBeSilentlyReplaced() {
        assertThrows(java.nio.charset.CharacterCodingException.class,
                () -> writer.write(DOC_ID, "病历预览", "未签署预览", new byte[]{(byte) 0xC3, 0x28}));
    }

    private static Map<String, byte[]> unpack(byte[] bytes) throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.isDirectory()) {
                    assertNull(entries.put(entry.getName(), zip.readAllBytes()), "Duplicate OFD ZIP entry");
                }
            }
        }
        return entries;
    }

    private static Document xml(Map<String, byte[]> entries, String path) throws Exception {
        byte[] bytes = entries.get(path);
        assertNotNull(bytes, path);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static String pageText(Map<String, byte[]> entries) throws Exception {
        var text = new StringBuilder();
        var pages = xml(entries, "Doc_0/Document.xml").getElementsByTagNameNS(NS, "Page");
        for (int i = 0; i < pages.getLength(); i++) {
            String location = ((Element) pages.item(i)).getAttribute("BaseLoc");
            var codes = xml(entries, "Doc_0/" + location).getElementsByTagNameNS(NS, "TextCode");
            for (int j = 0; j < codes.getLength(); j++) text.append(codes.item(j).getTextContent());
        }
        return text.toString();
    }
}
