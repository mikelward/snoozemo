package app.snoozemo.dnd

import android.content.Context

/**
 * Remembers the id of Snoozemo's one long-lived zen rule (SPEC.md §5.3).
 *
 * An interface so the controller is testable without Android, and because the
 * storage is likely to move to DataStore alongside the rest of the settings —
 * the id is small, read on every start, and read on the arm path, which is
 * exactly the kind of thing that must already be in memory before a tile tap.
 */
interface ZenRuleIdStore {
    fun ruleId(): String?
    fun setRuleId(id: String)
    fun clear()
}

/**
 * SharedPreferences rather than DataStore, deliberately, for this one value:
 * the arm path reads it and must not wait on a coroutine or a disk read, and
 * `getString` on an already-loaded preferences file is a memory hit. The file is
 * loaded at construction, which happens at startup, not at tap time.
 */
class PrefsZenRuleIdStore(context: Context) : ZenRuleIdStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun ruleId(): String? = prefs.getString(KEY_RULE_ID, null)

    override fun setRuleId(id: String) {
        prefs.edit().putString(KEY_RULE_ID, id).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_RULE_ID).apply()
    }

    private companion object {
        const val FILE_NAME = "zen_rule"
        const val KEY_RULE_ID = "rule_id"
    }
}
