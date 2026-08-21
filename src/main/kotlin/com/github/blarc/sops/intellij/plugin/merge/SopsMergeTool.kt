package com.github.blarc.sops.intellij.plugin.merge

import com.github.blarc.sops.intellij.plugin.Icons
import com.github.blarc.sops.intellij.plugin.SopsBundle.message
import com.github.blarc.sops.intellij.plugin.diff.SopsDiffContents
import com.github.blarc.sops.intellij.plugin.diff.SopsDiffContents.encryptedText
import com.github.blarc.sops.intellij.plugin.notifications.Notification
import com.github.blarc.sops.intellij.plugin.notifications.sendNotification
import com.github.blarc.sops.intellij.plugin.services.SopsService
import com.github.blarc.sops.intellij.plugin.settings.AppSettings
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.merge.MergeContext
import com.intellij.diff.merge.MergeContextEx
import com.intellij.diff.merge.MergeRequest
import com.intellij.diff.merge.MergeResult
import com.intellij.diff.merge.MergeTool
import com.intellij.diff.merge.TextMergeRequest
import com.intellij.diff.merge.TextMergeTool
import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent

/** Whether a merge window shows the decrypted contents instead of the encrypted ones. */
private val SHOW_DECRYPTED = Key.create<Boolean>("sops.merge.showDecrypted")

/**
 * A window that has not been switched shows what the setting asks for, so that the same window can be
 * switched back and forth without changing what every other window shows.
 */
private fun MergeContext.isShowingDecrypted(): Boolean =
    getUserData(SHOW_DECRYPTED) ?: AppSettings.instance.sopsDecryptDiff

/**
 * Shows the contents of the window again, with [tool]. Only a window that can be reopened shows this
 * tool at all, which [SopsMergeTool.canShow] makes sure of.
 */
private fun MergeContext.reopenWith(tool: MergeTool) = (this as MergeContextEx).reopenWithTool(tool)

/**
 * Resolves a conflict in a SOPS file in its decrypted contents, so that the conflict can be seen and
 * resolved without decrypting the file first. It takes the place of the merge window the platform
 * would show whenever the contents that are being merged are encrypted with SOPS, and shows them with
 * that same window, which keeps everything it can do with any other conflict.
 *
 * Decrypting runs SOPS, which cannot be waited for while the window is being created. The window shows
 * that it is decrypting and is reopened once the decrypted contents are known. The action that switches
 * between the encrypted and the decrypted contents is added to the toolbar of the window.
 *
 * The merged contents are encrypted back into the file that is being merged when the merge is applied,
 * because the file holds encrypted contents while the window resolves the conflict in decrypted ones.
 * Taking one of the two sides or cancelling writes the encrypted contents of that side, which the
 * window the place of which is taken does itself.
 */
class SopsMergeTool : MergeTool {

    override fun canShow(context: MergeContext, request: MergeRequest): Boolean {
        // The window has to be reopenable, because the contents are decrypted in the background and
        // because the action switches between the decrypted and the encrypted contents.
        if (context !is MergeContextEx || context.project == null) return false
        // A request of our own is already showing the decrypted contents.
        if (request is SopsMergeRequest) return false
        if (request !is TextMergeRequest) return false
        if (SopsDiffContents.encryptedContents(request.contents).isEmpty()) return false
        return TextMergeTool.INSTANCE.canShow(context, request)
    }

    override fun createComponent(context: MergeContext, request: MergeRequest): MergeTool.MergeViewer {
        val project = context.project
        // The window only shows this tool when it can, so it always merges the contents of documents.
        // Anything else is shown by the window of the platform, as if this tool were not there.
        if (project == null || context !is MergeContextEx || request !is TextMergeRequest) {
            return TextMergeTool.INSTANCE.createComponent(context, request)
        }

        if (!context.isShowingDecrypted()) {
            return SopsMergeViewer(TextMergeTool.INSTANCE.createComponent(context, request), context, this)
        }

        if (!SopsDiffContents.areDecrypted(request.contents)) {
            return SopsDecryptingMergeViewer(project, context, request, this)
        }

        val decryptedRequest = decryptedRequest(project, request)
        return SopsMergeViewer(
            TextMergeTool.INSTANCE.createComponent(context, decryptedRequest),
            context,
            this,
            SopsMergeResult(project, request, decryptedRequest)
        )
    }

    /**
     * The [request] with every content that is encrypted with SOPS replaced by its decrypted content.
     * Contents that are not encrypted are shown as they are, so a file that one side encrypted with
     * SOPS can also be merged.
     */
    private fun decryptedRequest(project: Project, request: TextMergeRequest): SopsMergeRequest {
        val contentFactory = DiffContentFactory.getInstance()
        val contents = request.contents.map { content ->
            val decryptedText = SopsDiffContents.decryptedText(content) ?: return@map content
            contentFactory.create(project, decryptedText, content.contentType)
        }

        // The merged contents start out empty, because the window fills them with the side it starts
        // the merge from. They have to be editable, because the conflict is resolved in them.
        val output = contentFactory.createEditable(project, "", request.outputContent.contentType)
        return SopsMergeRequest(request, contents, output)
    }
}

/**
 * Encrypts the decrypted contents that a merge window shows into the file that is being merged.
 */
