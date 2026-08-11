const state = {
  accessToken: null,
  sessionId: null,
  sending: false,
  isAdmin: false,
  hasChatConsent: false,
  grantedConsentTypes: new Set(),
  modelName: "multimodalAgent-qwen3.5-9b-benchmark:latest",
  roles: new Set(),
  capabilities: {
    reviewCases: false,
    viewOperations: false,
    manageKnowledge: false
  },
  latestReports: [],
  latestCases: [],
  caseFilter: "ACTIVE"
};

let accessTokenRefresh = null;

const $ = (selector) => document.querySelector(selector);

const els = {
  serviceState: $("#serviceState"),
  modelState: $("#modelState"),
  runtimeModel: $("#runtimeModel"),
  loginForm: $("#loginForm"),
  username: $("#username"),
  password: $("#password"),
  loginState: $("#loginState"),
  accountPanel: $("#accountPanel"),
  activeAccount: $("#activeAccount"),
  activeRole: $("#activeRole"),
  switchAccount: $("#switchAccount"),
  studentView: $("#studentView"),
  adminView: $("#adminView"),
  profileText: $("#profileText"),
  consentGate: $("#consentGate"),
  consentGateText: $("#consentGateText"),
  reviewConsent: $("#reviewConsent"),
  consentOverlay: $("#consentOverlay"),
  consentForm: $("#consentForm"),
  privacyConsent: $("#privacyConsent"),
  sensitiveConsent: $("#sensitiveConsent"),
  consentState: $("#consentState"),
  declineConsent: $("#declineConsent"),
  grantConsent: $("#grantConsent"),
  sessionBadge: $("#sessionBadge"),
  messages: $("#messages"),
  pipelineSteps: $("#pipelineSteps"),
  chatForm: $("#chatForm"),
  messageInput: $("#messageInput"),
  audioInput: $("#audioInput"),
  imageInput: $("#imageInput"),
  videoInput: $("#videoInput"),
  attachmentState: $("#attachmentState"),
  clearAttachments: $("#clearAttachments"),
  newSessionButton: $("#newSessionButton"),
  sendButton: $("#sendButton"),
  supportRefresh: $("#supportRefresh"),
  supportStatusRows: $("#supportStatusRows"),
  adminRefresh: $("#adminRefresh"),
  workspaceTitle: $("#workspaceTitle"),
  operationsPanel: $("#operationsPanel"),
  operationsWindowForm: $("#operationsWindowForm"),
  operationsFrom: $("#operationsFrom"),
  operationsTo: $("#operationsTo"),
  operationsMeta: $("#operationsMeta"),
  operationsStats: $("#operationsStats"),
  riskDistribution: $("#riskDistribution"),
  caseDistribution: $("#caseDistribution"),
  caseWorkbench: $("#caseWorkbench"),
  caseWorkbenchState: $("#caseWorkbenchState"),
  riskCaseRows: $("#riskCaseRows"),
  legacyAdminData: $("#legacyAdminData"),
  adminStats: $("#adminStats"),
  queueCount: $("#queueCount"),
  adminReportRows: $("#adminReportRows"),
  excelRows: $("#excelRows"),
  emailRows: $("#emailRows"),
  knowledgeUploadForm: $("#knowledgeUploadForm"),
  knowledgeFile: $("#knowledgeFile"),
  knowledgeUploadState: $("#knowledgeUploadState"),
  detailOverlay: $("#detailOverlay"),
  detailKicker: $("#detailKicker"),
  detailTitle: $("#detailTitle"),
  detailMeta: $("#detailMeta"),
  detailBody: $("#detailBody"),
  closeDetail: $("#closeDetail")
};

const pipeline = [
  ["input", "多模态接入"],
  ["fusion", "情绪融合"],
  ["router", "意图路由"],
  ["rag", "Agentic RAG"],
  ["mcp", "MCP 工具"],
  ["stream", "SSE 输出"]
];

const requiredChatConsents = ["PRIVACY_NOTICE", "SENSITIVE_DATA_PROCESSING"];
const consentVersion = "web-v1";

async function requestAccessTokenRefresh() {
  try {
    const response = await fetch("/api/auth/refresh", {
      method: "POST",
      credentials: "same-origin"
    });
    if (!response.ok) {
      state.accessToken = null;
      return false;
    }
    const tokens = await response.json();
    state.accessToken = tokens.accessToken;
    return true;
  } catch (error) {
    state.accessToken = null;
    return false;
  }
}

function refreshAccessToken() {
  if (accessTokenRefresh) return accessTokenRefresh;
  const refresh = () => requestAccessTokenRefresh();
  accessTokenRefresh = (navigator.locks?.request
    ? navigator.locks.request("multimodalAgent-auth-refresh", refresh)
    : refresh()
  ).finally(() => {
    accessTokenRefresh = null;
  });
  return accessTokenRefresh;
}

async function api(path, options = {}, allowRefresh = true) {
  const headers = { ...(options.headers || {}) };
  if (state.accessToken) headers.Authorization = `Bearer ${state.accessToken}`;
  let response = await fetch(path, { ...options, headers, credentials: "same-origin" });
  if (response.status === 401 && allowRefresh && path !== "/api/auth/refresh") {
    if (await refreshAccessToken()) return api(path, options, false);
  }
  if (!response.ok) {
    const error = new Error(await response.text() || `${response.status} ${response.statusText}`);
    error.status = response.status;
    throw error;
  }
  return response;
}

function tone(element, value) {
  element.classList.remove("ok", "warn", "danger", "active");
  if (value) element.classList.add(value);
}

function setService(text, value) {
  els.serviceState.textContent = text;
  tone(els.serviceState, value);
}

function displayModelName(model) {
  if ((model || "").includes("multimodalAgent-qwen3.5-9b")) {
    return "微调 Qwen3.5-9B";
  }
  return (model || "").includes("multimodalAgent-qwen2.5-7b-ft") ? "微调 Qwen2.5-7B" : (model || "未知模型");
}

