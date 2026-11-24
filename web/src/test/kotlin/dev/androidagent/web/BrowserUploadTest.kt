package dev.androidagent.web

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.nio.file.Files

/** Which files a chat may upload, and who a page's file chooser answers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserUploadTest {
    private val root = Files.createTempDirectory("uploads").toFile()
    private val chat = File(root, "chat-a/workspace").apply { mkdirs() }
    private lateinit var host: AndroidBrowserHost

    @After fun clean() {
        if (::host.isInitialized) runBlocking { host.closeAll() }
        root.deleteRecursively()
    }

    private fun refusal(paths: List<String>): String =
        (runCatching { UploadFiles.resolve(chat, buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } }) }.exceptionOrNull() as WebFailure).errorType

    @Test fun onlyFilesInsideTheChatFolderCanBeUploaded() {
        File(chat, "docs").mkdirs()
        val cv = File(chat, "docs/cv.pdf").apply { writeText("pdf") }
        File(root, "chat-b/workspace").mkdirs()
        File(root, "chat-b/workspace/secret.txt").writeText("other chat")
        File(root, "auth.json").writeText("token")

        assertEquals(listOf(cv.toPath().toRealPath().toFile()), UploadFiles.resolve(chat, buildJsonArray { add(JsonPrimitive("docs/cv.pdf")) }))
        assertEquals(listOf(cv.toPath().toRealPath().toFile()), UploadFiles.resolve(chat, JsonPrimitive("chat:docs/cv.pdf")))
        assertEquals(listOf(cv.toPath().toRealPath().toFile()), UploadFiles.resolve(chat, JsonPrimitive(cv.absolutePath)))
        assertEquals("outside_chat_folder", refusal(listOf("../../auth.json")))
        assertEquals("outside_chat_folder", refusal(listOf(File(root, "chat-b/workspace/secret.txt").absolutePath)))
        assertEquals("outside_chat_folder", refusal(listOf("../../chat-b/workspace/secret.txt")))
        assertEquals("no_such_file", refusal(listOf("docs")))
        assertEquals("no_such_file", refusal(listOf("missing.png")))
        assertEquals("no_files", refusal(emptyList()))
        assertEquals("too_many_files", refusal(List(UploadFiles.MAX_FILES + 1) { "docs/cv.pdf" }))
    }

    @Test fun aLinkLeavingTheChatFolderIsRefused() {
        val outside = File(root, "auth.json").apply { writeText("token") }
        val link = File(chat, "innocent.txt").toPath()
        val made = runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess
        org.junit.Assume.assumeTrue("this file system makes links", made)
        assertEquals("outside_chat_folder", refusal(listOf("innocent.txt")))
    }

    private class Params(private val chooserMode: Int) : WebChromeClient.FileChooserParams() {
        override fun getMode() = chooserMode
        override fun getAcceptTypes() = arrayOf("*/*")
        override fun isCaptureEnabled() = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun createIntent() = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
    }

    private fun choose(mode: Int = WebChromeClient.FileChooserParams.MODE_OPEN): Pair<Boolean, Array<Uri>?> {
        val view = checkNotNull(host.viewOf(chat.absolutePath))
        var answers = 0
        var given: Array<Uri>? = null
        val handled = checkNotNull(view.webChromeClient).onShowFileChooser(view, ValueCallback { answers++; given = it }, Params(mode))
        assertEquals("a chooser is answered exactly once", 1, answers)
        return handled to given
    }

    private fun openHost() {
        host = AndroidBrowserHost(RuntimeEnvironment.getApplication(), shareFile = { Uri.parse("content://test.files/" + it.name) })
        host.open(chat.absolutePath)
    }

    @Test fun armedFilesGoToOneChooserOnly() {
        openHost()
        val file = File(chat, "photo.png").apply { writeText("png") }
        val upload = PendingUpload(listOf(file))
        host.armUpload(chat.absolutePath, upload)
        val (handled, given) = choose()
        assertTrue(handled)
        assertEquals(listOf("content://test.files/photo.png"), given!!.map { it.toString() })
        assertTrue(upload.outcome.isCompleted)
        assertNull(runBlocking { upload.outcome.await() }.reason)
        // The next chooser, opened by a script or a later press, gets nothing.
        assertNull(choose().second)
    }

    @Test fun severalFilesAreRefusedByASingleFileChooser() {
        openHost()
        val files = listOf(File(chat, "a.txt").apply { writeText("a") }, File(chat, "b.txt").apply { writeText("b") })
        val single = PendingUpload(files)
        host.armUpload(chat.absolutePath, single)
        assertNull(choose().second)
        assertNotNull(runBlocking { single.outcome.await() }.reason)

        val multiple = PendingUpload(files)
        host.armUpload(chat.absolutePath, multiple)
        assertEquals(2, choose(WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE).second!!.size)
        assertNull(runBlocking { multiple.outcome.await() }.reason)
    }

    @Test fun anUnarmedChooserOnAnAgentHeldPageGetsNothing() {
        openHost()
        val (handled, given) = choose()
        assertTrue(handled)
        assertNull(given)
    }
}
