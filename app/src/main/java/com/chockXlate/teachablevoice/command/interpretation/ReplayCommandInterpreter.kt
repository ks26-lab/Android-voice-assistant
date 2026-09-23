package com.chockXlate.teachablevoice.command.interpretation

import com.chockXlate.teachablevoice.contract.runtime.ExecutionRequest
import com.chockXlate.teachablevoice.contract.workflow.Workflow

/**
 * Interprets natural language voice replay commands, matches skills, extracts slot values,
 * and constructs an ExecutionRequest.
 */
interface ReplayCommandInterpreter {
    fun interpretCommand(
        userCommand: String,
        availableWorkflows: List<Workflow>
    ): ExecutionRequest?
}