function setModel(status) {
  state.modelName = status.model || state.modelName;
  const label = status.realModelEnabled ? `${status.provider} / ${displayModelName(state.modelName)}` : "mock / 离线演示";
  els.modelState.textContent = label;
  els.runtimeModel.textContent = displayModelName(state.modelName);
  tone(els.modelState, status.realModelEnabled ? "ok" : "warn");
}

function setChatConsent(enabled) {
  state.hasChatConsent = enabled;
  const chatDisabled = !enabled;
  [els.messageInput, els.audioInput, els.imageInput, els.videoInput, els.sendButton]
    .forEach((element) => { element.disabled = chatDisabled; });
  document.querySelectorAll("[data-prompt]")
    .forEach((button) => { button.disabled = chatDisabled; });
  els.consentGate.hidden = enabled || !state.accessToken || state.isAdmin;
  if (!enabled && state.accessToken && !state.isAdmin) {
    setSession("需授权", "warn");
  }
}

function openConsentDialog() {
  if (!state.accessToken || state.isAdmin) return;
  const privacyGranted = state.grantedConsentTypes.has("PRIVACY_NOTICE");
  const sensitiveGranted = state.grantedConsentTypes.has("SENSITIVE_DATA_PROCESSING");
  els.privacyConsent.checked = privacyGranted;
  els.privacyConsent.disabled = privacyGranted;
  els.sensitiveConsent.checked = sensitiveGranted;
  els.sensitiveConsent.disabled = sensitiveGranted;
  els.consentState.textContent = "";
  els.consentOverlay.hidden = false;
  (privacyGranted ? els.sensitiveConsent : els.privacyConsent).focus();
}

function closeConsentDialog() {
  els.consentOverlay.hidden = true;
}

async function loadConsentStatus(promptWhenMissing = true) {
  try {
    const response = await api("/api/student/consents");
    const consents = await response.json();
    state.grantedConsentTypes = new Set(
      consents
        .filter((consent) => consent.status === "GRANTED")
        .map((consent) => consent.consentType)
    );
    const hasRequiredConsent = requiredChatConsents
      .every((consentType) => state.grantedConsentTypes.has(consentType));
    if (hasRequiredConsent) {
      setChatConsent(true);
      closeConsentDialog();
      setSession("READY", "ok");
    } else {
      setChatConsent(false);
      els.consentGateText.textContent = "请先阅读隐私声明并确认敏感数据处理授权。";
      if (promptWhenMissing) openConsentDialog();
    }
    return hasRequiredConsent;
  } catch (error) {
    state.grantedConsentTypes = new Set();
    setChatConsent(false);
    els.consentGateText.textContent = "授权状态读取失败，请稍后重试。";
    return false;
  }
}

async function grantRequiredConsents(event) {
  event.preventDefault();
  if (!els.privacyConsent.checked || !els.sensitiveConsent.checked) {
    els.consentState.textContent = "请勾选两项必要授权后继续。";
    return;
  }

  els.grantConsent.disabled = true;
  els.consentState.textContent = "正在记录授权…";
  try {
    for (const consentType of requiredChatConsents) {
      if (state.grantedConsentTypes.has(consentType)) continue;
      await api("/api/student/consents", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ consentType, version: consentVersion })
      });
    }
    const granted = await loadConsentStatus(false);
    if (!granted) throw new Error("consent state was not updated");
  } catch (error) {
    els.consentState.textContent = "授权提交失败，请重试。";
  } finally {
    els.grantConsent.disabled = false;
  }
}

function selectedFiles() {
  return [
    ["audio", "语音", els.audioInput.files?.[0]],
    ["image", "图像", els.imageInput.files?.[0]],
    ["video", "视频", els.videoInput.files?.[0]]
  ].filter(([, , file]) => file);
}

function updateAttachments() {
  const files = selectedFiles();
  els.clearAttachments.hidden = files.length === 0;
  els.attachmentState.textContent = files.length
    ? files.map(([, label, file]) => `${label} / ${file.name}`).join("    ")
    : "暂无附件";
  els.attachmentState.classList.toggle("active", files.length > 0);
}

function clearAttachments() {
  els.audioInput.value = "";
  els.imageInput.value = "";
  els.videoInput.value = "";
  updateAttachments();
}

function renderPipeline(activeKey = "") {
  els.pipelineSteps.innerHTML = "";
  pipeline.forEach(([key, label], index) => {
    const item = document.createElement("div");
    item.className = `pipeline-step ${key === activeKey ? "active" : ""}`;
    item.innerHTML = `<span>${String(index + 1).padStart(2, "0")}</span><strong>${label}</strong>`;
    els.pipelineSteps.append(item);
  });
}

function setSession(text, value) {
  els.sessionBadge.textContent = text;
  tone(els.sessionBadge, value);
}

function addMessage(role, content = "") {
  const card = document.createElement("article");
  card.className = `message-card ${role}`;
  card.dataset.raw = content;
  const label = role === "user" ? "学生输入" : displayModelName(state.modelName);
  card.innerHTML = `<header><span>${label}</span></header><div class="message-content"></div>`;
  card.querySelector(".message-content").textContent = content;
  els.messages.append(card);
  els.messages.scrollTop = els.messages.scrollHeight;
  return card;
}

function updateAssistant(card, text) {
  card.dataset.raw = text;
  card.querySelector(".message-content").textContent = text;
  els.messages.scrollTop = els.messages.scrollHeight;
}

function renderEmptyConversation() {
  els.messages.innerHTML = `
    <section class="empty-state">
      <p class="kicker">Ready</p>
      <h2>从文本、语音、图像或视频开始</h2>
      <p>系统会把输入送入多模态融合链路，再由智能体生成流式回复。</p>
    </section>
  `;
}

function clearEmpty() {
  els.messages.querySelector(".empty-state")?.remove();
}

