package edu.illinois.cs.cs125.intellijplugin

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.TestStatusListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerTopics
import com.intellij.openapi.components.BaseComponent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiFile
import org.apache.commons.httpclient.HttpStatus
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder
import org.jetbrains.annotations.NotNull
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.NetworkInterface
import java.nio.file.Files
import java.time.Instant
import java.util.*
import kotlin.concurrent.timer

class Component :
        BaseComponent,
        CaretListener,
        VisibleAreaListener,
        EditorMouseListener,
        SelectionListener,
        DocumentListener,
        ProjectManagerListener,
        CompilationStatusListener,
        FileEditorManagerListener {

    private val log = Logger.getInstance("edu.illinois.cs.cs125.intellijplugin")

    @NotNull
    override fun getComponentName(): String {
        return "CS125 Plugin"
    }

    var currentProjectCounters = mutableMapOf<Project, Counter>()

    data class ProjectConfiguration(
            val destination: String,
            val name: String,
            val emailLocation: String?,
            var email: String?,
            val networkAddress: String?
    )

    var projectConfigurations = mutableMapOf<Project, ProjectConfiguration>()

    private val versionProperties = Properties()
    private var version = ""

    override fun initComponent() {
        log.trace("initComponent")

        val connection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(ProjectManager.TOPIC, this)

        val state = Persistence.getInstance().persistentState
        log.trace("Loading " + state.savedCounters.size.toString() + " counters")

        if (state.UUID == "") {
            state.UUID = UUID.randomUUID().toString()
            if (state.savedCounters.size != 0) {
                log.warn("Must be updating plugin since saved counters exist before UUID is set")
            }
        }
        for (counter in state.savedCounters) {
            if (counter.UUID != state.UUID) {
                log.warn("Altering counter with bad UUID: ${counter.UUID} != ${state.UUID}")
                counter.UUID = state.UUID
            }
        }
        for (counter in state.activeCounters) {
            if (counter.UUID != state.UUID) {
                log.warn("Altering counter with bad UUID: ${counter.UUID} != ${state.UUID}")
                counter.UUID = state.UUID
            }
            counter.end = state.lastSave
            state.savedCounters.add(counter)
        }
        state.activeCounters.clear()

        version = try {
            versionProperties.load(this.javaClass.getResourceAsStream("/version.properties"))
            versionProperties.getProperty("version")
        } catch (e: Exception) {
            ""
        }

        ApplicationManager.getApplication().invokeLater {
            EditorFactory.getInstance().eventMulticaster.addCaretListener(this)
            EditorFactory.getInstance().eventMulticaster.addVisibleAreaListener(this)
            EditorFactory.getInstance().eventMulticaster.addEditorMouseListener(this)
            EditorFactory.getInstance().eventMulticaster.addSelectionListener(this)
            EditorFactory.getInstance().eventMulticaster.addDocumentListener(this)
        }
    }

    var uploadBusy = false
    var lastUploadFailed = false
    var lastUploadAttempt: Long = 0
    var lastSuccessfulUpload: Long = 0

    @Synchronized
    fun uploadCounters() {
        log.trace("uploadCounters")
        if (uploadBusy) {
            log.warn("Previous upload still busy")
            return
        }

        val state = Persistence.getInstance().persistentState
        if (state.savedCounters.size == 0) {
            log.trace("No counters to upload")
            return
        }

        if (lastUploadFailed && Instant.now().toEpochMilli() - lastUploadAttempt <= shortestUploadWait) {
            log.trace("Need to wait for longer to retry upload")
            return
        }

        val startIndex = 0
        val endIndex = state.savedCounters.size

        val uploadingCounters = mutableListOf<Counter>()
        uploadingCounters.addAll(state.savedCounters)

        if (uploadingCounters.isEmpty()) {
            log.trace("No counters to upload")
            return
        }

        val project = try {
            ProjectManager.getInstance().openProjects.find { project ->
                val window = WindowManager.getInstance().suggestParentWindow(project)
                window != null && window.isFocused
            } ?: ProjectManager.getInstance().openProjects[0]
        } catch (e: Exception) {
            null
        }

        if (project == null) {
            log.warn("Can't find project in uploadCounters")
            return
        }

        val uploadCounterTask = object : Task.Backgroundable(project, "Uploading CS 125 logs...",
                false) {
            override fun run(progressIndicator: ProgressIndicator) {
                val now = Instant.now().toEpochMilli()

                val projectConfiguration = projectConfigurations[project]
                if (projectConfiguration == null) {
                    log.warn("no configuration for project in uploadTask")
                    return
                }

                val json = Counters.adapter.toJson(Counters(uploadingCounters))
                if (json == null) {
                    log.warn("couldn't convert counters to JSON")
                    return
                }

                val destination = projectConfiguration.destination
                if (destination == "console") {
                    log.warn("Uploading to console")
                    log.trace(json)
                    state.savedCounters.subList(startIndex, endIndex).clear()
                    log.trace("Upload succeeded")
                    lastSuccessfulUpload = now
                    lastUploadFailed = false
                    return
                }

                val httpClient = HttpClientBuilder.create().build()
                val counterPost = HttpPost(destination)
                counterPost.addHeader("content-type", "application/json")
                counterPost.entity = StringEntity(json)

                lastUploadFailed = try {
                    val response = httpClient.execute(counterPost)
                    assert(response.statusLine.statusCode == HttpStatus.SC_OK) { "upload failed" }

                    state.savedCounters.subList(startIndex, endIndex).clear()
                    log.trace("Upload succeeded")
                    lastSuccessfulUpload = now
                    false
                } catch (e: Exception) {
                    log.warn("Upload failed: $e")
                    true
                } finally {
                    uploadBusy = false
                    lastUploadAttempt = now
                }
            }
        }
        ProgressManager.getInstance().run(uploadCounterTask)
    }

    private val stateTimerPeriodSec = 5
    private val maxSavedCounters = (2 * 60 * 60 / stateTimerPeriodSec) // 2 hours of logs
    private val uploadLogCountThreshold = (15 * 60 / stateTimerPeriodSec) // 15 minutes of logs
    //private val shortestUploadWait = 10 * 60 * 1000 // 10 minutes
    private val shortestUploadWait = 30 * 1000
    private val shortestUploadInterval = 30 * 60 * 1000 // 30 minutes

    @Synchronized
    fun rotateCounters() {
        log.trace("rotateCounters")

        val state = Persistence.getInstance().persistentState
        val end = Instant.now().toEpochMilli()

        for ((project, counter) in currentProjectCounters) {
            if (counter.isEmpty()) {
                continue
            }
            counter.end = end

            val fileDocumentManager = FileDocumentManager.getInstance()
            val openFiles: MutableMap<String, FileInfo> = mutableMapOf()
            for (file in FileEditorManager.getInstance(project).openFiles.filterNotNull()) {
                val document = fileDocumentManager.getCachedDocument(file) ?: continue
                openFiles[file.path] = FileInfo(file.path, document.lineCount)
            }
            counter.openFiles = openFiles.values.toMutableList()
            counter.openFileCount = counter.openFiles.size
            counter.closed = false

            log.trace("Counter $counter")

            state.savedCounters.add(counter)
            state.activeCounters.remove(counter)

            val newCounter = Counter(
                    state.UUID,
                    state.counterIndex++,
                    counter.index,
                    projectConfigurations[project]?.name ?: "",
                    projectConfigurations[project]?.email ?: "",
                    projectConfigurations[project]?.networkAddress ?: "",
                    version
            )
            currentProjectCounters[project] = newCounter
            state.activeCounters.add(newCounter)
        }

        if (state.savedCounters.size > maxSavedCounters) {
            state.savedCounters.subList(0, maxSavedCounters - state.savedCounters.size).clear()
        }

        val now = Instant.now().toEpochMilli()
        if (state.savedCounters.size >= uploadLogCountThreshold) {
            uploadCounters()
        } else if (now - lastSuccessfulUpload > shortestUploadInterval) {
            uploadCounters()
        }
    }

    private var stateTimer: Timer? = null
    override fun projectOpened(project: Project) {
        log.trace("projectOpened")

        val configurationFile = File(project.basePath.toString()).resolve(File(".intellijlogger.yaml"))
        if (!configurationFile.exists()) {
            log.trace("no project configuration found")
            return
        }

        val projectConfiguration = try {
            @Suppress("UNCHECKED_CAST")
            val configuration = Yaml().load(Files.newBufferedReader(configurationFile.toPath())) as Map<String, String>

            val destination = configuration["destination"]
                    ?: throw IllegalArgumentException("destination missing from configuration")
            val name = configuration["name"] ?: throw IllegalArgumentException("name missing from configuration")

            val emailLocation = configuration["emailLocation"]
            val email = if (emailLocation == null) {
                null
            } else {
                File(project.basePath.toString()).resolve(File(emailLocation)).let {
                    if (it.exists()) {
                        it.readText().trim()
                    } else {
                        null
                    }
                }
            }

            val networkAddress = try {
                NetworkInterface.getNetworkInterfaces().toList().flatMap { networkInterface ->
                    networkInterface.inetAddresses.toList()
                            .filter { it.address.size == 4 }
                            .filter { !it.isLoopbackAddress }
                            .filter { it.address[0] != 10.toByte() }
                            .map { it.hostAddress }
                }.first()
            } catch (e: Exception) {
                null
            }

            ProjectConfiguration(destination, name, emailLocation, email, networkAddress)
        } catch (e: Exception) {
            log.debug("Can't load project configuration: $e")
            return
        }

        log.trace(projectConfiguration.toString())
        projectConfigurations[project] = projectConfiguration

        val state = Persistence.getInstance().persistentState

        val newCounter = Counter(state.UUID,
                state.counterIndex++,
                -1,
                projectConfiguration.name,
                projectConfiguration.email,
                projectConfiguration.networkAddress,
                version)
        newCounter.opened = true
        currentProjectCounters[project] = newCounter
        state.activeCounters.add(newCounter)

        if (currentProjectCounters.size == 1) {
            stateTimer?.cancel()
            stateTimer = timer("edu.illinois.cs.cs125", true,
                    stateTimerPeriodSec * 1000L, stateTimerPeriodSec * 1000L) {
                rotateCounters()
            }
        }
        uploadCounters()

        project.messageBus.connect().subscribe(CompilerTopics.COMPILATION_STATUS, this)
        project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, this)
    }

    override fun projectClosing(project: Project) {
        log.trace("projectClosing")

        val currentCounter = currentProjectCounters[project] ?: return
        val state = Persistence.getInstance().persistentState

        // We save this counter regardless of whether it has counts just to mark the end of a session
        currentCounter.end = Instant.now().toEpochMilli()
        state.savedCounters.add(currentCounter)
        state.activeCounters.remove(currentCounter)

        currentProjectCounters.remove(project)
        if (currentProjectCounters.isEmpty()) {
            stateTimer?.cancel()
        }
        // Force an immediate upload
        lastSuccessfulUpload = 0
        uploadCounters()
        return
    }

    inner class TypedHandler : TypedHandlerDelegate() {
        override fun beforeCharTyped(char: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result {
            val projectCounter = currentProjectCounters[project] ?: return Result.CONTINUE
            log.trace("charTyped (${projectCounter.keystrokeCount})")
            projectCounter.keystrokeCount++
            return Result.CONTINUE
        }
    }

    inner class TestStatusHandler : TestStatusListener() {
        override fun testSuiteFinished(abstractTestProxy: AbstractTestProxy?) {}
        private fun countTests(abstractTestProxy: AbstractTestProxy, projectCounter: Counter) {
            if (!abstractTestProxy.isLeaf) {
                for (child in abstractTestProxy.children) {
                    countTests(child, projectCounter)
                }
                return
            }
            val name = abstractTestProxy.name
                    .replace("\\.test$".toRegex(), "")
                    .replace(".", "_")
            if (!(projectCounter.testCounts.containsKey(name))) {
                projectCounter.testCounts[name] = TestCounter()
            }
            val testCounter = projectCounter.testCounts[name] ?: return
            when {
                abstractTestProxy.isPassed -> testCounter.passed++
                abstractTestProxy.isDefect -> testCounter.failed++
                abstractTestProxy.isIgnored -> testCounter.ignored++
                abstractTestProxy.isInterrupted -> testCounter.interrupted++
            }
            projectCounter.totalTestCount++
        }

        override fun testSuiteFinished(abstractTestProxy: AbstractTestProxy?, project: Project) {
            if (abstractTestProxy == null) {
                return
            }
            val projectCounter = currentProjectCounters[project] ?: return
            log.trace("testSuiteFinished")
            countTests(abstractTestProxy, projectCounter)
        }
    }

    override fun caretAdded(caretEvent: CaretEvent) {
        val projectCounter = currentProjectCounters[caretEvent.editor.project] ?: return
        log.trace("caretAdded")
        projectCounter.caretAdded++
        return
    }

    override fun caretRemoved(caretEvent: CaretEvent) {
        val projectCounter = currentProjectCounters[caretEvent.editor.project] ?: return
        log.trace("caretRemoved")
        projectCounter.caretRemoved++
        return
    }

    override fun caretPositionChanged(caretEvent: CaretEvent) {
        val projectCounter = currentProjectCounters[caretEvent.editor.project] ?: return
        log.trace("caretPositionChanged")
        projectCounter.caretPositionChangedCount++
    }

    override fun visibleAreaChanged(visibleAreaEvent: VisibleAreaEvent) {
        val projectCounter = currentProjectCounters[visibleAreaEvent.editor.project] ?: return
        log.trace("visibleAreaChanged")
        projectCounter.visibleAreaChangedCount++
    }

    override fun mousePressed(editorMouseEvent: EditorMouseEvent) {
        val projectCounter = currentProjectCounters[editorMouseEvent.editor.project] ?: return
        log.trace("mousePressed")
        projectCounter.mousePressedCount++
    }

    override fun mouseClicked(editorMouseEvent: EditorMouseEvent) {
        val projectCounter = currentProjectCounters[editorMouseEvent.editor.project] ?: return
        log.trace("mouseActivity")
        projectCounter.mouseActivityCount++
    }

    override fun mouseReleased(editorMouseEvent: EditorMouseEvent) {
        val projectCounter = currentProjectCounters[editorMouseEvent.editor.project] ?: return
        log.trace("mouseActivity")
        projectCounter.mouseActivityCount++
    }

    override fun mouseEntered(editorMouseEvent: EditorMouseEvent) {
        val projectCounter = currentProjectCounters[editorMouseEvent.editor.project] ?: return
        log.trace("mouseActivity")
        projectCounter.mouseActivityCount++
    }

    override fun mouseExited(editorMouseEvent: EditorMouseEvent) {
        val projectCounter = currentProjectCounters[editorMouseEvent.editor.project] ?: return
        log.trace("mouseActivity")
        projectCounter.mouseActivityCount++
    }

    override fun selectionChanged(selectionEvent: SelectionEvent) {
        val projectCounter = currentProjectCounters[selectionEvent.editor.project] ?: return
        log.trace("selectionChanged")
        projectCounter.selectionChangedCount++
    }

    override fun documentChanged(documentEvent: DocumentEvent) {
        log.trace("documentChanged")

        val changedFile = FileDocumentManager.getInstance().getFile(documentEvent.document)
        for ((project, info) in projectConfigurations) {
            if (info.emailLocation == null) {
                continue
            }
            try {
                val emailPath = File(project.basePath.toString()).resolve(File(info.emailLocation)).canonicalPath
                if (changedFile?.canonicalPath.equals(emailPath)) {
                    info.email = documentEvent.document.text.trim()
                    log.debug("Updated email for project " + info.name + ": " + info.email)
                }
            } catch (e: Throwable) {
            }
        }

        val editors = EditorFactory.getInstance().getEditors(documentEvent.document)
        for (editor in editors) {
            val projectCounter = currentProjectCounters[editor.project] ?: continue
            projectCounter.documentChangedCount++
        }
    }

    override fun beforeDocumentChange(event: DocumentEvent) {
        return
    }

    override fun compilationFinished(aborted: Boolean, errors: Int, warnings: Int, compileContext: CompileContext) {
        if (aborted) {
            return
        }
        val projectCounter = currentProjectCounters[compileContext.project] ?: return
        log.trace("compilationFinished")
        projectCounter.compileCount++
        if (errors == 0) {
            projectCounter.successfulCompileCount++
        } else {
            projectCounter.failedCompileCount++
        }
        projectCounter.compilerErrorCount += errors
        projectCounter.compilerWarningCount += warnings
    }

    override fun fileOpened(manager: FileEditorManager, file: VirtualFile) {
        val projectCounter = currentProjectCounters[manager.project] ?: return
        log.trace("fileOpened")
        projectCounter.fileOpenedCount++
    }

    override fun fileClosed(manager: FileEditorManager, file: VirtualFile) {
        val projectCounter = currentProjectCounters[manager.project] ?: return
        log.trace("fileClosed")
        projectCounter.fileClosedCount++
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val projectCounter = currentProjectCounters[event.manager.project] ?: return
        log.trace("fileSelectionChanged")
        projectCounter.fileSelectionChangedCount++
        projectCounter.selectedFile = event.newFile?.path ?: ""
    }
}