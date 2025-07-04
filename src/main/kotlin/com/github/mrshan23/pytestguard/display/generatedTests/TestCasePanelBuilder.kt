package com.github.mrshan23.pytestguard.display.generatedTests

import com.github.mrshan23.pytestguard.bundles.plugin.PluginLabelsBundle
import com.github.mrshan23.pytestguard.bundles.plugin.PluginMessagesBundle
import com.github.mrshan23.pytestguard.data.Report
import com.github.mrshan23.pytestguard.data.TestCase
import com.github.mrshan23.pytestguard.display.CoverageVisualisationTabBuilder
import com.github.mrshan23.pytestguard.display.PyTestGuardIcons
import com.github.mrshan23.pytestguard.display.custom.CustomProgressIndicator
import com.github.mrshan23.pytestguard.display.editor.CustomLanguageTextField
import com.github.mrshan23.pytestguard.display.editor.TestCaseDocumentCreator
import com.github.mrshan23.pytestguard.settings.PluginSettingsService
import com.github.mrshan23.pytestguard.test.TestFramework
import com.github.mrshan23.pytestguard.test.TestProcessor
import com.github.mrshan23.pytestguard.test.data.ExecutionResult
import com.github.mrshan23.pytestguard.utils.ErrorMessageManager
import com.github.mrshan23.pytestguard.utils.IconButtonCreator
import com.github.mrshan23.pytestguard.utils.ReportUpdater
import com.intellij.lang.Language
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.LanguageTextField
import com.intellij.ui.LanguageTextField.SimpleDocumentCreator
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.jetbrains.python.PythonLanguage
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.border.MatteBorder