function startNewSession() {
  state.sessionId = null;
  clearAttachments();
  renderPipeline();
  setSession("READY");
  renderEmptyConversation();
  els.messageInput.focus();
}

function parseSse(buffer, onEvent) {
  const blocks = buffer.split("\n\n");
  const rest = blocks.pop() || "";
  for (const block of blocks) {
    const data = block.split("\n").find((line) => line.startsWith("data:"));
    if (data) onEvent(JSON.parse(data.slice(5)));
  }
  return rest;
}

async function sendChat(event) {
  event.preventDefault();
  if (state.sending || state.isAdmin) return;
  if (!state.hasChatConsent) {
    openConsentDialog();
    return;
  }
  const message = els.messageInput.value.trim();
  const files = selectedFiles();
  if (!message && !files.length) return;

  state.sending = true;
  els.sendButton.disabled = true;
  els.messageInput.value = "";
  clearEmpty();
  setSession("RUNNING", "warn");
  renderPipeline("input");

  const visibleInput = [
    message || "学生上传了多模态内容",
    ...files.map(([, label, file]) => `${label}: ${file.name}`)
  ].join("\n");
  addMessage("user", visibleInput);
  const assistant = addMessage("assistant", "");

  try {
    const response = files.length ? await sendMultimodal(message, files) : await sendText(message);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let output = "";
    renderPipeline(files.length ? "fusion" : "router");

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      buffer = parseSse(buffer, (eventData) => {
        if (eventData.type === "meta") {
          state.sessionId = eventData.sessionId;
          renderPipeline("rag");
        }
        if (eventData.type === "token") {
          output += eventData.content;
          updateAssistant(assistant, output);
          renderPipeline("stream");
        }
        if (eventData.type === "error") {
          output = eventData.content || "模型暂时没有返回内容。";
          updateAssistant(assistant, output);
          renderPipeline("stream");
        }
      });
    }

    if (!output) updateAssistant(assistant, "模型暂时没有返回内容。");
    renderPipeline("mcp");
    setTimeout(() => renderPipeline("stream"), 280);
    setSession("READY", "ok");
    await loadSupportStatuses();
  } catch (error) {
    if (error.message.includes("Required consent has not been granted")) {
      setChatConsent(false);
      openConsentDialog();
      updateAssistant(assistant, "请先完成隐私授权，再继续聊天。");
    } else {
      updateAssistant(assistant, "请求失败，请确认后端和 Ollama 已启动。");
    }
    setSession("FAILED", "danger");
  } finally {
    state.sending = false;
    els.sendButton.disabled = !state.hasChatConsent;
    clearAttachments();
    els.messageInput.focus();
  }
}

function sendText(message) {
  return api("/api/chat/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sessionId: state.sessionId, message })
  });
}

function sendMultimodal(message, files) {
  const body = new FormData();
  body.append("message", message || "学生上传了多模态内容，希望获得支持。");
  if (state.sessionId) body.append("sessionId", state.sessionId);
  files.forEach(([key, , file]) => body.append(key, file));
  return api("/api/chat/multimodal/stream", { method: "POST", body });
}

function formatDate(value) {
  return value ? new Date(value).toLocaleString() : "";
}

function riskTone(risk) {
  if (risk === "HIGH" || risk === "FAILED") return "danger";
  if (risk === "MEDIUM" || risk === "PENDING") return "warn";
  if (risk === "LOW" || risk === "SUCCESS") return "ok";
  return "";
}

const caseStatusLabels = {
  OPEN: "待确认",
  ACKNOWLEDGED: "已确认",
  REFERRED: "已转介",
  IN_PROGRESS: "跟进中",
  RESOLVED: "已解决",
  CLOSED: "已结案"
};

const referralStatusLabels = {
  PENDING: "待接收",
  ACCEPTED: "已接收",
  DECLINED: "已拒绝",
  COMPLETED: "已完成",
  CANCELLED: "已取消"
};

const referralTargetLabels = {
  COUNSELOR: "辅导员",
  PSYCHOLOGY_CENTER: "心理中心",
  EXTERNAL_PROVIDER: "校外机构"
};

const interventionTypeLabels = {
  CHECK_IN: "主动关怀",
  COUNSELING_SESSION: "咨询会谈",
  SAFETY_PLAN: "安全计划",
  FOLLOW_UP: "后续跟进",
  EXTERNAL_REFERRAL: "校外转介",
  OTHER: "其他"
};

const caseTransitions = {
  OPEN: ["ACKNOWLEDGED", "IN_PROGRESS", "CLOSED"],
  ACKNOWLEDGED: ["IN_PROGRESS", "CLOSED"],
  REFERRED: ["IN_PROGRESS", "CLOSED"],
  IN_PROGRESS: ["RESOLVED"],
  RESOLVED: ["IN_PROGRESS", "CLOSED"],
  CLOSED: []
};

const referralTransitions = {
  PENDING: ["ACCEPTED", "DECLINED", "CANCELLED"],
  ACCEPTED: ["COMPLETED", "CANCELLED"],
  DECLINED: [],
  COMPLETED: [],
  CANCELLED: []
};

function caseStatusTone(status) {
  if (status === "OPEN") return "danger";
  if (["ACKNOWLEDGED", "REFERRED", "IN_PROGRESS"].includes(status)) return "warn";
  if (["RESOLVED", "CLOSED"].includes(status)) return "ok";
  return "";
}

function isCaseOverdue(item) {
  return Boolean(
    item.slaDueAt
    && new Date(item.slaDueAt).getTime() < Date.now()
    && !["RESOLVED", "CLOSED"].includes(item.status)
  );
}

function formatLocalInput(value, length) {
  const date = new Date(value);
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, length);
}

function formatDateInput(value = new Date()) {
  return formatLocalInput(value, 10);
}

function formatDateTimeInput(value = new Date()) {
  return formatLocalInput(value, 16);
}

