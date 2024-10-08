package edu.illinois.cs.cs125.intellijlogger

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

fun Project.getButtonAction(): RunnerAndConfigurationSettings? {
    val projectConfiguration = getConfiguration(this) ?: return null
    val pattern = projectConfiguration.buttonAction ?: run {
        log.trace("no buttonAction for project")
        return null
    }
    val regex = Regex(pattern)
    return RunManager.getInstance(this).allSettings.find { runConfiguration ->
        runConfiguration.name.trim().matches(regex)
    } ?: run {
        log.trace("no run configuration matched $pattern")
        return null
    }
}

class ButtonAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(anActionEvent: AnActionEvent) {
        log.trace("actionPerformed")
        val project = anActionEvent.project ?: run {
            log.warn("no project for action")
            return
        }
        val buttonAction = project.getButtonAction() ?: return
        getCounters(project)?.let { counters ->
            ProgramRunnerUtil.executeConfiguration(buttonAction, DefaultRunExecutor.getRunExecutorInstance())
            counters.gradingCount++
        }
    }

    override fun update(anActionEvent: AnActionEvent) {
        log.trace("update")
        val project = anActionEvent.project ?: run {
            log.debug("no project")
            anActionEvent.presentation.isVisible = false
            return
        }
        anActionEvent.presentation.isVisible = project.getButtonAction() != null
    }
}
