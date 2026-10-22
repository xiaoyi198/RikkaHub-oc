package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File
import java.net.URI

/** Keep the visual part and describe the exact same local file to workspace tools. */
class UploadedAttachmentTransformer(
    private val workspaces: WorkspaceRepository,
    private val files: FilesManager,
) : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        val id = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaces.getById(id) ?: return messages
        if (!workspace.capabilities().rootfs ||
            workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            return messages
        }
        return withContext(Dispatchers.IO) {
            val directory = File(ctx.context.filesDir, FileFolders.UPLOAD)
            messages.map { message ->
                if (message.role != MessageRole.USER) return@map message
                message.copy(parts = buildList {
                    message.parts.forEach { part ->
                        val file = uploadedAttachmentFile(part, directory)
                        if (file != null) {
                            val managed = files.getByRelativePath("${FileFolders.UPLOAD}/${file.name}")
                            val name = (part as? UIMessagePart.Document)?.fileName ?: managed?.displayName ?: file.name
                            add(UIMessagePart.Text(uploadedAttachmentDescription(part, file, name)))
                        }
                        add(part)
                    }
                })
            }
        }
    }
}

internal fun uploadedAttachmentFile(part: UIMessagePart, directory: File): File? {
    val url = when (part) {
        is UIMessagePart.Image -> part.url
        is UIMessagePart.Document -> part.url
        is UIMessagePart.Video -> part.url
        is UIMessagePart.Audio -> part.url
        else -> return null
    }
    return runCatching {
        val uri = URI(url)
        if (uri.scheme != "file") return null
        File(uri).canonicalFile.takeIf { it.isFile && it.parentFile == directory.canonicalFile }
    }.getOrNull()
}

internal fun uploadedAttachmentDescription(part: UIMessagePart, file: File, name: String): String =
    "Uploaded attachment (the following media part, if present, is this same file): " + buildJsonObject {
        put("name", name)
        put("path", "/upload/${file.name}")
        put("sizeBytes", file.length())
        put(
            "type",
            when (part) {
                is UIMessagePart.Image -> "image"
                is UIMessagePart.Video -> "video"
                is UIMessagePart.Audio -> "audio"
                else -> "file"
            }
        )
        if (part is UIMessagePart.Document) put("mime", part.mime)
    }
