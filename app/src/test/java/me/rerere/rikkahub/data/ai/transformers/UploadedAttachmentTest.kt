package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessagePart
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class UploadedAttachmentTest {
    private val directory = Files.createTempDirectory("uploads").toFile()

    @After fun cleanup() {
        directory.deleteRecursively()
    }

    @Test fun binaryFilesAreNotDecodedIntoPromptGarbage() {
        val file = directory.resolve("archive.zip").apply { writeBytes(byteArrayOf(80, 75, 3, 4, 0, -1)) }
        assertNull(readAttachmentText(file))
        file.writeBytes(byteArrayOf(-1, -2))
        assertNull(readAttachmentText(file))
    }

    @Test fun utf8TextPreservesUnicodeAndIsBounded() {
        val file = directory.resolve("notes.txt").apply { writeText("中文🙂\nhello") }
        assertEquals("中文🙂\nhello", readAttachmentText(file))
        file.writeText("a".repeat(100))
        val text = readAttachmentText(file, maxChars = 10)!!
        assertTrue(text.startsWith("a".repeat(10)))
        assertTrue(text.contains("truncated"))
    }

    @Test fun imageDescriptorPointsToTheSameLocalVisualFileAndEscapesName() {
        val file = directory.resolve("unique-image.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val image = UIMessagePart.Image(url = file.toURI().toString())
        assertEquals(file.canonicalFile, uploadedAttachmentFile(image, directory))
        val description = uploadedAttachmentDescription(image, file, "同名\"图片.png")
        assertTrue(description.contains("/upload/unique-image.png"))
        assertTrue(description.contains("同名\\\"图片.png"))
        assertTrue(description.contains("\"sizeBytes\":3"))
        assertNull(uploadedAttachmentFile(UIMessagePart.Image("https://example.com/image.png"), directory))
        assertNull(uploadedAttachmentFile(image, directory.resolve("another-upload")))
    }
}