function renderSupportStatuses(statuses) {
  els.supportStatusRows.innerHTML = "";
  if (!statuses.length) {
    els.supportStatusRows.append(emptyRecord("暂无人工支持流程。需要时可以继续通过聊天寻求帮助。"));
    return;
  }
  statuses.forEach((item) => {
    const card = document.createElement("article");
    card.className = "support-status-card";
    card.innerHTML = `
      <div>
        <strong>支持单 #${escapeHtml(item.caseId)}</strong>
        <span class="${caseStatusTone(item.status)}">${escapeHtml(caseStatusLabels[item.status] || item.status)}</span>
      </div>
      <p>${item.hasActiveReferral ? "已安排进一步支持，请留意工作人员联系。" : "支持人员正在按流程跟进。"}</p>
      <small>最近更新：${escapeHtml(formatDate(item.updatedAt))}</small>
    `;
    els.supportStatusRows.append(card);
  });
}

async function loadSupportStatuses() {
  els.supportStatusRows.innerHTML = '<p class="empty-record">读取支持进度中…</p>';
  try {
    const response = await api("/api/student/support-status");
    renderSupportStatuses(await response.json());
  } catch (error) {
    els.supportStatusRows.innerHTML = '<p class="empty-record danger">支持进度暂时无法读取，请稍后重试。</p>';
  }
}

function renderDistribution(container, items, keyName, labelMap) {
  container.innerHTML = "";
  const max = Math.max(1, ...items.map((item) => Number(item.count) || 0));
  items.forEach((item) => {
    const key = item[keyName];
    const count = Number(item.count) || 0;
    const row = document.createElement("div");
    row.className = "distribution-row";
    row.innerHTML = `
      <span>${escapeHtml(labelMap[key] || key)}</span>
      <div><i style="width:${Math.max(count ? 6 : 0, (count / max) * 100)}%"></i></div>
      <strong>${count}</strong>
    `;
    container.append(row);
  });
}

function renderOperationsOverview(data) {
  els.operationsStats.innerHTML = "";
  els.operationsStats.append(
    statCard("在校学生", data.activeStudents, "ok"),
    statCard("超期个案", data.overdueCases, data.overdueCases ? "danger" : "ok"),
    statCard("活动转介", data.activeReferrals, data.activeReferrals ? "warn" : "ok"),
    statCard("超期转介", data.overdueReferrals, data.overdueReferrals ? "danger" : "ok"),
    statCard("窗口内干预", data.interventionsInWindow, "ok")
  );
  els.operationsMeta.textContent = `${formatDate(data.from)} 至 ${formatDate(data.to)} · ${formatDate(data.generatedAt)} 生成`;
  els.operationsFrom.value = formatDateInput(data.from);
  els.operationsTo.value = formatDateInput(new Date(new Date(data.to).getTime() - 1000));
  renderDistribution(els.riskDistribution, data.riskAssessmentsByLevel || [], "riskLevel", {
    NONE: "无风险", LOW: "低风险", MEDIUM: "中风险", HIGH: "高风险"
  });
  renderDistribution(els.caseDistribution, data.casesByStatus || [], "status", caseStatusLabels);
}

function operationsQuery() {
  if (!els.operationsFrom.value || !els.operationsTo.value) return "";
  const from = new Date(`${els.operationsFrom.value}T00:00:00`);
  const selectedEnd = new Date(`${els.operationsTo.value}T23:59:59.999`);
  const to = selectedEnd.getTime() > Date.now() ? new Date() : selectedEnd;
  return `?from=${encodeURIComponent(from.toISOString())}&to=${encodeURIComponent(to.toISOString())}`;
}

async function loadOperationsOverview(useWindow = false) {
  els.operationsMeta.textContent = "读取运营数据中…";
  try {
    const response = await api(`/api/admin/operations/overview${useWindow ? operationsQuery() : ""}`);
    renderOperationsOverview(await response.json());
  } catch (error) {
    els.operationsMeta.textContent = error.status === 400
      ? "日期范围无效：请选择最近 365 天内的完整时间段。"
      : "运营总览暂时无法读取，请稍后重试。";
  }
}

function filteredCases() {
  if (state.caseFilter === "ALL") return state.latestCases;
  if (state.caseFilter === "OVERDUE") return state.latestCases.filter(isCaseOverdue);
  return state.latestCases.filter((item) => !["RESOLVED", "CLOSED"].includes(item.status));
}

function renderRiskCases() {
  const cases = filteredCases();
  els.riskCaseRows.innerHTML = "";
  els.caseWorkbenchState.textContent = `共 ${state.latestCases.length} 个可见个案，当前显示 ${cases.length} 个`;
  if (!cases.length) {
    els.riskCaseRows.append(emptyRecord("当前筛选条件下没有个案。"));
    return;
  }
  cases.forEach((item) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `case-card ${isCaseOverdue(item) ? "overdue" : ""}`;
    button.innerHTML = `
      <header>
        <div><small>CASE ${escapeHtml(item.id)}</small><strong>${escapeHtml(item.studentUsername)}</strong></div>
        <span class="${caseStatusTone(item.status)}">${escapeHtml(caseStatusLabels[item.status] || item.status)}</span>
      </header>
      <p>${escapeHtml(item.openingReason || "待人工确认风险情况")}</p>
      <footer>
        <span class="${riskTone(item.riskLevel)}">${escapeHtml(item.riskLevel)}</span>
        <time>${isCaseOverdue(item) ? "已超期 · " : "SLA · "}${escapeHtml(formatDate(item.slaDueAt))}</time>
      </footer>
    `;
    button.addEventListener("click", () => openRiskCase(item.id));
    els.riskCaseRows.append(button);
  });
}

async function loadRiskCases() {
  els.caseWorkbenchState.textContent = "读取个案中…";
  try {
    const response = await api("/api/admin/risk-cases");
    state.latestCases = await response.json();
    renderRiskCases();
  } catch (error) {
    state.latestCases = [];
    els.riskCaseRows.innerHTML = "";
    els.caseWorkbenchState.textContent = "个案队列暂时无法读取，请确认账号的数据范围。";
  }
}

