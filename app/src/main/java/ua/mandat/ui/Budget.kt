@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ua.mandat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.mandat.R
import ua.mandat.data.AidRecord
import ua.mandat.data.CharityKind
import ua.mandat.data.ExpenseCategory
import ua.mandat.data.IncomeCategory
import ua.mandat.data.MandatDb
import ua.mandat.data.MoneyEntry
import ua.mandat.data.MoneyKind
import ua.mandat.data.Period
import ua.mandat.data.SETTING_EXPENSE_CATEGORIES
import ua.mandat.data.SETTING_INCOME_CATEGORIES
import ua.mandat.data.SETTING_LIMIT_PREFIX
import ua.mandat.data.Setting
import ua.mandat.data.decodeCategories
import ua.mandat.data.formatAmount
import ua.mandat.data.parseAmount
import ua.mandat.data.spanOf
import ua.mandat.data.summarize
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Whole hryvnias for a chart, never past what an Int holds. */
private fun hInt(minor: Long): Int = (minor / 100).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

/** "12 500 ₴" from kopiyky. */
fun hryvnias(minor: Long) = formatAmount(minor) + " ₴"

@Composable
fun categoryName(key: String): String = when (key) {
    IncomeCategory.SALARY.name -> stringResource(R.string.in_salary)
    IncomeCategory.ACTIVITY_FUNDS.name -> stringResource(R.string.in_activity)
    IncomeCategory.REFUNDS.name -> stringResource(R.string.in_refunds)
    IncomeCategory.ROYALTIES.name -> stringResource(R.string.in_royalties)
    IncomeCategory.PRACTICE.name -> stringResource(R.string.in_practice)
    IncomeCategory.TEACHING.name -> stringResource(R.string.in_teaching)
    IncomeCategory.OTHER_IN.name -> stringResource(R.string.in_other)
    ExpenseCategory.HOME.name -> stringResource(R.string.out_home)
    ExpenseCategory.OFFICE.name -> stringResource(R.string.out_office)
    ExpenseCategory.CHARITY.name -> stringResource(R.string.out_charity)
    ExpenseCategory.EXPERTS.name -> stringResource(R.string.out_experts)
    ExpenseCategory.TRIPS.name -> stringResource(R.string.out_trips)
    ExpenseCategory.PHONE.name -> stringResource(R.string.out_phone)
    ExpenseCategory.TRANSPORT.name -> stringResource(R.string.out_transport)
    ExpenseCategory.OTHER_OUT.name -> stringResource(R.string.out_other)
    else -> key // the owner's own category
}

fun CharityKind.label(): Int = when (this) {
    CharityKind.ARMY -> R.string.charity_army
    CharityKind.FUNDS -> R.string.charity_funds
    CharityKind.CAMPAIGNS -> R.string.charity_campaigns
    CharityKind.DIRECT -> R.string.charity_direct
    CharityKind.OTHER -> R.string.charity_other
}

private fun categoryColor(i: Int): Color = SeriesColors[i % SeriesColors.size]

