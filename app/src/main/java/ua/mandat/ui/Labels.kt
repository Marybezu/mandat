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

fun ua.mandat.data.TeamRole.label(): Int = when (this) {
    ua.mandat.data.TeamRole.ASSISTANT -> R.string.team_ASSISTANT
    ua.mandat.data.TeamRole.VOLUNTEER_ASSISTANT -> R.string.team_VOLUNTEER_ASSISTANT
    ua.mandat.data.TeamRole.OFFICE_HEAD -> R.string.team_OFFICE_HEAD
    ua.mandat.data.TeamRole.INTERN -> R.string.team_INTERN
    ua.mandat.data.TeamRole.SECRETARIAT -> R.string.team_SECRETARIAT
    ua.mandat.data.TeamRole.LAWYER -> R.string.team_LAWYER
    ua.mandat.data.TeamRole.ADVISOR -> R.string.team_ADVISOR
    ua.mandat.data.TeamRole.COMMS -> R.string.team_COMMS
    ua.mandat.data.TeamRole.ANALYST -> R.string.team_ANALYST
    ua.mandat.data.TeamRole.OTHER -> R.string.team_OTHER
}

fun ua.mandat.data.PersonCategory.label(): Int = when (this) {
    ua.mandat.data.PersonCategory.COLLEAGUE_MP -> R.string.cat_COLLEAGUE_MP
    ua.mandat.data.PersonCategory.OFFICIAL -> R.string.cat_OFFICIAL
    ua.mandat.data.PersonCategory.EXPERT -> R.string.cat_EXPERT
    ua.mandat.data.PersonCategory.JOURNALIST -> R.string.cat_JOURNALIST
    ua.mandat.data.PersonCategory.NGO -> R.string.cat_NGO
    ua.mandat.data.PersonCategory.BUSINESS -> R.string.cat_BUSINESS
    ua.mandat.data.PersonCategory.INTERNATIONAL -> R.string.cat_INTERNATIONAL
    ua.mandat.data.PersonCategory.VOTER -> R.string.cat_VOTER
    ua.mandat.data.PersonCategory.OTHER -> R.string.cat_OTHER
}

fun ua.mandat.data.Stance.label(): Int = when (this) {
    ua.mandat.data.Stance.ALLY -> R.string.stance_ALLY
    ua.mandat.data.Stance.UNDECIDED -> R.string.stance_UNDECIDED
    ua.mandat.data.Stance.OPPONENT -> R.string.stance_OPPONENT
    ua.mandat.data.Stance.NEUTRAL -> R.string.stance_NEUTRAL
}

fun ua.mandat.data.TaskStatus.delegationLabel(): Int = when (this) {
    ua.mandat.data.TaskStatus.OPEN -> R.string.d_OPEN
    ua.mandat.data.TaskStatus.DONE -> R.string.d_DONE
    ua.mandat.data.TaskStatus.FAILED, ua.mandat.data.TaskStatus.CANCELLED -> R.string.d_CANCELLED
}

fun ua.mandat.data.TaskStatus.promiseLabel(): Int = when (this) {
    ua.mandat.data.TaskStatus.OPEN -> R.string.p_OPEN
    ua.mandat.data.TaskStatus.DONE -> R.string.p_DONE
    ua.mandat.data.TaskStatus.FAILED -> R.string.p_FAILED
    ua.mandat.data.TaskStatus.CANCELLED -> R.string.p_CANCELLED
}

/** Statuses offered for delegations and deadlines (no "did not work out"). */
val TASK_STATUSES = listOf(ua.mandat.data.TaskStatus.OPEN, ua.mandat.data.TaskStatus.DONE, ua.mandat.data.TaskStatus.CANCELLED)

fun ua.mandat.data.Freedom.label(): Int = when (this) {
    ua.mandat.data.Freedom.TELL -> R.string.freedom_TELL
    ua.mandat.data.Freedom.OPTIONS -> R.string.freedom_OPTIONS
    ua.mandat.data.Freedom.DECIDE_INFORM -> R.string.freedom_DECIDE_INFORM
    ua.mandat.data.Freedom.FULL -> R.string.freedom_FULL
}

fun ua.mandat.data.Freedom.hint(): Int = when (this) {
    ua.mandat.data.Freedom.TELL -> R.string.freedom_TELL_hint
    ua.mandat.data.Freedom.OPTIONS -> R.string.freedom_OPTIONS_hint
    ua.mandat.data.Freedom.DECIDE_INFORM -> R.string.freedom_DECIDE_INFORM_hint
    ua.mandat.data.Freedom.FULL -> R.string.freedom_FULL_hint
}