function detailSection(title) {
  const section = document.createElement("section");
  section.className = "workflow-section";
  const heading = document.createElement("h3");
  heading.textContent = title;
  section.append(heading);
  return section;
}

function workflowNotice(message, kind = "danger") {
  const notice = document.createElement("p");
  notice.className = `workflow-notice ${kind}`;
  notice.textContent = message;
  els.detailBody.prepend(notice);
  return notice;
}

async function refreshRiskCaseDetail(caseId) {
  await Promise.all([
    loadRiskCases(),
    state.capabilities.viewOperations ? loadOperationsOverview() : Promise.resolve()
  ]);
  await openRiskCase(caseId);
}

async function updateRiskCaseStatus(item, status, button) {
  button.disabled = true;
  try {
    await api(`/api/admin/risk-cases/${item.id}/status`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ status, expectedVersion: item.version })
    });
    await refreshRiskCaseDetail(item.id);
  } catch (error) {
    workflowNotice(error.status === 409
      ? "个案已被其他工作人员更新，已保留当前页面，请刷新后重试。"
      : "个案状态更新失败，请稍后重试。");
    button.disabled = false;
  }
}

function renderCaseSummary(item) {
  const section = detailSection("个案概况");
  const grid = document.createElement("div");
  grid.className = "workflow-summary";
  [
    ["学生", item.studentUsername],
    ["风险等级", item.riskLevel],
    ["当前状态", caseStatusLabels[item.status] || item.status],
    ["建档来源", item.source],
    ["响应时限", formatDate(item.slaDueAt)],
    ["最近更新", formatDate(item.updatedAt)]
  ].forEach(([label, value]) => grid.append(detailRow(label, value)));
  const reason = document.createElement("p");
  reason.className = "workflow-copy";
  reason.textContent = item.openingReason || "暂无建档说明";
  section.append(grid, reason);

  const targets = caseTransitions[item.status] || [];
  if (targets.length) {
    const actions = document.createElement("div");
    actions.className = "workflow-actions";
    targets.forEach((target) => {
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = `转为${caseStatusLabels[target] || target}`;
      if (target === "CLOSED") button.className = "danger-action";
      button.addEventListener("click", () => updateRiskCaseStatus(item, target, button));
      actions.append(button);
    });
    section.append(actions);
  }
  return section;
}

function renderReferralRows(item, referrals, counselorTargets) {
  const section = detailSection("转介记录");
  const list = document.createElement("div");
  list.className = "workflow-list";
  if (!referrals.length) list.append(emptyRecord("暂无转介记录"));
  referrals.forEach((referral) => {
    const card = document.createElement("article");
    card.className = "workflow-card";
    card.innerHTML = `
      <header><strong>${escapeHtml(referralTargetLabels[referral.targetType] || referral.targetType)}</strong><span>${escapeHtml(referralStatusLabels[referral.status] || referral.status)}</span></header>
      <p>${escapeHtml(referral.reason)}</p>
      <small>接收方：${escapeHtml(referral.targetUsername || "机构接收")} · 截止 ${escapeHtml(formatDate(referral.dueAt))}</small>
    `;
    const targets = referralTransitions[referral.status] || [];
    if (targets.length) {
      const actions = document.createElement("div");
      actions.className = "workflow-actions";
      targets.forEach((target) => {
        const button = document.createElement("button");
        button.type = "button";
        button.textContent = referralStatusLabels[target] || target;
        button.addEventListener("click", async () => {
          button.disabled = true;
          try {
            await api(`/api/admin/risk-cases/${item.id}/referrals/${referral.id}/status`, {
              method: "PATCH",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ status: target, expectedVersion: referral.version })
            });
            await refreshRiskCaseDetail(item.id);
          } catch (error) {
            workflowNotice(error.status === 409 ? "转介已发生变化，请刷新后重试。" : "转介状态更新失败。");
            button.disabled = false;
          }
        });
        actions.append(button);
      });
      card.append(actions);
    }
    list.append(card);
  });
  section.append(list);

  if (item.status !== "CLOSED") {
    const form = document.createElement("form");
    form.className = "workflow-form";
    const counselorOption = counselorTargets.length
      ? '<option value="COUNSELOR">辅导员</option>'
      : "";
    const counselorChoices = counselorTargets.map((target) => `
      <option value="${escapeHtml(target.id)}">${escapeHtml(target.displayName || target.username)} · ${escapeHtml(target.username)}</option>
    `).join("");
    form.innerHTML = `
      <h4>新建转介</h4>
      <label>接收类型<select name="targetType">
        <option value="PSYCHOLOGY_CENTER">心理中心</option>
        ${counselorOption}
        <option value="EXTERNAL_PROVIDER">校外机构</option>
      </select></label>
      <label data-target-user hidden>接收辅导员<select name="targetUserId">${counselorChoices}</select></label>
      <label class="wide">转介原因<textarea name="reason" rows="3" maxlength="240" required></textarea></label>
      <label>响应截止时间<input name="dueAt" type="datetime-local"></label>
      <button type="submit">提交转介</button>
    `;
    const type = form.elements.targetType;
    const targetUser = form.querySelector("[data-target-user]");
    const syncTargetUser = () => {
      const needsUser = type.value === "COUNSELOR";
      targetUser.hidden = !needsUser;
      form.elements.targetUserId.required = needsUser;
    };
    type.addEventListener("change", syncTargetUser);
    syncTargetUser();
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      const submit = form.querySelector('button[type="submit"]');
      submit.disabled = true;
      const dueAt = form.elements.dueAt.value;
      try {
        await api(`/api/admin/risk-cases/${item.id}/referrals`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            targetType: type.value,
            targetUserId: type.value === "COUNSELOR"
              ? Number(form.elements.targetUserId.value)
              : null,
            reason: form.elements.reason.value,
            dueAt: dueAt ? new Date(dueAt).toISOString() : null
          })
        });
        await refreshRiskCaseDetail(item.id);
      } catch (error) {
        workflowNotice("转介创建失败，请检查接收对象和填写内容。");
        submit.disabled = false;
      }
    });
    section.append(form);
  }
  return section;
}

