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
import uniffi.zeron_core.CoreClient
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
private data class ModelChoice(
    val harness: String,
    val harnessLabel: String,
    val id: String,
    val label: String,
    val efforts: List<String>,
)

/** The last catalog each host reported: the chip opens on a real model name at once. */
private val modelCache = HashMap<String, List<ModelChoice>>()

private fun catalogModels(): List<ModelChoice> =
    uniffi.zeron_core.fallbackHarnesses().filter { it.offered }.flatMap { h ->
        uniffi.zeron_core.fallbackModels(h.id).map { ModelChoice(h.id, h.label, it.id, it.label, it.reasoningLevels) }
    }

/** Every offered harness on the host and its models (ListHarnesses + ListModels). */
private suspend fun hostModels(client: CoreClient, device: String): List<ModelChoice> = coroutineScope {
    client.listHarnesses(device).filter { it.offered }.map { h ->
        async {
            val models = client.listModels(device, h.id).ifEmpty { uniffi.zeron_core.fallbackModels(h.id) }
            models.map { ModelChoice(h.id, h.label, it.id, it.label, it.reasoningLevels) }
        }
    }.awaitAll().flatten()
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
    var projectId by remember { mutableStateOf(model.newSessionProject ?: projects.firstOrNull()?.id) }
    var hostId by remember { mutableStateOf(if (projectId == null) (hosts.firstOrNull { it.online } ?: hosts.firstOrNull())?.id else null) }
    var harness by remember { mutableStateOf("claude-code") }
    var modelId by remember { mutableStateOf<String?>(null) }
    var effort by remember { mutableStateOf<String?>(null) }
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
    var models by remember { mutableStateOf(modelCache[device] ?: catalogModels()) }
    LaunchedEffect(device) {
        models = modelCache[device] ?: catalogModels()
        if (device.isEmpty()) return@LaunchedEffect
        val fresh = runCatching { hostModels(client, device) }.getOrDefault(emptyList())
        if (fresh.isNotEmpty()) {
            modelCache[device] = fresh
            models = fresh
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
        val efforts = current?.efforts.orEmpty()
        if (efforts.isNotEmpty()) add(Chip("effort", reasoningLabel(effort ?: efforts[efforts.size / 2])))
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
                                val id = client.createSession(NewSession(target, model.defaultConfig(harness, modelId, effort), if (worktree) null else branch, null, null))
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
                        byHarness.forEach { (h, list) ->
                            val selected = h == harness
                            add(
                                MenuEntry(
                                    list.first().harnessLabel,
                                    subtitle = if (selected) current?.label else pluralStringResource(R.plurals.model_count, list.size, list.size),
                                    checked = selected,
                                    submenu = true,
                                    icon = { _ -> BrandMark(h, colors, 16.dp) },
                                ) { modelHarness = h },
                            )
                        }
                    } else {
                        val list = open.value
                        add(MenuEntry(list.first().harnessLabel, back = true) { modelHarness = null })
                        list.forEach { m ->
                            add(MenuEntry(m.label, checked = m.harness == harness && m.id == modelId) {
                                harness = m.harness
                                modelId = m.id
                                effort = null
                            })
                        }
                    }
                }
                "effort" -> stringResource(R.string.reasoning_effort) to current?.efforts.orEmpty().let { levels ->
                    // The chip shows the middle level while nothing is picked
                    // (the engine default); check that same row so they agree.
                    val shown = effort ?: levels.getOrNull(levels.size / 2)
                    levels.map { e -> MenuEntry(reasoningLabel(e), checked = e == shown) { effort = e } }
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