/**
 * The budget: a month or a year at a glance (in, out, what is left, given personally), where the
 * money goes, the limits, twelve months of trend, charity, and the entries. Organised aid is
 * shown apart, never mixed into the personal numbers.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BudgetScreen(db: MandatDb, session: Session) {
    BackHandler { session.showBudget = false }
    val entries by remember(db) { db.money().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val aid by remember(db) { db.money().observeAid().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val settings by remember(db) { db.settings().observeBudget() }.collectAsStateWithLifecycle(emptyList())
    val titles by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val titleOf = titles.associate { it.id to it.title }
    val today = Dates.today()
    var year by rememberSaveable { mutableStateOf(false) }
    var back by rememberSaveable { mutableStateOf(0) }
    var catPick by rememberSaveable { mutableStateOf<String?>(null) }
    var kindPick by rememberSaveable { mutableStateOf<String?>(null) }
    var awaitingOnly by rememberSaveable { mutableStateOf(false) }
    var charityPick by rememberSaveable { mutableStateOf<String?>(null) }
    var editLimits by remember { mutableStateOf(false) }
    var editCategories by remember { mutableStateOf(false) }

    val span = spanOf(if (year) Period.YEAR else Period.MONTH, today, back)
    val sum = summarize(entries, span)
    val inSpan = entries.filter { it.day in span }
    val expenses = inSpan.filter { it.kind == MoneyKind.EXPENSE.name }
    val incomes = inSpan.filter { it.kind == MoneyKind.INCOME.name }
    val limits = settings.filter { it.key.startsWith(SETTING_LIMIT_PREFIX) }
        .associate { it.key.removePrefix(SETTING_LIMIT_PREFIX) to (it.value.toLongOrNull() ?: 0L) }.filterValues { it > 0 }
    val shown = (if (awaitingOnly) entries.filter { it.awaitingRefund && !it.refunded } else inSpan)
        .filter { (catPick == null || it.category == catPick) && (kindPick == null || it.kind == kindPick) }
        .filter { charityPick == null || (it.category == ExpenseCategory.CHARITY.name && (it.charityKind ?: "NONE") == charityPick) }
    val aidInSpan = aid.filter { it.day in span }
    val title = if (year) LocalDate.ofEpochDay(span.from).year.toString()
    else LocalDate.ofEpochDay(span.from).format(DateTimeFormatter.ofPattern("LLLL yyyy")).replaceFirstChar { it.uppercase() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.budget_title)) },
                navigationIcon = {
                    IconButton(onClick = { session.showBudget = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = Space.screen, end = Space.screen, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(Space.gap),
        ) {
            item(key = "add") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { session.moneyDraft = newMoney(MoneyKind.EXPENSE) }, modifier = Modifier.weight(1f)) { Text("− " + stringResource(R.string.money_expense)) }
                    OutlinedButton(onClick = { session.moneyDraft = newMoney(MoneyKind.INCOME) }, modifier = Modifier.weight(1f)) { Text("+ " + stringResource(R.string.money_income)) }
                }
            }
            item(key = "period") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QuickChip(stringResource(R.string.cal_month), !year) { year = false; back = 0 }
                    Spacer(Modifier.width(6.dp))
                    QuickChip(stringResource(R.string.dash_year), year) { year = true; back = 0 }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { back++ }) { Text("‹", style = MaterialTheme.typography.titleLarge) }
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { if (back > 0) back-- }, enabled = back > 0) { Text("›", style = MaterialTheme.typography.titleLarge) }
                }
            }
            item(key = "tiles") {
                MCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                        MoneyTile(sum.income, stringResource(R.string.money_in), ChartColors.teal) { kindPick = MoneyKind.INCOME.name; catPick = null; awaitingOnly = false }
                        MoneyTile(sum.expense, stringResource(R.string.money_out), ChartColors.coral) { kindPick = MoneyKind.EXPENSE.name; catPick = null; awaitingOnly = false }
                    }
                    Spacer(Modifier.height(Space.gap))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                        MoneyTile(sum.balance, stringResource(R.string.money_left), if (sum.balance >= 0) ChartColors.blue else ChartColors.coral)
                        MoneyTile(sum.charity, stringResource(R.string.money_given), ChartColors.amber) { kindPick = null; catPick = ExpenseCategory.CHARITY.name; awaitingOnly = false }
                    }
                    if (sum.awaiting > 0) {
                        Spacer(Modifier.height(Space.gap))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (awaitingOnly) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { awaitingOnly = !awaitingOnly }.padding(10.dp),
                        ) {
                            Text(stringResource(R.string.money_awaiting, hryvnias(sum.awaiting)), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            Text("→", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (expenses.isNotEmpty()) item(key = "where") {
                SectionCard(Sym.layers, stringResource(R.string.money_where)) {
                    Spacer(Modifier.height(4.dp))
                    val byCat = expenses.groupBy { it.category }.mapValues { (_, l) -> l.sumOf { it.uah } }.entries.sortedByDescending { it.value }
                    RankedBars(
                        byCat.mapIndexed { i, (k, v) -> Slice(k, categoryName(k), hInt(v), categoryColor(i)) },
                        center = hryvnias(sum.expense), centerLabel = "", selected = catPick, onPick = { catPick = it },
                    )
                }
            }
            if (!year) item(key = "limits") {
                SectionCard(Sym.target, stringResource(R.string.money_limits)) {
                    if (limits.isEmpty()) Text(stringResource(R.string.money_limits_none), style = MaterialTheme.typography.bodySmall, color = Muted)
                    limits.forEach { (cat, limit) ->
                        val spent = expenses.filter { it.category == cat }.sumOf { it.uah }
                        LimitRow(categoryName(cat), spent, limit) { catPick = cat; kindPick = MoneyKind.EXPENSE.name; charityPick = null; awaitingOnly = false }
                    }
                    TextButton(onClick = { editLimits = true }) { Text(stringResource(R.string.money_limits_edit)) }
                }
            }
            if (incomes.isNotEmpty()) item(key = "income") {
                SectionCard(Sym.checkCircle, stringResource(R.string.money_from)) {
                    Spacer(Modifier.height(4.dp))
                    val byCat = incomes.groupBy { it.category }.mapValues { (_, l) -> l.sumOf { it.uah } }.entries.sortedByDescending { it.value }
                    StackBar(byCat.mapIndexed { i, (k, v) -> Slice(k, categoryName(k), hInt(v), categoryColor(i + 3)) },
                        selected = catPick, onPick = { catPick = it })
                }
            }
            item(key = "trend") {
                SectionCard(Sym.calendar, stringResource(R.string.money_trend)) {
                    Spacer(Modifier.height(6.dp))
                    val months = (11 downTo 0).map { spanOf(Period.MONTH, today, it) }
                    MonthBars(months.map { m -> summarize(entries, m) }.map { it.income to it.expense },
                        months.map { LocalDate.ofEpochDay(it.from).format(DateTimeFormatter.ofPattern("LLLLL")) }) { i -> year = false; back = 11 - i }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                        LegendDot(ChartColors.teal, stringResource(R.string.money_in))
                        LegendDot(ChartColors.coral, stringResource(R.string.money_out))
                    }
                }
            }
            val charity = expenses.filter { it.category == ExpenseCategory.CHARITY.name }
            if (charity.isNotEmpty() || aidInSpan.isNotEmpty()) item(key = "charity") {
                SectionCard(Sym.group, stringResource(R.string.money_charity_title)) {
                    if (charity.isNotEmpty()) {
                        Text(stringResource(R.string.money_given_personally, hryvnias(sum.charity)), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        StackBar(
                            CharityKind.entries.mapIndexed { i, k ->
                                Slice(k.name, stringResource(k.label()), hInt(charity.filter { it.charityKind == k.name }.sumOf { it.uah }), categoryColor(i))
                            } + Slice("NONE", stringResource(R.string.not_set), hInt(charity.filter { it.charityKind == null }.sumOf { it.uah }), ChartColors.grayLight),
                            selected = charityPick, onPick = { charityPick = it; awaitingOnly = false },
                        )
                    }
                    if (aidInSpan.isNotEmpty()) {
                        val given = aidInSpan.filter { !it.collected }
                        Text(
                            stringResource(R.string.money_aid_organised, hryvnias(aidInSpan.filter { it.collected }.sumOf { it.uah }), hryvnias(given.sumOf { it.uah }), given.size),
                            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(stringResource(R.string.money_aid_apart), style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
            }
            if (catPick != null || awaitingOnly || kindPick != null || charityPick != null) item(key = "filter") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    kindPick?.let { FilterBadge(stringResource(if (it == MoneyKind.INCOME.name) R.string.money_income else R.string.money_expense)) { kindPick = null } }
                    catPick?.let { FilterBadge(categoryName(it)) { catPick = null } }
                    if (awaitingOnly) FilterBadge(stringResource(R.string.money_awaiting_short)) { awaitingOnly = false }
                    charityPick?.let { k ->
                        FilterBadge(CharityKind.entries.firstOrNull { it.name == k }?.let { stringResource(it.label()) } ?: stringResource(R.string.not_set)) { charityPick = null }
                    }
                }
            }
            item(key = "list") {
                SectionCard(Sym.folder, stringResource(R.string.money_entries), count = shown.size) {
                    if (shown.isEmpty()) EmptyHint(Sym.info, stringResource(R.string.money_empty))
                    Paged(shown) { m -> MoneyRow(m, m.matterId?.let(titleOf::get), session) }
                }
            }
            item(key = "cats") {
                TextButton(onClick = { editCategories = true }) { Text(stringResource(R.string.money_my_categories)) }
            }
        }
    }
    if (editLimits) LimitsDialog(session, settings.associate { it.key to it.value }, onClose = { editLimits = false })
    if (editCategories) CategoriesDialog(session, settings.associate { it.key to it.value }, onClose = { editCategories = false })
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MoneyTile(minor: Long, label: String, color: Color, onClick: (() -> Unit)? = null) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(hryvnias(minor), style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
    }
}

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

/** A limit: what was spent against it this month; past the limit the bar turns coral. */
@Composable
private fun LimitRow(name: String, spent: Long, limit: Long, onClick: () -> Unit) {
    val over = spent > limit
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Row {
            Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("${formatAmount(spent)} / ${hryvnias(limit)}", style = MaterialTheme.typography.labelMedium,
                color = if (over) ChartColors.coral else Muted)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth((spent.toFloat() / limit).coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                .background(if (over) ChartColors.coral else ChartColors.blue))
        }
    }
}