function renderInterventionRows(item, interventions) {
  const section = detailSection("干预与跟进");
  const list = document.createElement("div");
  list.className = "workflow-list";
  if (!interventions.length) list.append(emptyRecord("暂无干预记录"));
  interventions.forEach((intervention) => {
    const card = document.createElement("article");
    card.className = "workflow-card";
    card.innerHTML = `
      <header><strong>${escapeHtml(interventionTypeLabels[intervention.type] || intervention.type)}</strong><span>${escapeHtml(formatDate(intervention.occurredAt))}</span></header>
      <p>${escapeHtml(intervention.notes)}</p>
      <small>${escapeHtml(intervention.outcome || "暂无结果记录")}${intervention.followUpAt ? ` · 下次跟进 ${escapeHtml(formatDate(intervention.followUpAt))}` : ""}</small>
    `;
    list.append(card);
  });
  section.append(list);

  if (item.status !== "CLOSED") {
    const form = document.createElement("form");
    form.className = "workflow-form";
    form.innerHTML = `
      <h4>记录干预</h4>
      <label>干预类型<select name="type">
        ${Object.entries(interventionTypeLabels).map(([value, label]) => `<option value="${value}">${label}</option>`).join("")}
      </select></label>
      <label>发生时间<input name="occurredAt" type="datetime-local" required value="${formatDateTimeInput()}"></label>
      <label class="wide">过程记录<textarea name="notes" rows="4" maxlength="4000" required></textarea></label>
      <label class="wide">结果摘要<input name="outcome" maxlength="500"></label>
      <label>下次跟进<input name="followUpAt" type="datetime-local"></label>
      <button type="submit">保存记录</button>
    `;
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      const submit = form.querySelector('button[type="submit"]');
      submit.disabled = true;
      const followUpAt = form.elements.followUpAt.value;
      try {
        await api(`/api/admin/risk-cases/${item.id}/interventions`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            type: form.elements.type.value,
            notes: form.elements.notes.value,
            outcome: form.elements.outcome.value || null,
            occurredAt: new Date(form.elements.occurredAt.value).toISOString(),
            followUpAt: followUpAt ? new Date(followUpAt).toISOString() : null
          })
        });
        await refreshRiskCaseDetail(item.id);
      } catch (error) {
        workflowNotice("干预记录保存失败，请检查填写内容。");
        submit.disabled = false;
      }
    });
    section.append(form);
  }
  return section;
}

async function openRiskCase(caseId) {
  els.detailOverlay.hidden = false;
  els.detailKicker.textContent = `CASE ${caseId}`;
  els.detailTitle.textContent = "风险个案";
  els.detailMeta.textContent = "正在读取最新状态…";
  els.detailBody.innerHTML = '<p class="empty-record">读取个案中…</p>';
  try {
    const [caseResponse, referralsResponse, interventionsResponse, targetsResponse] = await Promise.all([
      api(`/api/admin/risk-cases/${caseId}`),
      api(`/api/admin/risk-cases/${caseId}/referrals`),
      api(`/api/admin/risk-cases/${caseId}/interventions`),
      api("/api/admin/risk-cases/referral-targets")
    ]);
    const [item, referrals, interventions, counselorTargets] = await Promise.all([
      caseResponse.json(), referralsResponse.json(), interventionsResponse.json(), targetsResponse.json()
    ]);
    els.detailTitle.textContent = `${item.studentUsername} 的风险个案`;
    els.detailMeta.textContent = `${caseStatusLabels[item.status] || item.status} · ${item.riskLevel} · 版本 ${item.version}`;
    els.detailBody.innerHTML = "";
    els.detailBody.append(
      renderCaseSummary(item),
      renderReferralRows(item, referrals, counselorTargets),
      renderInterventionRows(item, interventions)
    );
  } catch (error) {
    els.detailBody.innerHTML = '<p class="empty-record danger">个案详情读取失败或当前账号无权访问。</p>';
  }
}

function statCard(label, value, kind) {
  const node = document.createElement("article");
  node.className = `stat-card ${kind || ""}`;
  node.innerHTML = `<strong>${value}</strong><span>${label}</span>`;
  return node;
}

function renderAdminStats(reports, excelRecords, alerts) {
  els.adminStats.innerHTML = "";
  const high = reports.filter((item) => item.riskLevel === "HIGH").length;
  const medium = reports.filter((item) => item.riskLevel === "MEDIUM").length;
  const mailFailed = alerts.filter((item) => item.status === "FAILED").length;
  els.queueCount.textContent = high;
  els.adminStats.append(
    statCard("报告总数", reports.length),
    statCard("高风险", high, "danger"),
    statCard("需关注", medium, "warn"),
    statCard("邮件失败", mailFailed, mailFailed ? "danger" : "ok"),
    statCard("Excel 写入", excelRecords.length, "ok")
  );
}

function emptyRecord(text) {
  const node = document.createElement("p");
  node.className = "empty-record";
  node.textContent = text;
  return node;
}

function recordButton(title, badge, meta, summary, onClick) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "record-card";
  button.innerHTML = `
    <div><strong>${escapeHtml(title)}</strong><span class="${riskTone(badge)}">${escapeHtml(badge || "SKIPPED")}</span></div>
    <small>${escapeHtml(meta || "")}</small>
    <p>${escapeHtml(summary || "无摘要")}</p>
  `;
  button.addEventListener("click", onClick);
  return button;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function renderReportRows(reports) {
  els.adminReportRows.innerHTML = "";
  if (!reports.length) {
    els.adminReportRows.append(emptyRecord("暂无风险记录"));
    return;
  }
  reports.slice(0, 24).forEach((item) => {
    els.adminReportRows.append(recordButton(
      `${item.username} / ${item.emotion}`,
      item.riskLevel,
      `${item.needsRag ? "RAG" : "无 RAG"} · ${formatDate(item.createdAt)}`,
      item.summary,
      () => item.sessionId ? openConversation(item) : openRecord("报告详情", item)
    ));
  });
}