class TestCasePanelBuilder(
    private val project: Project,
    private val testCase: TestCase,
    private val report: Report,
    private val testFramework: TestFramework,
    private val generatedTestsTabData: GeneratedTestsTabData,
    private val coverageVisualisationTabBuilder: CoverageVisualisationTabBuilder,
) {

    private val panel = JPanel()


    private val copyButton =
        IconButtonCreator.getButton(PyTestGuardIcons.copy, PluginLabelsBundle.get("copyTip"))

    private val removeButton =
        IconButtonCreator.getButton(PyTestGuardIcons.remove, PluginLabelsBundle.get("removeTip"))

    private val runTestCaseButton =
        IconButtonCreator.getButton(PyTestGuardIcons.runTestCase, PluginLabelsBundle.get("runTestTip"))

    private val errorLabel =
        IconButtonCreator.getButton(PyTestGuardIcons.showError, null)

    private val languageId: String = PythonLanguage.INSTANCE.id

    // Add an editor to modify the test source code
    private val languageTextField: LanguageTextField

    private val languageTextFieldScrollPane: JBScrollPane

    init {
        val settingsState = project.service<PluginSettingsService>().state
        val enableSimpleMode = settingsState.enableSimpleMode

        if (enableSimpleMode) {
            languageTextField = LanguageTextField(
                Language.findLanguageByID(languageId),
                project,
                testCase.testCode,
                SimpleDocumentCreator(),
                false,
            )
        } else {
            languageTextField = CustomLanguageTextField(
                Language.findLanguageByID(languageId),
                project,
                testCase.testCode,
                TestCaseDocumentCreator(testCase),
                false,
            )
        }

        languageTextFieldScrollPane = JBScrollPane(
            languageTextField,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS,
        )
    }

    /**
     * Retrieves the upper panel for the GUI.
     *
     * This panel contains various components such as buttons, labels, and checkboxes. It is used to display information and
     * perform actions related to the GUI.
     *
     * @return The JPanel object representing the upper panel.
     */
    fun getUpperPanel(): JPanel {
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)
        panel.add(Box.createHorizontalGlue())
        panel.add(runTestCaseButton)
        panel.add(errorLabel)
        panel.add(copyButton)
        panel.add(removeButton)
        panel.add(Box.createRigidArea(Dimension(12, 0)))

        errorLabel.isVisible = false
        errorLabel.addActionListener {
            copy(testCase.executionMessage,
                PluginMessagesBundle.get("errorMessageCopied"))
        }

        runTestCaseButton.disabledIcon = PyTestGuardIcons.runTestCaseDisabled
        runTestCaseButton.addActionListener {
            runTestCase()
        }

        removeButton.addActionListener { remove() }

        copyButton.addActionListener {
            copy(generatedTestsTabData.testCaseIdToEditorTextField[testCase.id]!!.document.text,
                PluginMessagesBundle.get("testCaseCopied"))
        }

        return panel
    }

    /**
     * Retrieves the middle panel of the application.
     * This method sets the border of the languageTextField and
     * adds it to the middlePanel with appropriate spacing.
     */
    fun getMiddlePanel(): JPanel {

        // Set border
        languageTextField.border = JBUI.Borders.empty()

        val panel = JPanel()

        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.add(Box.createRigidArea(Dimension(0, 5)))
        panel.add(languageTextFieldScrollPane)
        panel.add(Box.createRigidArea(Dimension(0, 5)))

        addLanguageTextFieldListener(languageTextField)

        return panel
    }

    /**
     * Adds a document listener to the provided LanguageTextField.
     * The listener triggers the updateUI() method whenever the document of the LanguageTextField changes.
     *
     * @param languageTextField the LanguageTextField to add the listener to
     */
    private fun addLanguageTextFieldListener(languageTextField: LanguageTextField) {
        languageTextField.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                update()
            }
        })
    }

    /**
     * Updates the user interface based on the provided code.
     */
    private fun update() {
        updateTestCaseInformation()

        languageTextField.border = JBUI.Borders.empty()
        runTestCaseButton.isEnabled = true
        errorLabel.isVisible = false

        ReportUpdater.updateTestCase(report, testCase, coverageVisualisationTabBuilder)
        GenerateTestsTabHelper.update(generatedTestsTabData)
    }

    private fun remove() {
        // Remove the test case from the cache
        GenerateTestsTabHelper.removeTestCase(testCase.id!!, generatedTestsTabData)

        ReportUpdater.removeTestCase(report, testCase, coverageVisualisationTabBuilder)

        GenerateTestsTabHelper.update(generatedTestsTabData)
    }

    private fun copy(content: String, notificationMessage: String) {
        val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(
            StringSelection(
                content
            ),
            null,
        )
        NotificationGroupManager.getInstance()
            .getNotificationGroup("UserInterface")
            .createNotification(
                "",
                notificationMessage,
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    private fun runTestCase() {
        runTestCaseButton.isEnabled = false

        ProgressManager.getInstance()
            .run(object : Task.Backgroundable(project, PluginMessagesBundle.get("executingTests")) {
                override fun run(indicator: ProgressIndicator) {
                    val customProgressIndicator = CustomProgressIndicator(indicator)
                    customProgressIndicator.setText(PluginMessagesBundle.get("runningTest").format(testCase.testName))

                    // Save file before running the test
                    saveDocument()

                    val testProcess = TestProcessor(project, customProgressIndicator)

                    testProcess.runTest(testCase, testFramework)?.let {
                        SwingUtilities.invokeLater {
                            updateAfterExecutingTestCase(it)
                        }
                    }

                    customProgressIndicator.stop()
                }
            })

    }


    private fun saveDocument() {
        val project = languageTextField.project
        val document = languageTextField.document

        val psiFile = ReadAction.compute<PsiFile, Throwable> {
            PsiDocumentManager.getInstance(project).getPsiFile(document)
        }

        if (psiFile != null) {
            ApplicationManager.getApplication().invokeLater {
                ApplicationManager.getApplication().runWriteAction {
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                    FileDocumentManager.getInstance().saveDocument(document)
                }
            }
        }
    }

    private fun updateAfterExecutingTestCase(executionResult: ExecutionResult) {
        val size = 3
        if (executionResult.isSuccessful()) {
            languageTextField.border = MatteBorder(size, size, size, size, JBColor.GREEN)
        } else {
            languageTextField.border = MatteBorder(size, size, size, size, JBColor.RED)
            errorLabel.isVisible = true
            errorLabel.toolTipText = ErrorMessageManager.simplify(executionResult.executionMessage, testCase, testFramework)
            testCase.executionMessage = executionResult.executionMessage
        }

        coverageVisualisationTabBuilder.show(executionResult)
    }

    /**
     * Updates the current test case with the specified test name and test code.
     */
    private fun updateTestCaseInformation() {
        testCase.testCode = languageTextField.document.text
    }

    /**
     * Retrieves the editor text field from the current UI context.
     *
     * @return the editor text field
     */
    fun getEditorTextField(): EditorTextField = languageTextField
}