/** Twelve months, money in and out side by side; the last one is this month. */
@Composable
private fun MonthBars(values: List<Pair<Long, Long>>, labels: List<String>, onPick: (Int) -> Unit) {
    val max = values.maxOfOrNull { maxOf(it.first, it.second) }?.coerceAtLeast(1) ?: 1
    Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, (inc, out) ->
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).clickable { onPick(i) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    Box(Modifier.width(6.dp).fillMaxHeight((inc.toFloat() / max).coerceAtLeast(0.02f)).clip(RoundedCornerShape(2.dp)).background(ChartColors.teal))
                    Box(Modifier.width(6.dp).fillMaxHeight((out.toFloat() / max).coerceAtLeast(0.02f)).clip(RoundedCornerShape(2.dp)).background(ChartColors.coral))
                }
                Text(labels[i], style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoneyRow(m: MoneyEntry, matterTitle: String?, session: Session) {
    val income = m.kind == MoneyKind.INCOME.name
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable { session.moneyDraft = MoneyDraft(m, isNew = false) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(categoryName(m.category), style = MaterialTheme.typography.titleSmall)
            val details = listOfNotNull(
                m.note.ifBlank { null }, m.toWhom.ifBlank { null },
                CharityKind.of(m.charityKind)?.let { stringResource(it.label()) },
            ).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.labelMedium, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MiniChip(Dates.short(m.day))
                if (m.awaitingRefund) MiniChip(stringResource(if (m.refunded) R.string.money_refunded else R.string.money_awaiting_short))
                if (matterTitle != null && m.matterId != null) MiniChip(matterTitle, onClick = { session.openMatter(m.matterId) })
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text((if (income) "+" else "−") + hryvnias(m.uah), style = MaterialTheme.typography.titleSmall, color = if (income) ChartColors.teal else MaterialTheme.colorScheme.onSurface)
            if (m.currency != "UAH") Text("${formatAmount(m.amount)} ${m.currency}", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

/** The dialog for one entry: amount, currency (with the rate), category, and charity details. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoneyDialog(db: MandatDb, session: Session, d: MoneyDraft) {
    val settings by remember(db) { db.settings().observeBudget() }.collectAsStateWithLifecycle(emptyList())
    val matters by remember(db) { db.matters().observeActiveTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    val allMatters by remember(db) { db.matters().observeAllTitles().hiding(session) { it.id } }.collectAsStateWithLifecycle(emptyList())
    var confirmDelete by remember { mutableStateOf(false) }
    val income = d.kind == MoneyKind.INCOME.name
    val own = decodeCategories(settings.firstOrNull { it.key == (if (income) SETTING_INCOME_CATEGORIES else SETTING_EXPENSE_CATEGORIES) }?.value)
    val defaults = if (income) IncomeCategory.entries.map { it.name } else ExpenseCategory.entries.map { it.name }
    val categories = (defaults + own + listOf(d.category)).distinct()
    var otherCurrency by remember { mutableStateOf(d.currency !in listOf("UAH", "USD", "EUR")) }

    EditSheet(
        title = stringResource(if (income) R.string.money_income else R.string.money_expense),
        onDismiss = { session.moneyDraft = null },
        onSave = { session.saveMoneyDraft() },
        saveEnabled = d.valid(),
    ) {
        SheetGroup {

                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickChip(stringResource(R.string.money_expense), !income) { d.kind = MoneyKind.EXPENSE.name; d.category = ExpenseCategory.HOME.name }
                    QuickChip(stringResource(R.string.money_income), income) { d.kind = MoneyKind.INCOME.name; d.category = IncomeCategory.SALARY.name }
                }
                OutlinedTextField(d.amountText, { d.amountText = it.take(20) }, label = { Text(stringResource(R.string.money_amount)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = d.amountText.isNotBlank() && d.amount() == null,
                    modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("UAH", "USD", "EUR").forEach { c ->
                        QuickChip(c, d.currency == c && !otherCurrency) { d.currency = c; otherCurrency = false }
                    }
                    QuickChip(stringResource(R.string.money_other_currency), otherCurrency) { otherCurrency = true; if (d.currency in listOf("UAH", "USD", "EUR")) d.currency = "" }
                }
                if (otherCurrency) {
                    OutlinedTextField(d.currency, { d.currency = it.filter { ch -> ch.isLetter() }.uppercase().take(3) }, label = { Text(stringResource(R.string.money_currency_code)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (d.currency != "UAH") {
                    OutlinedTextField(d.rateText, { d.rateText = it.take(12) }, label = { Text(stringResource(R.string.money_rate, d.currency.ifBlank { "…" })) },
                        supportingText = { Text(stringResource(R.string.money_rate_hint)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.money_category), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { c -> QuickChip(categoryName(c), d.category == c) { d.category = c } }
                }
                if (!income && d.category == ExpenseCategory.CHARITY.name) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CharityKind.entries.forEach { k ->
                            QuickChip(stringResource(k.label()), d.charityKind == k) { d.charityKind = if (d.charityKind == k) null else k }
                        }
                    }
                    OutlinedTextField(d.toWhom, { d.toWhom = it }, label = { Text(stringResource(R.string.money_to_whom)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                DueField(d.day, allowClear = false, label = R.string.event_day) { if (it != null) d.day = it }
                OutlinedTextField(d.note, { d.note = it }, label = { Text(stringResource(R.string.money_note)) }, modifier = Modifier.fillMaxWidth())
                MatterPicker(matters, allMatters, d.matterId) { d.matterId = it }
                if (!income) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = d.awaitingRefund, onCheckedChange = { d.awaitingRefund = it; if (!it) d.refunded = false })
                        Text(stringResource(R.string.money_awaiting_mark), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (d.awaitingRefund) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = d.refunded, onCheckedChange = { d.refunded = it })
                        Text(stringResource(R.string.money_refunded), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!d.isNew) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete)) }
        }
    }
    if (confirmDelete) {
        ConfirmDelete(onConfirm = {
            val id = d.entry.id
            session.moneyDraft = null
            session.write { it.money().delete(id) }
        }, onCancel = { confirmDelete = false })
    }
}

/** Monthly limits per expense category (empty means no limit). */
@Composable
private fun LimitsDialog(session: Session, settings: Map<String, String>, onClose: () -> Unit) {
    val cats = ExpenseCategory.entries.map { it.name } + decodeCategories(settings[SETTING_EXPENSE_CATEGORIES])
    val texts = remember { mutableStateOf(cats.associateWith { c -> settings[SETTING_LIMIT_PREFIX + c]?.toLongOrNull()?.let { formatAmount(it).replace(" ", "") }.orEmpty() }) }
    EditSheet(
        title = stringResource(R.string.money_limits),
        onDismiss = onClose,
        onSave = {
                val values = texts.value
                onClose()
                session.write { db ->
                    values.forEach { (c, t) ->
                        val v = parseAmount(t)
                        db.settings().put(Setting(SETTING_LIMIT_PREFIX + c, (v ?: 0L).toString()))
                    }
                }
            },
    ) {
        SheetGroup {

                Text(stringResource(R.string.money_limits_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
                cats.forEach { c ->
                    OutlinedTextField(texts.value[c].orEmpty(), { v -> texts.value = texts.value + (c to v.take(15)) }, label = { Text(categoryName(c)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
        }
    }
}

/** The owner's own categories, added to the default ones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoriesDialog(session: Session, settings: Map<String, String>, onClose: () -> Unit) {
    var income by remember { mutableStateOf(decodeCategories(settings[SETTING_INCOME_CATEGORIES])) }
    var expense by remember { mutableStateOf(decodeCategories(settings[SETTING_EXPENSE_CATEGORIES])) }
    var newIn by remember { mutableStateOf("") }
    var newOut by remember { mutableStateOf("") }
    EditSheet(
        title = stringResource(R.string.money_my_categories),
        onDismiss = onClose,
        onSave = {
                val i = (income + listOfNotNull(newIn.trim().ifEmpty { null })).distinct()
                val e = (expense + listOfNotNull(newOut.trim().ifEmpty { null })).distinct()
                onClose()
                session.write { db ->
                    db.settings().put(Setting(SETTING_INCOME_CATEGORIES, i.joinToString("\n")))
                    db.settings().put(Setting(SETTING_EXPENSE_CATEGORIES, e.joinToString("\n")))
                }
            },
    ) {
        SheetGroup {

                Text(stringResource(R.string.money_income), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { income.forEach { c -> FilterBadge(c) { income = income - c } } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newIn, { newIn = it.take(40) }, singleLine = true, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.money_new_category)) })
                    TextButton(onClick = { if (newIn.isNotBlank()) { income = (income + newIn.trim()).distinct(); newIn = "" } }) { Text("+") }
                }
                Text(stringResource(R.string.money_expense), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { expense.forEach { c -> FilterBadge(c) { expense = expense - c } } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newOut, { newOut = it.take(40) }, singleLine = true, modifier = Modifier.weight(1f), label = { Text(stringResource(R.string.money_new_category)) })
                    TextButton(onClick = { if (newOut.isNotBlank()) { expense = (expense + newOut.trim()).distinct(); newOut = "" } }) { Text("+") }
                }
                Text(stringResource(R.string.money_categories_hint), style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

/**
 * The ledger of a fundraising matter: collected and handed over, money or things. Its totals are
 * shown in the budget as "organised aid", never added to the owner's own money.
 */
@Composable
fun AidLedger(db: MandatDb, session: Session, matterId: Long) {
    val records by remember(db, matterId) { db.money().observeAidFor(matterId).hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    var adding by remember { mutableStateOf<Boolean?>(null) }
    val inMoney = records.filter { it.collected }.sumOf { it.uah }
    val outMoney = records.filter { !it.collected }.sumOf { it.uah }
    LinkSection(stringResource(R.string.aid_title))
    MCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
            MoneyTile(inMoney, stringResource(R.string.aid_collected), ChartColors.teal)
            MoneyTile(outMoney, stringResource(R.string.aid_given), ChartColors.blue)
        }
        Text(stringResource(R.string.money_aid_apart), style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(top = 6.dp))
    }
    records.take(30).forEach { r ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(listOf(stringResource(if (r.collected) R.string.aid_collected else R.string.aid_given), r.what).filter { it.isNotBlank() }.joinToString(": "),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(Dates.short(r.day), r.party).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            if (r.amount > 0) Text(hryvnias(r.uah), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { session.write { it.money().deleteAid(r.id) } }) { Text("✕") }
        }
    }
    Row {
        TextButton(onClick = { adding = true }) { Text("+ " + stringResource(R.string.aid_collected)) }
        TextButton(onClick = { adding = false }) { Text("+ " + stringResource(R.string.aid_given)) }
    }
    adding?.let { collected -> AidDialog(session, matterId, collected) { adding = null } }
}

@Composable
private fun AidDialog(session: Session, matterId: Long, collected: Boolean, onClose: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var what by remember { mutableStateOf("") }
    var party by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(Dates.today()) }
    val minor = parseAmount(amount) ?: 0L
    EditSheet(
        title = stringResource(if (collected) R.string.aid_collected else R.string.aid_given),
        onDismiss = onClose,
        onSave = {
            onClose()
            session.write {
                it.money().insertAid(AidRecord(matterId = matterId, day = day, collected = collected, amount = minor, currency = "UAH", uah = minor,
                    what = what.trim(), party = party.trim(), createdAt = System.currentTimeMillis()))
            }
        },
        saveEnabled = minor > 0 || what.isNotBlank(),
    ) {
        SheetGroup {
            OutlinedTextField(amount, { amount = it.take(20) }, label = { Text(stringResource(R.string.aid_amount)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(what, { what = it.take(300) }, label = { Text(stringResource(R.string.aid_what)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(party, { party = it.take(300) }, label = { Text(stringResource(if (collected) R.string.aid_from else R.string.aid_to)) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            DueField(day, allowClear = false, label = R.string.event_day) { if (it != null) day = it }
        }
    }

}

/** The budget on Review: this month in two numbers and one line; tap for the whole budget. */
@Composable
fun BudgetCard(db: MandatDb, session: Session) {
    val entries by remember(db) { db.money().observeAll().hiding(session) { it.matterId } }.collectAsStateWithLifecycle(emptyList())
    val sum = summarize(entries, spanOf(Period.MONTH, Dates.today()))
    MCard(onClick = { session.showBudget = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.budget_month), style = MaterialTheme.typography.labelLarge, color = Muted)
                Text("${hryvnias(sum.income)} · −${hryvnias(sum.expense)}", style = MaterialTheme.typography.titleSmall)
                if (sum.charity > 0) Text(stringResource(R.string.money_given_personally, hryvnias(sum.charity)), style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            Text("→", color = MaterialTheme.colorScheme.primary)
        }
    }
}
