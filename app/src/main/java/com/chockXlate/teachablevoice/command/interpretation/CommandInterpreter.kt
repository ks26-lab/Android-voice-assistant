package com.chockXlate.teachablevoice.command.interpretation

/** Replay facade; teaching uses the same canonical language policy. */
object CommandInterpreter {
    fun normalizeCommand(rawCommand: String) = SemanticCommandPolicy.normalizeCommand(rawCommand)
    fun understandCommand(rawCommand: String) = SemanticCommandPolicy.understandCommand(rawCommand)
}
