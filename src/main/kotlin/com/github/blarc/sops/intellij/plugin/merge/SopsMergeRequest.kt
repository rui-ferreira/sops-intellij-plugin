package com.github.blarc.sops.intellij.plugin.merge

import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.merge.MergeResult
import com.intellij.diff.merge.TextMergeRequest
import com.intellij.openapi.util.Key

/**
 * The decrypted contents of the merge that [delegate] describes, so that a conflict in a SOPS file can
 * be resolved without decrypting it first.
 *
 * The result is not applied through this request. The window applies the result of the request it was
 * opened with, which is [delegate], so [SopsMergeTool] encrypts what this request holds into the file
 * that is being merged before it lets the window finish the merge.
 */
class SopsMergeRequest(
    private val delegate: TextMergeRequest,
    private val contents: List<DocumentContent>,
    private val output: DocumentContent,
) : TextMergeRequest() {

    override fun getContents(): List<DocumentContent> = contents

    /** The merged contents, decrypted, which are not backed by the file that is being merged. */
    override fun getOutputContent(): DocumentContent = output

    override fun getTitle(): String? = delegate.title

    override fun getContentTitles(): List<String> = delegate.contentTitles

    // The window looks up the callback that tells the version control system that the conflict has
    // been resolved, and the keys that decide how the window behaves, on the request it is given. Both
    // have to be the ones of the request that is being taken the place of.
    override fun <T : Any?> getUserData(key: Key<T>): T? = delegate.getUserData(key)

    override fun <T : Any?> putUserData(key: Key<T>, value: T?) = delegate.putUserData(key, value)

    override fun onAssigned(assigned: Boolean) = delegate.onAssigned(assigned)

    /**
     * Only reached when the window is given this request directly, which it is not. The contents that
     * are being merged belong to [delegate], so it applies them.
     */
    override fun applyResult(result: MergeResult) = delegate.applyResult(result)
}
