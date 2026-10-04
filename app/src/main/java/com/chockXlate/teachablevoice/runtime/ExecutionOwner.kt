package com.chockXlate.teachablevoice.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Owns the execution CoroutineScope. Ensures there is exactly ONE managed scope
 * that survives UI lifecycle (MainActivity, MainDashboardScreen recomposition, HOME dispatch)
 * without leaking multiple unmanaged scopes on repeated clicks.
 * Pattern derived from 'android-agent' best practices.
 */
object ExecutionOwner {
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
}
