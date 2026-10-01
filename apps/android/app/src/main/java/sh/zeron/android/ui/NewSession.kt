package sh.zeron.android.ui

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import sh.zeron.android.design.BackButton
import sh.zeron.android.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import sh.zeron.android.core.Machine
import sh.zeron.android.core.ZeronModel
import sh.zeron.android.design.AnchoredMenu
import sh.zeron.android.design.BrandMark
import sh.zeron.android.design.Glyph
import sh.zeron.android.design.Glyphs
import sh.zeron.android.design.LocalZeronColors
import sh.zeron.android.design.MenuEntry
import sh.zeron.android.design.ZeronType
import sh.zeron.android.design.menuSection
import uniffi.zeron_core.BusyPolicy
import uniffi.zeron_core.CatalogSource
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.DirectPhase
import uniffi.zeron_core.ModelCatalog
import uniffi.zeron_core.ModelOption
import uniffi.zeron_core.NewSession
import uniffi.zeron_core.RepoRef
import uniffi.zeron_core.SendRequest
import uniffi.zeron_core.SessionTarget
import uniffi.zeron_core.WorktreeSpec
import uniffi.zeron_core.fileMentionLink
import uniffi.zeron_core.harnessLabel
import uniffi.zeron_core.modelLabel
import uniffi.zeron_core.reasoningLabel

/** One model on one harness, as the model menu lists them (iOS ModelChoice). */
internal data class ModelChoice(
    val harness: String,
    val harnessLabel: String,
    val id: String,
    val label: String,
    val efforts: List<String>,
    /** What else the model offers (Codex service tier, Claude's 1M context…). */
    val options: List<ModelOption> = emptyList(),
)

/**
 * A host's model menu: every offered harness's models, plus the harnesses
 * whose list isn't live (the computer didn't answer: saved or built-in).
 */
internal data class HostCatalog(
    val models: List<ModelChoice>,
    val stale: Map<String, CatalogSource> = emptyMap(),
) {
    /** [harness]'s list swapped for [fresh] (other harnesses untouched). */
    fun with(harness: String, fresh: List<ModelChoice>, source: CatalogSource): HostCatalog {
        val at = models.indexOfFirst { it.harness == harness }.takeIf { it >= 0 } ?: models.size
        val rest = models.filter { it.harness != harness }
        val merged = rest.take(at.coerceAtMost(rest.size)) + fresh + rest.drop(at.coerceAtMost(rest.size))
        return HostCatalog(merged, if (source == CatalogSource.LIVE) stale - harness else stale + (harness to source))
    }
}

/** Screenshot tests: stand-ins for the host's ListModels answers. */
internal object CatalogHooks {
    var models: ((harness: String, force: Boolean) -> ModelCatalog)? = null
}

/** The last catalog each host reported: the chip opens on a real model name at once. */
private val modelCache = HashMap<String, HostCatalog>()

private fun catalogModels(): List<ModelChoice> =
    uniffi.zeron_core.fallbackHarnesses().filter { it.offered }.flatMap { h ->
        uniffi.zeron_core.fallbackModels(h.id).map { ModelChoice(h.id, h.label, it.id, it.label, it.reasoningLevels, it.options) }
    }

/** One harness's models and where they came from. `force` re-probes the CLI on the computer. */
private suspend fun harnessModels(client: CoreClient, device: String, harness: String, label: String, force: Boolean): Pair<List<ModelChoice>, CatalogSource> {
    val catalog = CatalogHooks.models?.invoke(harness, force) ?: client.modelCatalog(device, harness, force)
    val models = catalog.models.ifEmpty { uniffi.zeron_core.fallbackModels(harness) }
    return models.map { ModelChoice(harness, label, it.id, it.label, it.reasoningLevels, it.options) } to catalog.source
}