function renderExcelRows(records) {
  els.excelRows.innerHTML = "";
  if (!records.length) {
    els.excelRows.append(emptyRecord("暂无 Excel 记录"));
    return;
  }
  records.slice(0, 24).forEach((item) => {
    els.excelRows.append(recordButton(
      `#${item.reportId} / ${item.username}`,
      item.excelStatus,
      `${item.emotion} · ${item.riskLevel} · ${formatDate(item.createdAt)}`,
      item.summary || item.content,
      () => openRecord("Excel 写入", item)
    ));
  });
}

function renderEmailRows(records) {
  els.emailRows.innerHTML = "";
  if (!records.length) {
    els.emailRows.append(emptyRecord("暂无预警邮件"));
    return;
  }
  records.slice(0, 24).forEach((item) => {
    els.emailRows.append(recordButton(
      `#${item.reportId} / ${item.recipient}`,
      item.status,
      `${item.riskLevel} · ${item.attempts} 次 · ${formatDate(item.updatedAt)}`,
      item.errorMessage || item.summary,
      () => openRecord("邮件预警", item)
    ));
  });
}

function detailRow(label, value) {
  const row = document.createElement("div");
  row.className = "detail-row";
  row.innerHTML = `<span>${escapeHtml(label)}</span><strong>${escapeHtml(value ?? "无")}</strong>`;
  return row;
}

function openRecord(title, record) {
  els.detailOverlay.hidden = false;
  els.detailKicker.textContent = "记录详情";
  els.detailTitle.textContent = title;
  els.detailMeta.textContent = formatDate(record.createdAt || record.updatedAt);
  els.detailBody.innerHTML = "";
  Object.entries(record).forEach(([key, value]) => {
    if (value !== null && value !== undefined && typeof value !== "object") {
      els.detailBody.append(detailRow(key, value));
    }
  });
}

async function openConversation(report) {
  els.detailOverlay.hidden = false;
  els.detailKicker.textContent = `${report.username} / ${report.sessionId}`;
  els.detailTitle.textContent = "完整对话";
  els.detailMeta.textContent = "管理员视图";
  els.detailBody.innerHTML = `<p class="empty-record">读取中...</p>`;
  try {
    const response = await api(`/api/admin/conversations/${encodeURIComponent(report.sessionId)}`);
    const data = await response.json();
    els.detailBody.innerHTML = "";
    data.messages.forEach((message) => {
      const card = document.createElement("article");
      card.className = `conversation-card ${message.role.toLowerCase()}`;
      card.innerHTML = `<header><strong>${message.role}</strong><span>${formatDate(message.createdAt)}</span></header><p>${escapeHtml(message.content)}</p>`;
      els.detailBody.append(card);
    });
  } catch (error) {
    els.detailBody.innerHTML = `<p class="empty-record">读取失败</p>`;
  }
}

function closeDetail() {
  els.detailOverlay.hidden = true;
}

async function loadReports() {
  const response = await api("/api/admin/reports");
  return response.json();
}

async function loadExcelRecords() {
  const response = await api("/api/admin/excel-records");
  return response.json();
}

async function loadAlertRecords() {
  const response = await api("/api/admin/alerts");
  return response.json();
}

async function loadAdminData() {
  els.adminRefresh.disabled = true;
  const tasks = [];
  if (state.capabilities.reviewCases) {
    tasks.push(loadRiskCases());
    tasks.push(Promise.all([loadReports(), loadExcelRecords(), loadAlertRecords()])
      .then(([reports, excelRecords, alerts]) => {
        state.latestReports = reports;
        renderAdminStats(reports, excelRecords, alerts);
        renderReportRows(reports);
        renderExcelRows(excelRecords);
        renderEmailRows(alerts);
      })
      .catch(() => {
        els.adminStats.innerHTML = "";
        els.adminReportRows.innerHTML = '<p class="empty-record danger">评估记录读取失败</p>';
        els.excelRows.innerHTML = '<p class="empty-record danger">数据闭环记录读取失败</p>';
        els.emailRows.innerHTML = '<p class="empty-record danger">预警记录读取失败</p>';
      }));
  }
  if (state.capabilities.viewOperations) tasks.push(loadOperationsOverview());
  await Promise.allSettled(tasks);
  els.adminRefresh.disabled = false;
}

async function uploadKnowledge(event) {
  event.preventDefault();
  const file = els.knowledgeFile.files?.[0];
  if (!file) {
    els.knowledgeUploadState.textContent = "请选择文件";
    return;
  }
  const body = new FormData();
  body.append("file", file);
  els.knowledgeUploadState.textContent = "入库中";
  try {
    const response = await api("/api/admin/knowledge/file", { method: "POST", body });
    const data = await response.json();
    els.knowledgeUploadState.textContent = `${data.source} / ${data.chunks} 个片段`;
    els.knowledgeFile.value = "";
  } catch (error) {
    els.knowledgeUploadState.textContent = "入库失败";
  }
}

function showLoggedOut() {
  state.accessToken = null;
  state.isAdmin = false;
  state.roles = new Set();
  state.capabilities = { reviewCases: false, viewOperations: false, manageKnowledge: false };
  state.latestCases = [];
  state.grantedConsentTypes = new Set();
  setChatConsent(false);
  closeConsentDialog();
  els.loginForm.hidden = false;
  els.loginState.textContent = "Please enter a configured account";
  els.accountPanel.hidden = true;
  els.studentView.hidden = false;
  els.adminView.hidden = true;
  els.supportStatusRows.innerHTML = '<p class="empty-record">登录后查看人工支持进度</p>';
  renderEmptyConversation();
  renderPipeline();
}