private class SopsMergeResult(
    private val project: Project,
    private val request: TextMergeRequest,
    private val decryptedRequest: SopsMergeRequest,
) {

    /**
     * Encrypts what the window shows into the file that is being merged and tells whether it could be
     * done. Encrypting runs SOPS, which the window is blocked on, because the merge is finished as soon
     * as this returns.
     */
    fun encryptIntoMergedFile(): Boolean {
        // The contents are encrypted the way one of the sides is, so that the file keeps the keys it
        // was encrypted with. Which side does not matter, because they all share those keys.
        val template = SopsDiffContents.encryptedContents(request.contents).firstOrNull()
        if (template == null) {
            sendNotification(Notification(message = message("notification.merge.no-template")), project)
            return false
        }

        val decryptedText = runReadAction { decryptedRequest.outputContent.document.text }
        val templateText = template.encryptedText()
        val fileName = request.outputContent.highlightFile?.name

        val encryptedText = runWithModalProgressBlocking(project, message("merge.encrypting")) {
            project.service<SopsService>().encryptLikeTemplate(decryptedText, templateText, fileName)
        } ?: return false

        val document = request.outputContent.document
        val isWritten = DiffUtil.executeWriteCommand(document, project, message("merge.encrypting")) {
            document.setText(StringUtil.convertLineSeparators(encryptedText))
        }
        if (!isWritten) {
            return false
        }

        // The window shows the decrypted contents in documents of its own, so it saves those instead of
        // the file that is being merged. That file has to be written before the version control system
        // is told that the conflict has been resolved, because it reads the file from disk.
        FileDocumentManager.getInstance().saveDocument(document)
        return true
    }
}

/**
 * The window that shows the contents, with the action that switches between the decrypted and the
 * encrypted contents added to its toolbar.
 */
private class SopsMergeViewer(
    private val delegate: MergeTool.MergeViewer,
    private val context: MergeContext,
    private val tool: MergeTool,
    /** How to encrypt what is shown, when the decrypted contents are what is shown. */
    private val result: SopsMergeResult? = null,
) : MergeTool.MergeViewer by delegate {

    override fun init(): MergeTool.ToolbarComponents {
        val components = delegate.init()
        components.toolbarActions = components.toolbarActions.orEmpty() + SopsShowDecryptedAction(context, tool)
        return components
    }

    /**
     * Applying the decrypted contents encrypts them into the file that is being merged first, because
     * the file holds encrypted contents. The merge is left unfinished when they cannot be encrypted, so
     * that the window stays open and nothing is lost. Taking one of the two sides or cancelling writes
     * the encrypted contents of that side, which [delegate] does itself.
     */
    override fun getResolveAction(result: MergeResult): Action? {
        val action = delegate.getResolveAction(result)
        val mergeResult = this.result
        if (action == null || result != MergeResult.RESOLVED || mergeResult == null) {
            return action
        }
        return SopsApplyAction(action, mergeResult)
    }
}

/** Encrypts the merged contents into the file that is being merged before [delegate] applies them. */
private class SopsApplyAction(
    private val delegate: Action,
    private val result: SopsMergeResult,
) : Action by delegate {

    override fun actionPerformed(e: ActionEvent) {
        if (!result.encryptIntoMergedFile()) return
        delegate.actionPerformed(e)
    }
}

/** Leaves the file that is being merged as it is, while its contents are being decrypted. */
private class SopsAbortAction(private val context: MergeContext) : AbstractAction(message("merge.action.abort")) {

    override fun actionPerformed(e: ActionEvent) = context.finishMerge(MergeResult.CANCEL)
}

/**
 * Shows that the contents are being decrypted and reopens the merge window once they are, which is when
 * the decrypted contents are shown.
 */
private class SopsDecryptingMergeViewer(
    private val project: Project,
    private val context: MergeContext,
    private val request: TextMergeRequest,
    private val tool: MergeTool,
) : MergeTool.MergeViewer {

    private val panel = DiffUtil.createMessagePanel(message("merge.decrypting"))
    private var isDisposed = false

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent? = null

    /**
     * The merge can be aborted while the contents are being decrypted, which leaves the file as it is.
     * The other results are not offered yet, because the contents they would apply are not being shown.
     */
    override fun getResolveAction(result: MergeResult): Action? {
        if (result != MergeResult.CANCEL) return null
        return SopsAbortAction(context)
    }

    override fun init(): MergeTool.ToolbarComponents {
        project.service<SopsService>().decryptContents(request.contents) { isDecrypted ->
            withContext(Dispatchers.EDT) {
                if (isDisposed) return@withContext
                // Contents that could not be decrypted are shown as they are, so that the toolbar
                // shows what the window shows and decrypting is not tried over and over again.
                if (!isDecrypted) {
                    context.putUserData(SHOW_DECRYPTED, false)
                }
                // The decrypted contents are remembered, so the reopened window shows them without
                // decrypting them again.
                context.reopenWith(tool)
            }
        }

        val components = MergeTool.ToolbarComponents()
        components.toolbarActions = listOf(SopsShowDecryptedAction(context, tool))
        return components
    }

    override fun dispose() {
        isDisposed = true
    }
}

/**
 * Switches the merge window between the encrypted and the decrypted contents. It is pressed while the
 * decrypted contents are shown, so that a window that shows the contents as they are on disk looks like
 * any other merge window.
 */
private class SopsShowDecryptedAction(
    private val context: MergeContext,
    private val tool: MergeTool,
) : ToggleAction(
    message("diff.action.show-decrypted"),
    null,
    Icons.KEY_ICON.getThemeBasedIcon()
), DumbAware {

    override fun isSelected(e: AnActionEvent) = context.isShowingDecrypted()

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        context.putUserData(SHOW_DECRYPTED, state)
        context.reopenWith(tool)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}
