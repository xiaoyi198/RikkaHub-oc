package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import java.io.File

object DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                val content = readDocumentContent(document)
                                val prompt = """
                                  <UploadFile name="${document.fileName}">
                                  ```
                                  $content
                                  ```
                                  </UploadFile>
                                """.trimMargin()
                                add(0, UIMessagePart.Text(prompt))
                            }
                        }
                    }
                )
            }
        }
    }

    private fun parsePdfAsText(file: File): String {
        return PdfParser.parserPdf(file)
    }

    private fun parseDocxAsText(file: File): String {
        return DocxParser.parse(file)
    }

    private fun parsePptxAsText(file: File): String {
        return PptxParser.parse(file)
    }

    private fun parseEpubAsText(file: File): String {
        return EpubParser.parse(file)
    }

    internal fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[ERROR, invalid file uri: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }
        return runCatching {
            when (document.mime) {
                "application/pdf" -> parsePdfAsText(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptxAsText(file)
                "application/epub+zip" -> parseEpubAsText(file)
                else -> readAttachmentText(file)
                    ?: "[Binary attachment: use workspace tools to inspect or process the original file.]"
            }
        }.getOrElse {
            "[ERROR, failed to read file: ${document.fileName}]"
        }
    }
}

/** Decode bounded UTF-8 text; binary attachments never enter the model as replacement-character garbage. */
internal fun readAttachmentText(file: File, maxChars: Int = 512 * 1024): String? = runCatching {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    java.io.InputStreamReader(file.inputStream(), decoder).use { reader ->
        val text = StringBuilder()
        val buffer = CharArray(8192)
        while (text.length < maxChars) {
            val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - text.length))
            if (count < 0) return@use text.toString()
            if ((0 until count).any { buffer[it] < ' ' && buffer[it] !in "\n\r\t\u000c" }) return@use null
            text.append(buffer, 0, count)
        }
        if (reader.read() >=
            0) {
            text.append("\n[Attachment text truncated; read the original file with workspace tools.]")
        }
        text.toString()
    }
}.getOrNull()
