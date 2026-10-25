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

/**
 * A body of the Rada (or a line of work) and the positions one can hold in it. A role is built
 * as "position — body", optionally with a name or topic. Only generic names are in the code;
 * the owner's actual choices live in the encrypted database.
 */
data class RoleBody(val name: Int, val positions: List<Int>)

val ROLE_BODIES = listOf(
    RoleBody(R.string.body_rada, listOf(R.string.pos_rada_chair, R.string.pos_rada_first_deputy, R.string.pos_rada_deputy)),
    RoleBody(R.string.body_faction, listOf(R.string.pos_chair, R.string.pos_first_deputy, R.string.pos_deputy, R.string.pos_member)),
    RoleBody(R.string.body_independent, listOf()),
    RoleBody(R.string.body_committee, listOf(R.string.pos_chair, R.string.pos_first_deputy, R.string.pos_deputy, R.string.pos_secretary, R.string.pos_sub_chair, R.string.pos_member)),
    RoleBody(R.string.body_tik, listOf(R.string.pos_chair, R.string.pos_deputy, R.string.pos_secretary, R.string.pos_member)),
    RoleBody(R.string.body_tsk, listOf(R.string.pos_chair, R.string.pos_deputy, R.string.pos_secretary, R.string.pos_member)),
    RoleBody(R.string.body_stik, listOf(R.string.pos_chair, R.string.pos_deputy, R.string.pos_secretary, R.string.pos_member)),
    RoleBody(R.string.body_counting, listOf(R.string.pos_chair, R.string.pos_member)),
    RoleBody(R.string.body_delegation, listOf(R.string.pos_chair, R.string.pos_deputy, R.string.pos_member)),
    RoleBody(R.string.body_eu_committee, listOf(R.string.pos_cochair, R.string.pos_member)),
    RoleBody(R.string.body_assembly, listOf(R.string.pos_cochair, R.string.pos_member)),
    RoleBody(R.string.body_friendship, listOf(R.string.pos_head_cochair, R.string.pos_deputy_head, R.string.pos_secretary, R.string.pos_member)),
    RoleBody(R.string.body_interfaction, listOf(R.string.pos_cochair, R.string.pos_member)),
    RoleBody(R.string.body_working_group, listOf(R.string.pos_head, R.string.pos_member)),
    RoleBody(R.string.body_district, listOf()),
    RoleBody(R.string.body_region, listOf()),
    RoleBody(R.string.body_other_body, listOf(R.string.pos_representative, R.string.pos_member)),
)