/** Every offered harness on the host and its models (ListHarnesses + ListModels). */
private suspend fun hostModels(client: CoreClient, device: String): HostCatalog = coroutineScope {
    val harnesses = client.listHarnesses(device).filter { it.offered }
    // One harness failing (or throwing) mustn't take the others' live lists with it.
    val parts = harnesses.map { h ->
        async {
            runCatching { harnessModels(client, device, h.id, h.label, force = false) }.getOrElse {
                uniffi.zeron_core.fallbackModels(h.id).map { m -> ModelChoice(h.id, h.label, m.id, m.label, m.reasoningLevels, m.options) } to CatalogSource.STATIC
            }
        }
    }.awaitAll()
    HostCatalog(
        models = parts.flatMap { it.first },
        stale = harnesses.zip(parts).filter { (_, part) -> part.second != CatalogSource.LIVE }.associate { (h, part) -> h.id to part.second },
    )
}

/**
 * New Session, like iOS NewSessionViewController: the composer's chips pick
 * the project (projects grouped by computer, No Project, New Project…), the
 * host when there's no project, the branch or a new worktree for git
 * projects, the model (grouped by harness) and the reasoning effort.
 */
@Composable
fun NewSessionSheet(model: ZeronModel, onDismiss: () -> Unit) {
    val colors = LocalZeronColors.current
    val context = LocalContext.current
    val client = model.client ?: return
    val projects = model.workspace?.projects.orEmpty()
    val hosts = remember(model.workspace) { client.executionDevices() }
    var text by remember { mutableStateOf("") }
    // The project it was opened from, else the last pick on this computer,
    // else the most recently used project (not just the first in the list).
    val opening = remember {
        NewSessionMemory.initial(
            explicit = model.newSessionProject,
            remembered = NewSessionMemory.load(context, model.activeMachine),
            projects = projects,
            hosts = hosts.map { it.id },
            id = { it.id },
            lastUsedMs = { p -> p.sessions.maxOfOrNull { it.lastActivityMs } ?: p.createdAtMs },
        )
    }
    var projectId by remember { mutableStateOf(opening.projectId) }
    var hostId by remember { mutableStateOf(if (projectId == null) opening.hostId ?: (hosts.firstOrNull { it.online } ?: hosts.firstOrNull())?.id else null) }
    // Remember where this one goes when the sheet closes (sent or not), like iOS.
    val picks by androidx.compose.runtime.rememberUpdatedState(NewSessionMemory.Picks(projectId, if (projectId == null) hostId else null))
    androidx.compose.runtime.DisposableEffect(model.activeMachine) {
        val machine = model.activeMachine
        onDispose { NewSessionMemory.save(context, machine, picks) }
    }
    var harness by remember { mutableStateOf("claude-code") }
    var modelId by remember { mutableStateOf<String?>(null) }
    var effort by remember { mutableStateOf<String?>(null) }
    // Model options picked here (option id → choice id); unpicked = the model's default.
    var optionPicks by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var branch by remember { mutableStateOf<String?>(null) }
    var worktree by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var addedNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var chipMenu by remember { mutableStateOf<Pair<String, Rect>?>(null) }
    // Drill-downs inside the open chip menu: a harness's models, the project sort choice.
    var modelHarness by remember { mutableStateOf<String?>(null) }
    var projectSortOpen by remember { mutableStateOf(false) }
    var refs by remember { mutableStateOf<List<RepoRef>?>(null) }
    LaunchedEffect(Unit) { model.newSessionProject = null }
    var scheduleOpen by remember { mutableStateOf(false) }
    val pendingNew = rememberScheduledNewSessions(model.activeMachine)
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }

    val project = projects.firstOrNull { it.id == projectId }
    val device = project?.deviceId ?: hostId ?: hosts.firstOrNull()?.id ?: ""
    var catalog by remember { mutableStateOf(modelCache[device] ?: HostCatalog(catalogModels())) }
    val models = catalog.models
    // Read again when the link to the computer comes up: a sheet opened while
    // it was still connecting must not keep the fallback list for good.
    val linkUp = model.directStatus?.phase.let { it == null || it == DirectPhase.LIVE || it == DirectPhase.SYNCING }
    LaunchedEffect(device, linkUp) {
        catalog = modelCache[device] ?: HostCatalog(catalogModels())
        if (device.isEmpty()) return@LaunchedEffect
        val fresh = runCatching { hostModels(client, device) }.getOrNull()
        if (fresh != null && fresh.models.isNotEmpty()) {
            modelCache[device] = fresh
            catalog = fresh
        }
    }
    val scope = rememberCoroutineScope()
    var refreshing by remember(device) { mutableStateOf<Set<String>>(emptySet()) }
    var forced by remember(device) { mutableStateOf<Set<String>>(emptySet()) }
    // Ask the computer to re-probe one CLI (its model list opened, or retry).
    val refresh: (String) -> Unit = refresh@{ h ->
        if (h in refreshing || device.isEmpty()) return@refresh
        val label = models.firstOrNull { it.harness == h }?.harnessLabel ?: harnessLabel(h)
        refreshing = refreshing + h
        forced = forced + h
        scope.launch {
            val fresh = runCatching { harnessModels(client, device, h, label, force = true) }.getOrNull()
            refreshing = refreshing - h
            if (fresh != null && fresh.first.isNotEmpty()) {
                catalog = catalog.with(h, fresh.first, fresh.second)
                modelCache[device] = catalog
            }
        }
    }
    // Keep the pick valid for this host's catalog: same harness first.
    val current = models.firstOrNull { it.harness == harness && it.id == modelId }
        ?: models.firstOrNull { it.harness == harness }
        ?: models.firstOrNull()
    LaunchedEffect(current) {
        if (current != null && (current.harness != harness || current.id != modelId)) {
            harness = current.harness
            modelId = current.id
        }
    }
    LaunchedEffect(chipMenu?.first, project?.id) {
        if (chipMenu?.first == "branch" && project != null) {
            refs = null
            refs = runCatching { client.listRefs(project.deviceId, project.path) }.getOrDefault(emptyList()).sortedByDescending { it.current }
        }
    }

    val chips = buildList {
        if (projectId != null) {
            val name = project?.name ?: addedNames[projectId] ?: stringResource(R.string.project)
            add(Chip("project", name, colorIndex = project?.colorIndex?.toInt() ?: 0))
            if (project?.gitDetected == true) add(Chip("branch", if (worktree) stringResource(R.string.new_worktree) else branch ?: stringResource(R.string.current_branch)))
        } else {
            add(Chip("project", stringResource(R.string.no_project)))
            add(Chip("host", hosts.firstOrNull { it.id == hostId }?.name ?: stringResource(R.string.choose_host)))
        }
        // Never the harness name in place of a model.
        val modelTitle = current?.label ?: modelId?.let { modelLabel(harness, it) } ?: harnessLabel(harness)
        add(Chip("model", modelTitle, harness = harness))
        // One traits chip like desktop's: the effort plus any non-default option ("X-High · Fast").
        val efforts = current?.efforts.orEmpty()
        val options = current?.options.orEmpty().filter { it.choices.size > 1 }
        if (efforts.isNotEmpty() || options.isNotEmpty()) {
            val parts = buildList {
                if (efforts.isNotEmpty()) add(reasoningLabel(effort ?: efforts[efforts.size / 2]))
                options.forEach { o ->
                    val pick = optionPicks[o.id]?.takeIf { it != o.defaultChoice }
                    pick?.let { id -> o.choices.firstOrNull { it.id == id } }?.let { add(it.label) }
                }
            }
            add(Chip("effort", parts.joinToString(" · ").ifEmpty { options.first().label }))
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(colors, onClick = onDismiss)
                Text(stringResource(R.string.new_session), color = colors.text, fontFamily = ZeronType.Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            }
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                BrandMark(harness, colors, 34.dp)
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.new_session_headline), color = colors.text, fontFamily = ZeronType.Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
            }
            Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 768.dp).fillMaxWidth()) {
                    pendingNew.forEach { message ->
                        ScheduledChip(colors, message, detail = message.newSession?.label) {
                            sh.zeron.android.schedule.ScheduledAlarms.cancel(context, message.id)
                            if (text.isBlank()) text = message.text
                            model.showToast(context.getString(R.string.schedule_cancelled))
                        }
                    }
                    ComposerBar(
                        colors = colors,
                        text = text,
                        onText = { text = it },
                        placeholder = stringResource(R.string.new_session_placeholder),
                        running = false,
                        canSteer = false,
                        focused = true,
                        onFocus = {},
                        chips = chips,
                        onChip = { chip, rect ->
                            modelHarness = null
                            projectSortOpen = false
                            chipMenu = chip.id to rect
                        },
                        images = emptyList(),
                        onRemoveImage = {},
                        onSend = send@{
                            val body = text.trim()
                            if (body.isEmpty()) return@send
                            val target = when {
                                projectId != null -> SessionTarget.Project(projectId!!)
                                hostId != null -> SessionTarget.Projectless(hostId!!)
                                else -> {
                                    model.showToast(context.getString(R.string.choose_project_or_host))
                                    return@send
                                }
                            }
                            try {
                                val id = client.createSession(NewSession(target, model.defaultConfig(harness, modelId, effort, optionsFor(current, optionPicks)), if (worktree) null else branch, null, null))
                                val handle = client.openSession(id)
                                val spec = if (worktree && project != null) WorktreeSpec(project.path, branch ?: "HEAD", project.id) else null
                                handle.send(SendRequest(body, emptyList(), spec, BusyPolicy.QUEUE))
                                handle.close()
                                onDismiss()
                                model.openSession(id)
                            } catch (t: Throwable) {
                                model.showToast(t.message ?: context.getString(R.string.start_session_failed))
                            }
                        },
                        mentionSearch = search@{ q ->
                            val p = project ?: return@search emptyList()
                            runCatching { client.searchFiles(p.deviceId, null, p.id, q) }.getOrDefault(emptyList())
                        },
                        onMention = { path, dir -> text = text.replace(Regex("@[^\\s]*$"), fileMentionLink(path, dir) + " ") },
                        onSchedule = { scheduleOpen = true },
                    )
                }
            }
        }
        chipMenu?.let { (id, anchor) ->
            val close = { chipMenu = null }
            val (title, entries) = when (id) {
                "project" -> if (projectSortOpen) {
                    // The sort toggle beside a computer's name: pick the order, back to the list.
                    stringResource(R.string.sort_projects) to listOf(
                        MenuEntry(stringResource(R.string.project), back = true) { projectSortOpen = false },
                        MenuEntry(stringResource(R.string.sort_recent), checked = model.projectSort == ZeronModel.ProjectSort.Recent, keepOpen = true, icon = { c -> Glyph(Glyphs.Recent, 17.dp, c) }) {
                            model.applyProjectSort(ZeronModel.ProjectSort.Recent)
                            projectSortOpen = false
                        },
                        MenuEntry(stringResource(R.string.sort_name), checked = model.projectSort == ZeronModel.ProjectSort.Name, keepOpen = true, icon = { c -> SortAlphaMark(c) }) {
                            model.applyProjectSort(ZeronModel.ProjectSort.Name)
                            projectSortOpen = false
                        },
                    )
                } else stringResource(R.string.project) to buildList {
                    // Projects grouped by computer, like the iOS inline sections;
                    // within a computer, in the remembered order (ProjectOrder).
                    val sortLabel = stringResource(R.string.sort_projects)
                    projects.groupBy { it.deviceId }.entries
                        .sortedBy { (_, list) -> list.first().deviceName ?: "" }
                        .forEach { (_, unsorted) ->
                            val list = when (model.projectSort) {
                                ZeronModel.ProjectSort.Name -> ProjectOrder.byName(unsorted) { it.name }
                                ZeronModel.ProjectSort.Recent -> ProjectOrder.byRecent(unsorted) { p -> p.sessions.maxOfOrNull { it.lastActivityMs } ?: p.createdAtMs }
                            }
                            val head = list.first()
                            val hostName = head.deviceName ?: stringResource(R.string.host_fallback)
                            add(
                                MenuEntry(
                                    if (head.deviceOnline) hostName else stringResource(R.string.host_offline_suffix, hostName),
                                    header = true,
                                    icon = { c -> Glyph(Glyphs.Sort, 16.dp, c, Modifier.semantics { contentDescription = sortLabel }) },
                                ) { projectSortOpen = true },
                            )
                            list.forEach { p ->
                                add(MenuEntry(p.name, checked = p.id == projectId, icon = { c -> Glyph(Glyphs.Folder, 17.dp, c) }) {
                                    projectId = p.id
                                    hostId = null
                                    branch = null
                                    worktree = false
                                })
                            }
                        }
                    add(menuSection())
                    add(MenuEntry(stringResource(R.string.no_project_ellipsis), checked = projectId == null, icon = { c -> Glyph(Glyphs.Tray, 17.dp, c) }) {
                        projectId = null
                        hostId = hostId ?: (hosts.firstOrNull { it.online } ?: hosts.firstOrNull())?.id
                    })
                    add(MenuEntry(stringResource(R.string.new_project_ellipsis), icon = { c -> Glyph(Glyphs.FolderPlus, 17.dp, c) }) { browsing = true })
                    add(menuSection())
                    add(MenuEntry(stringResource(R.string.add_computer_ellipsis), icon = { c -> Glyph(Glyphs.Computer, 17.dp, c) }) { model.editMachine = Machine() })
                }
                "host" -> stringResource(R.string.run_on) to buildList {
                    hosts.forEach { h ->
                        add(MenuEntry(h.name, subtitle = stringResource(if (h.online) R.string.online else R.string.offline), checked = h.id == hostId, icon = { c -> Glyph(Glyphs.Computer, 17.dp, c) }) { hostId = h.id })
                    }
                    add(menuSection())
                    add(MenuEntry(stringResource(R.string.add_computer_ellipsis), icon = { c -> Glyph(Glyphs.Computer, 17.dp, c) }) { model.editMachine = Machine() })
                }
                "branch" -> stringResource(R.string.checkout) to buildList {
                    add(MenuEntry(stringResource(R.string.new_worktree), checked = worktree) { worktree = !worktree })
                    add(menuSection(stringResource(R.string.branch)))
                    val list = refs.orEmpty()
                    list.forEach { ref ->
                        add(MenuEntry(ref.name, subtitle = if (ref.current) stringResource(R.string.checked_out) else null, checked = ref.name == (branch ?: list.firstOrNull()?.name)) { branch = ref.name })
                    }
                    if (refs != null && list.isEmpty()) add(MenuEntry(stringResource(R.string.no_branches)) {})
                }
                "model" -> stringResource(R.string.model) to buildList {
                    // Two levels: the CLIs first (the current one checked, its
                    // model as the subtitle), then the chosen CLI's models.
                    val byHarness = models.groupBy { it.harness }.entries.sortedBy { it.key }
                    val open = modelHarness?.let { h -> byHarness.firstOrNull { it.key == h } }
                    if (open == null) {
                        if (catalog.stale.isNotEmpty()) {
                            // Short here (the CLI rows set the width); the CLI's own list says which list it shows.
                            val names = catalog.stale.keys.sorted().joinToString("、") { h -> models.firstOrNull { it.harness == h }?.harnessLabel ?: harnessLabel(h) }
                            val reading = catalog.stale.keys.any { it in refreshing }
                            add(
                                MenuEntry(
                                    stringResource(if (reading) R.string.model_list_reading else R.string.model_list_stale_short),
                                    subtitle = stringResource(R.string.model_list_retry_for, names),
                                    keepOpen = true,
                                    icon = { c -> Glyph(Glyphs.Refresh, 17.dp, c) },
                                ) { catalog.stale.keys.forEach(refresh) },
                            )
                        }
                        byHarness.forEach { (h, list) ->
                            val selected = h == harness
                            add(
                                MenuEntry(
                                    list.first().harnessLabel,
                                    subtitle = if (selected) current?.label else pluralStringResource(R.plurals.model_count, list.size, list.size),
                                    checked = selected,
                                    submenu = true,
                                    icon = { _ -> BrandMark(h, colors, 16.dp) },
                                ) {
                                    modelHarness = h
                                    // Opening a CLI's list asks for a fresh one, once per sheet.
                                    if (h !in forced) refresh(h)
                                },
                            )
                        }
                    } else {
                        val list = open.value
                        add(MenuEntry(list.first().harnessLabel, back = true) { modelHarness = null })
                        catalog.stale[open.key]?.let { source ->
                            add(staleRow(listOf(source), open.key in refreshing) { refresh(open.key) })
                        }
                        list.forEach { m ->
                            add(MenuEntry(m.label, checked = m.harness == harness && m.id == modelId) {
                                harness = m.harness
                                modelId = m.id
                                effort = null
                                optionPicks = emptyMap()
                            })
                        }
                    }
                }
                "effort" -> stringResource(if (current?.efforts.isNullOrEmpty()) R.string.model_options else R.string.reasoning_effort) to buildList {
                    // The chip shows the middle level while nothing is picked
                    // (the engine default); check that same row so they agree.
                    val levels = current?.efforts.orEmpty()
                    val shown = effort ?: levels.getOrNull(levels.size / 2)
                    levels.forEach { e -> add(MenuEntry(reasoningLabel(e), checked = e == shown) { effort = e }) }
                    // Then what else the model offers (desktop TraitsPicker): Codex's
                    // service tier, Claude's 1M context window…
                    current?.options.orEmpty().filter { it.choices.size > 1 }.forEach { o ->
                        if (isNotEmpty()) add(menuSection(o.label)) else add(MenuEntry(o.label, header = true) {})
                        val picked = optionPicks[o.id] ?: o.defaultChoice
                        o.choices.forEach { c ->
                            add(MenuEntry(c.label, checked = c.id == picked) { optionPicks = optionPicks + (o.id to c.id) })
                        }
                    }
                }
                else -> null to emptyList()
            }
            // A drill-down swaps the whole list: give each level its own panel.
            androidx.compose.runtime.key(id, modelHarness, projectSortOpen) {
                AnchoredMenu(colors, anchor, title, entries, loading = id == "branch" && refs == null, onDismiss = close)
            }
        }
        if (scheduleOpen) {
            ScheduleSendDialog(colors, onDismiss = { scheduleOpen = false }) { atMs ->
                scheduleOpen = false
                val body = text.trim()
                if (body.isEmpty()) return@ScheduleSendDialog
                if (projectId == null && hostId == null) {
                    model.showToast(context.getString(R.string.choose_project_or_host))
                    return@ScheduleSendDialog
                }
                // The same picks an immediate send would use, frozen now.
                val label = project?.name ?: projectId?.let { addedNames[it] } ?: hosts.firstOrNull { it.id == hostId }?.name.orEmpty()
                val spec = sh.zeron.android.schedule.NewSessionSpec(
                    projectId = projectId,
                    hostId = if (projectId == null) hostId else null,
                    harness = harness,
                    model = modelId,
                    effort = effort,
                    branch = branch,
                    worktree = worktree && project != null,
                    projectPath = project?.path,
                    label = label,
                )
                sh.zeron.android.schedule.ScheduledAlarms.schedule(
                    context,
                    sh.zeron.android.schedule.ScheduledMessage(
                        workspace = model.activeMachine,
                        chatId = "",
                        text = body,
                        atMs = atMs,
                        chatTitle = context.getString(R.string.sched_new_session_title, label),
                        newSession = spec,
                    ),
                )
                text = ""
                model.showToast(context.getString(R.string.sched_new_toast, scheduleWhenText(context, atMs)))
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        if (browsing) {
            NewProjectScreen(model, initialDeviceId = project?.deviceId ?: hostId, onClose = { browsing = false }, onCreated = { id, name ->
                browsing = false
                addedNames = addedNames + (id to name)
                projectId = id
                hostId = null
                branch = null
                worktree = false
            })
        }
    }
}

/** "A↓Z" for the name sort row. */
@Composable
private fun SortAlphaMark(color: androidx.compose.ui.graphics.Color) {
    Text("A–Z", color = color, fontFamily = ZeronType.Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
}

/** 「没能从电脑读取最新列表 · 重试」: the list shown isn't the computer's live one. */
@Composable
private fun staleRow(sources: Collection<CatalogSource>, reading: Boolean, onRetry: () -> Unit): MenuEntry {
    val title = stringResource(if (reading) R.string.model_list_reading else R.string.model_list_stale)
    val subtitle = stringResource(if (CatalogSource.SAVED in sources) R.string.model_list_showing_saved else R.string.model_list_showing_builtin)
    return MenuEntry(title, subtitle = subtitle, keepOpen = true, icon = { c -> Glyph(Glyphs.Refresh, 17.dp, c) }) { onRetry() }
}

/** The options to start the session with: only ones the model offers, picked away from its default. */
internal fun optionsFor(model: ModelChoice?, picks: Map<String, String>): Map<String, String> {
    val offered = model?.options.orEmpty()
    return picks.filter { (id, choice) -> offered.any { o -> o.id == id && o.defaultChoice != choice && o.choices.any { it.id == choice } } }
}
