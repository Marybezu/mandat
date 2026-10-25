package ua.mandat.ui

import ua.mandat.R
import ua.mandat.data.Attention
import ua.mandat.data.MatterStatus
import ua.mandat.data.MatterType

fun MatterType.label(): Int = when (this) {
    MatterType.CAMPAIGN -> R.string.type_CAMPAIGN
    MatterType.BILL -> R.string.type_BILL
    MatterType.INVESTIGATION -> R.string.type_INVESTIGATION
    MatterType.COURT -> R.string.type_COURT
    MatterType.DISTRICT -> R.string.type_DISTRICT
    MatterType.INTERNATIONAL -> R.string.type_INTERNATIONAL
    MatterType.WORKING_GROUP -> R.string.type_WORKING_GROUP
    MatterType.INQUIRY -> R.string.type_INQUIRY
    MatterType.PUBLIC_COMMS -> R.string.type_PUBLIC_COMMS
    MatterType.OTHER -> R.string.type_OTHER
}

fun Attention.label(): Int = when (this) {
    Attention.FOCUS -> R.string.attention_FOCUS
    Attention.BACKGROUND -> R.string.attention_BACKGROUND
    Attention.SLEEPING -> R.string.attention_SLEEPING
}

fun Attention.hint(): Int = when (this) {
    Attention.FOCUS -> R.string.attention_hint_FOCUS
    Attention.BACKGROUND -> R.string.attention_hint_BACKGROUND
    Attention.SLEEPING -> R.string.attention_hint_SLEEPING
}

fun MatterStatus.label(): Int = when (this) {
    MatterStatus.ACTIVE -> R.string.status_ACTIVE
    MatterStatus.DONE -> R.string.status_DONE
    MatterStatus.DROPPED -> R.string.status_DROPPED
}

/** Generic role names offered at first start; the owner picks any and can add their own. */
val ROLE_SUGGESTIONS = listOf(
    R.string.role_suggest_committee_member,
    R.string.role_suggest_committee_chair,
    R.string.role_suggest_subcommittee_chair,
    R.string.role_suggest_commission_chair,
    R.string.role_suggest_commission_member,
    R.string.role_suggest_district,
    R.string.role_suggest_faction,
    R.string.role_suggest_delegation,
    R.string.role_suggest_working_group,
)