fun ua.mandat.data.Raci.label(): Int = when (this) {
    ua.mandat.data.Raci.LEAD -> R.string.raci_LEAD
    ua.mandat.data.Raci.APPROVE -> R.string.raci_APPROVE
    ua.mandat.data.Raci.CONSULT -> R.string.raci_CONSULT
    ua.mandat.data.Raci.INFORM -> R.string.raci_INFORM
}

fun influenceLabel(level: Int?): Int = when (level) {
    1 -> R.string.influence_1
    2 -> R.string.influence_2
    3 -> R.string.influence_3
    else -> R.string.not_set
}

fun ua.mandat.data.Publicity.label(): Int = when (this) {
    ua.mandat.data.Publicity.PUBLIC -> R.string.publicity_public
    ua.mandat.data.Publicity.CIRCLE -> R.string.publicity_circle
    ua.mandat.data.Publicity.PERSONAL -> R.string.publicity_personal
    ua.mandat.data.Publicity.SELF -> R.string.publicity_self
}

fun ua.mandat.data.PromiseOrigin.label(): Int = when (this) {
    ua.mandat.data.PromiseOrigin.CHOSEN -> R.string.origin_chosen
    ua.mandat.data.PromiseOrigin.FORCED -> R.string.origin_forced
    ua.mandat.data.PromiseOrigin.ATTRIBUTED -> R.string.origin_attributed
    ua.mandat.data.PromiseOrigin.DUG_UP -> R.string.origin_dug_up
}

fun ua.mandat.data.PromiseOrigin.hint(): Int = when (this) {
    ua.mandat.data.PromiseOrigin.CHOSEN -> R.string.origin_chosen_hint
    ua.mandat.data.PromiseOrigin.FORCED -> R.string.origin_forced_hint
    ua.mandat.data.PromiseOrigin.ATTRIBUTED -> R.string.origin_attributed_hint
    ua.mandat.data.PromiseOrigin.DUG_UP -> R.string.origin_dug_up_hint
}

fun riskLabel(level: Int): Int = when (level) {
    1 -> R.string.risk_low
    2 -> R.string.risk_mid
    else -> R.string.risk_high
}

fun ua.mandat.data.InfoKind.label(): Int = when (this) {
    ua.mandat.data.InfoKind.DISCUSSION -> R.string.info_discussion
    ua.mandat.data.InfoKind.CRITIQUE -> R.string.info_critique
    ua.mandat.data.InfoKind.ATTACK -> R.string.info_attack
    ua.mandat.data.InfoKind.CORRECTION -> R.string.info_correction
}

fun ua.mandat.data.AttackStage.label(): Int = when (this) {
    ua.mandat.data.AttackStage.SPOTTED -> R.string.stage_spotted
    ua.mandat.data.AttackStage.ASSESSED -> R.string.stage_assessed
    ua.mandat.data.AttackStage.DECIDED -> R.string.stage_decided
    ua.mandat.data.AttackStage.ACTED -> R.string.stage_acted
    ua.mandat.data.AttackStage.RESULT -> R.string.stage_result
}

fun ua.mandat.data.AttackDecision.label(): Int = when (this) {
    ua.mandat.data.AttackDecision.IGNORE -> R.string.decision_ignore
    ua.mandat.data.AttackDecision.ANSWER -> R.string.decision_answer
    ua.mandat.data.AttackDecision.REFUTE -> R.string.decision_refute
    ua.mandat.data.AttackDecision.ALLIES -> R.string.decision_allies
    ua.mandat.data.AttackDecision.LEGAL -> R.string.decision_legal
}

fun ua.mandat.data.IntegrityCheck.label(): Int = when (this) {
    ua.mandat.data.IntegrityCheck.SOURCE -> R.string.check_source
    ua.mandat.data.IntegrityCheck.ACTIONS -> R.string.check_actions
    ua.mandat.data.IntegrityCheck.FACTS -> R.string.check_facts
    ua.mandat.data.IntegrityCheck.SIGN -> R.string.check_sign
    ua.mandat.data.IntegrityCheck.PROPORTION -> R.string.check_proportion
}

fun ua.mandat.data.AllianceStatus.label(): Int = when (this) {
    ua.mandat.data.AllianceStatus.ACTIVE -> R.string.alliance_active
    ua.mandat.data.AllianceStatus.ENDED -> R.string.alliance_ended
    ua.mandat.data.AllianceStatus.BROKEN -> R.string.alliance_broken
}
