package com.github.mrshan23.pytestguard.llm


import com.github.mrshan23.pytestguard.actions.controllers.TestGenerationController
import com.github.mrshan23.pytestguard.bundles.plugin.PluginMessagesBundle
import com.github.mrshan23.pytestguard.data.Report
import com.github.mrshan23.pytestguard.data.TestCase
import com.github.mrshan23.pytestguard.display.PyTestGuardDisplayManager
import com.github.mrshan23.pytestguard.llm.prompt.PromptGenerator
import com.github.mrshan23.pytestguard.psi.PsiHelper
import com.github.mrshan23.pytestguard.settings.PluginSettingsService
import com.github.mrshan23.pytestguard.test.TestAssembler
import com.github.mrshan23.pytestguard.test.TestFramework
import com.github.mrshan23.pytestguard.utils.FileUtils
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager


class Llm(private val project: Project) {

    private var report : Report? = null

    fun generateTestsForMethod(
        psiHelper: PsiHelper,
        caretOffset: Int,
        testFramework: TestFramework,
        testGenerationController: TestGenerationController,
        pyTestGuardDisplayManager: PyTestGuardDisplayManager,
    ) {
        testGenerationController.errorMonitor.clear()
        pyTestGuardDisplayManager.clear()

        ProgressManager.getInstance()
            .run(object : Task.Backgroundable(project, PluginMessagesBundle.get("testGenerationMessage")) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        testGenerationController.indicator = indicator

                        // Get API key from settings
                        val settingsState = project.service<PluginSettingsService>().state
                        val apiKey = settingsState.apiKey

                        // TODO: add notification here
                        if (apiKey.isEmpty()) {
                            testGenerationController.errorMonitor.notifyErrorOccurrence()
                            return
                        }

                        val manager = GeminiRequestManager(apiKey)

                        val promptGenerator = PromptGenerator(psiHelper, caretOffset, testFramework)
                        val testAssembler = TestAssembler(indicator)

                        val systemPrompt = promptGenerator.getSystemPrompt()
                        val userPrompt = promptGenerator.generateUserPrompt()

                        val response = manager.sendRequest(
                            systemPrompt = systemPrompt,
                            userPrompt = userPrompt,
                            indicator = indicator,
                            testAssembler = testAssembler,
                        )

                        // TODO: Check if needed
                        if (response.isFailure) {
                            testGenerationController.errorMonitor.notifyErrorOccurrence()
                            return
                        }

                        report = Report()

                        val generatedTestSuite = testAssembler.assembleTestSuite(testFramework)
                        generatedTestSuite?.let {
                            addTestCasesToReport(report!!, it)
                            report!!.testFramework = testFramework
                        } ?: run {
                            //TODO: add error message or notification or something about no tests generated
                            testGenerationController.errorMonitor.notifyErrorOccurrence()
                            return
                        }

                        indicator.stop()
                    } catch (e: RuntimeException) {
                        e.printStackTrace()
                    }
                }

                override fun onFinished() {
                    super.onFinished()
                    testGenerationController.finished()

                    if (testGenerationController.errorMonitor.hasErrorOccurred() || report == null) return

                    // If there are existing results from previous test generations, remove them and create a new one
                    FileUtils.removeDirectory(FileUtils.getPyTestGuardResultsDirectoryPath(project))
                    FileUtils.createHiddenPyTestGuardResultsDirectory(project)

                    pyTestGuardDisplayManager.display(
                        report!!,
                        testFramework,
                        project,
                    )

                    VirtualFileManager.getInstance().syncRefresh()
                }

            })
    }

    /**
     * Records the generated test cases in the given report.
     *
     * @param report The report object to store the test cases in.
     * @param testCases The list of test cases generated by LLM.
     */
    private fun addTestCasesToReport(report: Report, testCases: List<TestCase>) {
        for ((index, test) in testCases.withIndex()) {
            test.id = index
            test.uniqueTestName = FileUtils.getUniqueTestCaseName(test.testName)
            report.testCaseList[index] = test
        }
    }

}