function hasRole(role) {
  return state.roles.has(role);
}

function configureCapabilities(profile) {
  state.roles = new Set(profile.roles || []);
  state.capabilities = {
    reviewCases: hasRole("ROLE_COUNSELOR") || hasRole("ROLE_PSYCHOLOGY_CENTER"),
    viewOperations: hasRole("ROLE_SCHOOL_ADMIN"),
    manageKnowledge: hasRole("ROLE_ADMIN")
  };
  return Object.values(state.capabilities).some(Boolean);
}

function workspaceRoleLabel() {
  const labels = [];
  if (hasRole("ROLE_PSYCHOLOGY_CENTER")) labels.push("心理中心");
  if (hasRole("ROLE_COUNSELOR")) labels.push("辅导员");
  if (hasRole("ROLE_SCHOOL_ADMIN")) labels.push("学校管理员");
  if (hasRole("ROLE_ADMIN")) labels.push("系统管理员");
  return labels.join(" / ") || "工作人员";
}

async function loadProfile() {
  const response = await api("/api/auth/me");
  const profile = await response.json();
  const isStaff = configureCapabilities(profile);
  state.isAdmin = isStaff;
  const accountName = isStaff ? (profile.displayName || profile.username) : profile.username;
  els.loginForm.hidden = true;
  els.accountPanel.hidden = false;
  els.activeAccount.textContent = accountName;
  els.activeRole.textContent = isStaff ? workspaceRoleLabel() : "学生账号";

  if (isStaff) {
    setChatConsent(false);
    closeConsentDialog();
    els.studentView.hidden = true;
    els.adminView.hidden = false;
    els.workspaceTitle.textContent = state.capabilities.reviewCases ? "心理支持工作台" : "学校运营工作台";
    els.operationsPanel.hidden = !state.capabilities.viewOperations;
    els.caseWorkbench.hidden = !state.capabilities.reviewCases;
    els.legacyAdminData.hidden = !state.capabilities.reviewCases;
    els.knowledgeUploadForm.hidden = !state.capabilities.manageKnowledge;
    await loadAdminData();
  } else {
    els.studentView.hidden = false;
    els.adminView.hidden = true;
    els.profileText.textContent = profile.username;
    await Promise.all([loadConsentStatus(), loadSupportStatuses()]);
  }
  els.loginState.textContent = "登录成功";
}

async function loadAgentStatus() {
  const response = await api("/api/agent/status");
  setModel(await response.json());
}

async function checkHealth() {
  try {
    const response = await fetch("/api/health", { cache: "no-store" });
    if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
    const body = await response.json();
    const status = typeof body.status === "string" ? body.status : "UNKNOWN";
    setService(status === "UP" ? "服务正常" : `服务 ${status}`, status === "UP" ? "ok" : "warn");
  } catch (error) {
    setService("服务不可用", "danger");
  }
}

async function login(event) {
  event?.preventDefault();
  try {
    const response = await fetch("/api/auth/login", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username: els.username.value.trim(),
        password: els.password.value
      })
    });
    if (!response.ok) throw new Error("login failed");
    const tokens = await response.json();
    state.accessToken = tokens.accessToken;
    els.password.value = "";
    await loadProfile();
    await loadAgentStatus();
  } catch (error) {
    showLoggedOut();
    els.password.value = "";
    els.loginState.textContent = "账号或密码错误";
  }
}

els.loginForm.addEventListener("submit", login);
els.switchAccount.addEventListener("click", async () => {
  try {
    await api("/api/auth/logout", { method: "POST" });
  } catch (error) {
    // Clearing the local token still leaves the UI in a safe logged-out state.
  }
  showLoggedOut();
  els.username.focus();
});
els.chatForm.addEventListener("submit", sendChat);
els.consentForm.addEventListener("submit", grantRequiredConsents);
els.reviewConsent.addEventListener("click", openConsentDialog);
els.declineConsent.addEventListener("click", () => {
  closeConsentDialog();
  els.consentGateText.textContent = "尚未授权，聊天功能保持关闭。";
});
els.audioInput.addEventListener("change", updateAttachments);
els.imageInput.addEventListener("change", updateAttachments);
els.videoInput.addEventListener("change", updateAttachments);
els.clearAttachments.addEventListener("click", clearAttachments);
els.newSessionButton.addEventListener("click", startNewSession);
els.adminRefresh.addEventListener("click", loadAdminData);
els.supportRefresh.addEventListener("click", loadSupportStatuses);
els.operationsWindowForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  await loadOperationsOverview(true);
});
document.querySelectorAll("[data-case-filter]").forEach((button) => {
  button.addEventListener("click", () => {
    state.caseFilter = button.dataset.caseFilter;
    document.querySelectorAll("[data-case-filter]").forEach((item) => {
      item.classList.toggle("active", item === button);
    });
    renderRiskCases();
  });
});
els.knowledgeUploadForm.addEventListener("submit", uploadKnowledge);
els.closeDetail.addEventListener("click", closeDetail);
els.detailOverlay.addEventListener("click", (event) => {
  if (event.target === els.detailOverlay) closeDetail();
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && !els.detailOverlay.hidden) closeDetail();
  if (event.key === "Escape" && !els.consentOverlay.hidden) closeConsentDialog();
});
document.addEventListener("click", (event) => {
  const prompt = event.target.closest("[data-prompt]");
  if (prompt && !state.isAdmin) {
    if (!state.hasChatConsent) {
      openConsentDialog();
      return;
    }
    els.messageInput.value = prompt.dataset.prompt;
    els.messageInput.focus();
  }
});

async function restoreSession() {
  if (!await refreshAccessToken()) return;
  try {
    await loadProfile();
    await loadAgentStatus();
  } catch (error) {
    showLoggedOut();
  }
}

checkHealth();
restoreSession();
setChatConsent(false);
renderPipeline();
renderEmptyConversation();
