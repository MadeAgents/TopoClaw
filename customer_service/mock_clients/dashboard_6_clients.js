// Copyright 2025 OPPO

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

//     http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

(() => {
  const DEVICES = [
    {
      id: "a-mobile",
      title: "用户A - 手机",
      imei: "user-a-imei",
      deviceType: "mobile",
      allowAgent: true,
      canCreateAgent: true,
      relayImeiId: "000",
      topoclawPort: 18790,
    },
    {
      id: "a-pc",
      title: "用户A - 电脑",
      imei: "user-a-imei",
      deviceType: "pc",
      allowAgent: true,
      canCreateAgent: true,
      relayImeiId: "000",
      topoclawPort: 18790,
    },
    {
      id: "b-mobile",
      title: "用户B - 手机",
      imei: "user-b-imei",
      deviceType: "mobile",
      allowAgent: true,
      canCreateAgent: true,
      relayImeiId: "001",
      topoclawPort: 1879,
    },
    {
      id: "b-pc",
      title: "用户B - 电脑",
      imei: "user-b-imei",
      deviceType: "pc",
      allowAgent: true,
      canCreateAgent: true,
      relayImeiId: "001",
      topoclawPort: 1879,
    },
    {
      id: "c-mobile",
      title: "用户C - 手机",
      imei: "user-c-imei",
      deviceType: "mobile",
      allowAgent: true,
      canCreateAgent: false,
      relayImeiId: "000",
      topoclawPort: 18790,
    },
    {
      id: "d-mobile",
      title: "用户D - 手机",
      imei: "user-d-imei",
      deviceType: "mobile",
      allowAgent: true,
      canCreateAgent: false,
      relayImeiId: "000",
      topoclawPort: 18790,
    },
  ];

  const deviceStates = new Map();

  function rid(prefix) {
    return `${prefix}-${Math.random().toString(16).slice(2, 10)}`;
  }

  function now() {
    return new Date().toLocaleTimeString();
  }

  function wsBaseFromHttp(httpBase) {
    return httpBase.replace(/^http:\/\//, "ws://").replace(/^https:\/\//, "wss://");
  }

  /** 当前卡片 IMEI：以输入框为准，空则回退为设备默认（DEVICES 配置）。 */
  function currentDeviceImei(state) {
    const v = (state.refs?.imeiInput?.value ?? "").trim();
    return v || state.imeiDefault;
  }

  /** 与 customer_service /ws 查询参数 imei_id 及 register 帧一致；优先输入框，否则设备默认（B=001）。 */
  function currentRelayImeiId(state) {
    const fromInput = (state.refs?.relayImeiIdInput?.value || "").trim();
    if (fromInput) return fromInput;
    const fallback = String(state.relayImeiId || "").trim();
    if (fallback) return fallback;
    return "000";
  }

  async function requestJson(url, method = "GET", payload = null) {
    const options = { method, headers: {} };
    if (payload) {
      options.headers["Content-Type"] = "application/json";
      options.body = JSON.stringify(payload);
    }
    const response = await fetch(url, options);
    const text = await response.text();
    let body = {};
    try {
      body = text ? JSON.parse(text) : {};
    } catch (_) {
      body = { raw: text };
    }
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText}: ${JSON.stringify(body)}`);
    }
    return body;
  }

  function appendLine(state, line) {
    const el = state.refs.chatLog;
    const full = `[${now()}] ${line}`;
    el.textContent = `${el.textContent}${full}\n`;
    el.scrollTop = el.scrollHeight;
  }

  function setStatus(state, text, statusClass = "") {
    const badge = state.refs.statusBadge;
    badge.textContent = text;
    badge.classList.remove("status-ok", "status-error");
    if (statusClass) badge.classList.add(statusClass);
  }

  function renderSelect(select, options, valueKey, labelFn) {
    select.innerHTML = "";
    if (!options.length) {
      const emptyOpt = document.createElement("option");
      emptyOpt.value = "";
      emptyOpt.textContent = "暂无数据";
      select.appendChild(emptyOpt);
      return;
    }
    for (const item of options) {
      const opt = document.createElement("option");
      opt.value = String(item[valueKey] || "");
      opt.textContent = labelFn(item);
      select.appendChild(opt);
    }
  }

  function checkedMemberKeys(container) {
    if (!container) return [];
    return Array.from(container.querySelectorAll('input[type="checkbox"][data-member-key]:checked'))
      .map((el) => String(el.getAttribute("data-member-key") || "").trim())
      .filter(Boolean);
  }

  function fillMemberCheckboxList(container, members, checkedKeys) {
    const checked = new Set(checkedKeys);
    container.innerHTML = "";
    if (!members.length) {
      const hint = document.createElement("div");
      hint.className = "member-checkbox-empty";
      hint.style.fontSize = "12px";
      hint.style.color = "#8899a8";
      hint.textContent = "暂无可选成员，请先在本卡片点「刷新列表」加载好友与 Agent。";
      container.appendChild(hint);
      return;
    }
    for (const m of members) {
      const label = document.createElement("label");
      const input = document.createElement("input");
      input.type = "checkbox";
      input.setAttribute("data-member-key", m.key);
      input.checked = checked.has(m.key);
      label.appendChild(input);
      label.appendChild(document.createTextNode(` ${m.label} (${m.id})`));
      container.appendChild(label);
    }
  }

  function refreshSelectableMemberOptions(state) {
    const members = [];
    for (const f of state.friends) {
      const imei = String(f.imei || "").trim();
      if (!imei) continue;
      members.push({
        key: `friend:${imei}`,
        kind: "friend",
        id: imei,
        label: `好友: ${f.nickname || imei}`,
      });
    }
    for (const a of state.agents) {
      const aid = String(a.id || "").trim();
      if (!aid) continue;
      members.push({
        key: `agent:${aid}`,
        kind: "agent",
        id: aid,
        label: `Agent: ${a.name || aid}`,
      });
    }

    state.selectableMembers = members;
    const createKept = checkedMemberKeys(state.refs.createGroupMemberCheckboxes);
    const inviteKept = checkedMemberKeys(state.refs.inviteMemberCheckboxes);
    fillMemberCheckboxList(state.refs.createGroupMemberCheckboxes, members, createKept);
    fillMemberCheckboxList(state.refs.inviteMemberCheckboxes, members, inviteKept);
  }

  function nicknameForImei(state, imei) {
    const im = String(imei || "").trim();
    const f = state.friends.find((x) => String(x.imei || "").trim() === im);
    return f ? String(f.nickname || im) : im;
  }

  function renderGroupMembersSummary(state) {
    const el = state.refs.groupDetailBody;
    const groupId = (state.refs.groupSelect.value || "").trim();
    if (!groupId) {
      el.textContent = "请选择群组";
      return;
    }
    const g = state.groups.find((x) => String(x.group_id || "") === groupId);
    if (!g) {
      el.textContent = "本地无该群数据，请点击「刷新群组」。";
      return;
    }
    const members = Array.isArray(g.members) ? g.members : [];
    const creator = String(g.creator_imei || "").trim();
    const humanLines = members.map((imei) => {
      const im = String(imei || "").trim();
      const tag = im && im === creator ? "（群主）" : "";
      return `· ${nicknameForImei(state, im)}  ${im}${tag}`;
    });
    const assistants = Array.isArray(g.assistants) ? g.assistants : [];
    const configs = g.assistant_configs && typeof g.assistant_configs === "object" ? g.assistant_configs : {};
    const asstLines = assistants.map((aid) => {
      const id = String(aid || "").trim() || "(unknown)";
      const cfg = configs[id] || {};
      const name = cfg.name || id;
      return `· ${name}  [小助手: ${id}]`;
    });
    const parts = [];
    parts.push(humanLines.length ? `【用户】\n${humanLines.join("\n")}` : "【用户】\n（无）");
    parts.push(asstLines.length ? `【群内小助手】\n${asstLines.join("\n")}` : "【群内小助手】\n（无）");
    el.textContent = parts.join("\n\n");
  }

  async function refreshFriends(state) {
    try {
      // 1. 获取好友列表
      const res = await requestJson(`${state.httpBase}/api/friends/list?imei=${encodeURIComponent(currentDeviceImei(state))}`);
      const friends = Array.isArray(res.friends) ? res.friends : [];
      
      // 2. 获取用户创建的 agent 列表
      let agents = [];
      try {
        const agentRes = await requestJson(`${state.httpBase}/api/custom-assistants?imei=${encodeURIComponent(currentDeviceImei(state))}`);
        agents = Array.isArray(agentRes.assistants) ? agentRes.assistants : [];
      } catch (e) {
        // 忽略 agent 列表获取失败
      }
      
      // 3. 合并好友和 agent（agent 作为特殊的好友）
      const combinedFriends = [
        ...friends.map(f => ({ ...f, _type: 'friend', _value: f.imei, _label: `${f.nickname || f.imei} (${f.imei})` })),
        ...agents.map(a => ({ 
          ...a, 
          _type: 'agent', 
          _value: a.id, 
          _label: `[Agent] ${a.name || a.id} (${a.id})`,
          imei: a.id // 用于 select 的 value
        }))
      ];
      
      state.friends = combinedFriends;
      state.rawFriends = friends; // 保存原始好友列表用于其他用途
      state.rawAgents = agents;   // 保存原始 agent 列表
      
      renderSelect(
        state.refs.friendSelect,
        combinedFriends,
        "imei",
        (f) => f._label || `${f.nickname || f.imei} (${f.imei})`
      );
      refreshSelectableMemberOptions(state);
      appendLine(state, `好友列表已刷新: ${friends.length} 个好友, ${agents.length} 个Agent`);
    } catch (error) {
      appendLine(state, `刷新好友失败: ${error.message || error}`);
    }
  }

  async function refreshGroups(state) {
    try {
      const res = await requestJson(`${state.httpBase}/api/groups/list?imei=${encodeURIComponent(currentDeviceImei(state))}`);
      const groups = Array.isArray(res.groups) ? res.groups : [];
      state.groups = groups;
      renderSelect(
        state.refs.groupSelect,
        groups,
        "group_id",
        (g) => `${g.name || g.group_id} (${g.group_id})`
      );
      renderGroupMembersSummary(state);
      await refreshGroupAssistantOptions(state);
      appendLine(state, `群组列表已刷新: ${groups.length} 个`);
    } catch (error) {
      appendLine(state, `刷新群组失败: ${error.message || error}`);
    }
  }

  async function refreshGroupAssistantOptions(state) {
    const groupId = (state.refs.groupSelect.value || "").trim();
    const defaultOptions = [{ id: "assistant", name: "默认小助手 (assistant)" }];
    if (!groupId) {
      state.groupAssistants = defaultOptions;
      renderSelect(
        state.refs.mentionAssistantSelect,
        defaultOptions,
        "id",
        (a) => `${a.name || a.id} (${a.id})`
      );
      renderGroupMembersSummary(state);
      return;
    }
    try {
      const out = await requestJson(`${state.httpBase}/api/groups/${encodeURIComponent(groupId)}`);
      const group = out.group || {};
      const ids = Array.isArray(group.assistants) ? group.assistants : [];
      const configs = group.assistant_configs || {};
      const options = ids.map((id) => {
        const cfg = configs[id] || {};
        return { id, name: cfg.name || id };
      });
      const merged = [];
      const seen = new Set();
      for (const row of [...defaultOptions, ...options]) {
        const k = String(row.id || "");
        if (seen.has(k)) continue;
        seen.add(k);
        merged.push(row);
      }
      state.groupAssistants = merged.length ? merged : defaultOptions;
      renderSelect(
        state.refs.mentionAssistantSelect,
        state.groupAssistants,
        "id",
        (a) => `${a.name || a.id} (${a.id})`
      );
    } catch (_) {
      state.groupAssistants = defaultOptions;
      renderSelect(
        state.refs.mentionAssistantSelect,
        defaultOptions,
        "id",
        (a) => `${a.name || a.id} (${a.id})`
      );
    }
  }

  async function refreshAgents(state) {
    try {
      const res = await requestJson(
        `${state.httpBase}/api/custom-assistants?imei=${encodeURIComponent(currentDeviceImei(state))}`
      );
      const assistants = Array.isArray(res.assistants) ? res.assistants : [];
      state.agents = assistants;
      renderSelect(
        state.refs.customAgentSelect,
        assistants,
        "id",
        (a) => `${a.name || a.id} (${a.id})`
      );
      refreshSelectableMemberOptions(state);
      if (assistants.length > 0) {
        state.refs.agentIdInput.value = assistants[0].id || state.refs.agentIdInput.value;
      }
      appendLine(state, `已创建Agent列表刷新完成: ${assistants.length} 个`);
    } catch (error) {
      appendLine(state, `刷新Agent列表失败: ${error.message || error}`);
    }
  }

  async function refreshAllLists(state) {
    const tasks = [refreshFriends(state), refreshGroups(state), refreshAgents(state)];
    await Promise.all(tasks);
    refreshSelectableMemberOptions(state);
  }

  function chatWsUrl(state) {
    const wsBase = wsBaseFromHttp(state.httpBase);
    const q = state.deviceType === "pc" ? "?device=pc" : "";
    return `${wsBase}/ws/customer-service/${encodeURIComponent(currentDeviceImei(state))}${q}`;
  }

  async function connectChatWs(state) {
    if (state.chatWs && state.chatWs.readyState === WebSocket.OPEN) {
      return;
    }
    await registerProfile(state);
    const url = chatWsUrl(state);
    const ws = new WebSocket(url);
    state.chatWs = ws;
    setStatus(state, "连接中...");
    ws.onopen = () => {
      setStatus(state, "聊天WS在线", "status-ok");
      appendLine(state, `聊天WS连接成功: ${url}`);
    };
    ws.onclose = (event) => {
      setStatus(state, "聊天WS离线");
      appendLine(state, `聊天WS断开: code=${event.code} reason=${event.reason || "-"}`);
    };
    ws.onerror = () => {
      setStatus(state, "聊天WS错误", "status-error");
      appendLine(state, "聊天WS异常");
    };
    ws.onmessage = (event) => {
      handleChatIncoming(state, event.data);
    };
  }

  function agentWsUrl(state) {
    const wsBase = wsBaseFromHttp(state.httpBase);
    const params = new URLSearchParams();
    params.set("imei", currentDeviceImei(state));
    params.set("imei_id", currentRelayImeiId(state));
    return `${wsBase}/ws?${params.toString()}`;
  }

  function connectAgentWs(state) {
    if (!state.allowAgent) {
      appendLine(state, "当前设备未启用 Agent 功能");
      return;
    }
    if (state.agentWs && state.agentWs.readyState === WebSocket.OPEN) {
      return;
    }
    const url = agentWsUrl(state);
    const ws = new WebSocket(url);
    state.agentWs = ws;
    appendLine(state, `Agent WS连接中: ${url}`);
    ws.onopen = () => {
      appendLine(state, "Agent WS连接成功");
      const payload = {
        type: "register",
        thread_id: "",
        device_id: `${state.id}-dashboard`,
        device_type: state.deviceType,
        imei: currentDeviceImei(state),
        imei_id: currentRelayImeiId(state),
      };
      ws.send(JSON.stringify(payload));
      appendLine(state, `Agent => ${JSON.stringify(payload)}`);
    };
    ws.onclose = (event) => {
      appendLine(state, `Agent WS断开: code=${event.code} reason=${event.reason || "-"}`);
    };
    ws.onerror = () => {
      appendLine(state, "Agent WS异常");
    };
    ws.onmessage = (event) => {
      appendLine(state, `Agent <= ${event.data}`);
    };
  }

  function sendViaChatWs(state, payload) {
    if (!state.chatWs || state.chatWs.readyState !== WebSocket.OPEN) {
      appendLine(state, "聊天WS未连接，无法发送");
      return false;
    }
    state.chatWs.send(JSON.stringify(payload));
    appendLine(state, `发送 => ${JSON.stringify(payload)}`);
    return true;
  }

  function handleChatIncoming(state, rawData) {
    let msg = null;
    try {
      msg = JSON.parse(rawData);
    } catch (_) {
      appendLine(state, `收到文本: ${rawData}`);
      return;
    }

    if (msg.type === "offline_messages" && Array.isArray(msg.messages)) {
      appendLine(state, `收到离线消息 ${msg.messages.length} 条`);
      for (const m of msg.messages) {
        appendLine(state, formatMessageLine(m));
      }
      return;
    }

    appendLine(state, formatMessageLine(msg));
  }

  function formatMessageLine(msg) {
    const type = msg.type || "unknown";
    if (type === "friend_message" || type === "friend_sync_message") {
      const sender = msg.senderImei || msg.sender_imei || msg.sender || "friend";
      return `[好友消息] ${sender}: ${msg.content || ""}`;
    }
    if (type === "group_message") {
      const sender = msg.senderImei || msg.sender || "group";
      return `[群消息 ${msg.groupId || "-"}] ${sender}: ${msg.content || ""}`;
    }
    if (type === "friend_message_ack") {
      return `[发送确认] message_id=${msg.message_id || "-"} target_online=${msg.target_online}`;
    }
    if (type === "error" || type === "friend_message_error") {
      return `[错误] ${msg.content || JSON.stringify(msg)}`;
    }
    if (type === "pong") {
      return "[心跳] pong";
    }
    return `[${type}] ${msg.content || JSON.stringify(msg)}`;
  }

  async function addFriend(state) {
    const peer = state.refs.addFriendInput.value.trim();
    if (!peer) return;
    if (peer === currentDeviceImei(state)) {
      appendLine(state, "不能添加自己为好友");
      return;
    }
    try {
      const out = await requestJson(`${state.httpBase}/api/friends/add`, "POST", {
        imei: currentDeviceImei(state),
        friendImei: peer,
      });
      appendLine(state, `添加好友完成: ${JSON.stringify(out)}`);
      await refreshFriends(state);
    } catch (error) {
      appendLine(state, `添加好友失败: ${error.message || error}`);
    }
  }

  async function createGroup(state) {
    const name = state.refs.createGroupNameInput.value.trim();
    const selected = checkedMemberKeys(state.refs.createGroupMemberCheckboxes);
    const memberImeis = [];
    const agentIds = [];
    for (const key of selected) {
      const item = state.selectableMembers.find((x) => x.key === key);
      if (!item) continue;
      if (item.kind === "friend") memberImeis.push(item.id);
      if (item.kind === "agent") agentIds.push(item.id);
    }
    const members = Array.from(new Set(memberImeis)).filter((imei) => imei !== currentDeviceImei(state));
    if (!name) {
      appendLine(state, "请先输入群组名称");
      return;
    }
    try {
      const out = await requestJson(`${state.httpBase}/api/groups/create`, "POST", {
        imei: currentDeviceImei(state),
        name,
        memberImeis: members,
        assistantEnabled: true,
      });
      appendLine(
        state,
        `创建群组完成: 成员=${members.length + 1}(含自己), groupId=${out.groupId || "-"}`
      );
      if (out.groupId && agentIds.length) {
        for (const assistantId of agentIds) {
          const agent = state.agents.find((a) => String(a.id) === assistantId);
          if (!agent) continue;
          try {
            await requestJson(`${state.httpBase}/api/groups/add-assistant`, "POST", {
              groupId: out.groupId,
              imei: currentDeviceImei(state),
              assistantId,
              baseUrl: agent.baseUrl || "",
              name: agent.name || assistantId,
              capabilities: Array.isArray(agent.capabilities) ? agent.capabilities : ["chat"],
              intro: agent.intro || "",
              avatar: agent.avatar || "",
              multiSession:
                typeof agent.multiSessionEnabled === "boolean" ? agent.multiSessionEnabled : true,
              displayId: agent.displayId || "",
            });
            appendLine(state, `创建时已拉入Agent: ${assistantId}`);
          } catch (error) {
            appendLine(state, `创建时拉入Agent失败[${assistantId}]: ${error.message || error}`);
          }
        }
      }
      await refreshGroups(state);
      if (out.groupId) state.refs.groupSelect.value = out.groupId;
      await refreshGroupAssistantOptions(state);
    } catch (error) {
      appendLine(state, `创建群组失败: ${error.message || error}`);
    }
  }

  async function inviteMembersToGroup(state) {
    const groupId = state.refs.groupSelect.value.trim();
    const selected = checkedMemberKeys(state.refs.inviteMemberCheckboxes);
    if (!groupId) {
      appendLine(state, "请先选择群组");
      return;
    }
    if (!selected.length) {
      appendLine(state, "请先选择要邀请的成员");
      return;
    }
    for (const key of selected) {
      const item = state.selectableMembers.find((x) => x.key === key);
      if (!item) continue;
      try {
        if (item.kind === "friend") {
          const out = await requestJson(`${state.httpBase}/api/groups/add-member`, "POST", {
            groupId,
            imei: currentDeviceImei(state),
            memberImei: item.id,
          });
          appendLine(state, `邀请好友进群: ${item.id} -> ${JSON.stringify(out)}`);
        } else if (item.kind === "agent") {
          const agent = state.agents.find((a) => String(a.id) === item.id);
          if (!agent) {
            appendLine(state, `邀请Agent失败[${item.id}]：未找到Agent配置`);
            continue;
          }
          const out = await requestJson(`${state.httpBase}/api/groups/add-assistant`, "POST", {
            groupId,
            imei: currentDeviceImei(state),
            assistantId: item.id,
            baseUrl: agent.baseUrl || "",
            name: agent.name || item.id,
            capabilities: Array.isArray(agent.capabilities) ? agent.capabilities : ["chat"],
            intro: agent.intro || "",
            avatar: agent.avatar || "",
            multiSession:
              typeof agent.multiSessionEnabled === "boolean" ? agent.multiSessionEnabled : true,
            displayId: agent.displayId || "",
          });
          appendLine(state, `邀请Agent进群: ${item.id} -> ${JSON.stringify(out)}`);
        }
      } catch (error) {
        appendLine(state, `邀请失败[${item.id}]: ${error.message || error}`);
      }
    }
    await refreshGroups(state);
    await refreshGroupAssistantOptions(state);
  }

  function sendFriendMessage(state) {
    const targetValue = state.refs.friendSelect.value.trim();
    const content = state.refs.friendMessageInput.value.trim();
    if (!targetValue || !content) {
      appendLine(state, "请选择好友并输入消息内容");
      return;
    }
    
    // 判断选择的是好友还是 agent
    const selectedItem = state.friends.find(f => f.imei === targetValue || f.id === targetValue);
    const isAgent = selectedItem && selectedItem._type === 'agent';
    
    if (isAgent) {
      // 发送给 Agent - 使用私聊链路（Agent WS）
      if (!state.allowAgent) {
        appendLine(state, "当前设备未启用 Agent 功能，无法给 Agent 发消息");
        return;
      }
      if (!state.agentWs || state.agentWs.readyState !== WebSocket.OPEN) {
        appendLine(state, "请先连接 Agent WS");
        return;
      }
      
      const agentId = selectedItem.id;
      const threadId = `private-${currentDeviceImei(state)}-${agentId}`; // 私聊 thread_id
      
      const payload = {
        type: "chat",
        request_id: rid("private-chat"),
        agent_id: agentId,
        thread_id: threadId,
        message: content,
        images: [],
        imei_id: currentRelayImeiId(state),
      };
      
      state.agentWs.send(JSON.stringify(payload));
      appendLine(state, `私聊Agent => ${JSON.stringify(payload)}`);
      appendLine(state, `已发送私聊消息给 Agent: ${agentId}`);
    } else {
      // 发送给普通好友 - 使用原有好友消息链路
      const payload = {
        type: "friend_message",
        targetImei: targetValue,
        content,
        message_type: "text",
        message_id: rid("friend"),
      };
      sendViaChatWs(state, payload);
    }
  }

  function sendGroupMessage(state) {
    const groupId = state.refs.groupSelect.value.trim();
    let content = state.refs.groupMessageInput.value.trim();
    if (!groupId || !content) {
      appendLine(state, "请选择群组并输入消息内容");
      return;
    }
    if (state.refs.mentionAssistantToggle.checked && !content.startsWith("@")) {
      const targetAssistant = (state.refs.mentionAssistantSelect.value || "assistant").trim();
      const mentionPrefix = targetAssistant ? `@${targetAssistant} ` : "";
      content = `${mentionPrefix}${content}`.trim();
      state.refs.groupMessageInput.value = content;
    }
    const payload = {
      type: "group_message",
      groupId,
      content,
      message_type: "text",
      message_id: rid("group"),
    };
    sendViaChatWs(state, payload);
  }

  function insertAssistantMention(state) {
    const selected = (state.refs.mentionAssistantSelect.value || "assistant").trim();
    const prefix = selected ? `@${selected} ` : "";
    const raw = state.refs.groupMessageInput.value || "";
    if (!raw.startsWith("@")) {
      state.refs.groupMessageInput.value = `${prefix}${raw}`.trim();
    }
    state.refs.mentionAssistantToggle.checked = true;
  }

  function createAgent(state) {
    if (!state.allowAgent || !state.canCreateAgent) {
      appendLine(state, "当前设备无 Agent 创建权限");
      return;
    }
    const agentId = state.refs.agentIdInput.value.trim();
    const prompt = state.refs.agentPromptInput.value.trim();
    if (!agentId) {
      appendLine(state, "请先输入 agent id");
      return;
    }
    if (!state.agentWs || state.agentWs.readyState !== WebSocket.OPEN) {
      appendLine(state, "请先连接 Agent WS");
      return;
    }
    const payload = {
      type: "create_agent",
      request_id: rid("create-agent"),
      agent_id: agentId,
      default_prompt: prompt || "你是一个助手",
      skills_include: ["summarize"],
      imei_id: currentRelayImeiId(state),
    };
    state.agentWs.send(JSON.stringify(payload));
    appendLine(state, `Agent => ${JSON.stringify(payload)}`);
    setTimeout(() => {
      refreshAgents(state);
    }, 300);
  }

  function deleteAgent(state) {
    if (!state.allowAgent) {
      appendLine(state, "当前设备未启用 Agent 功能");
      return;
    }
    const selectedAgent = state.refs.customAgentSelect.value.trim();
    const agentId = (selectedAgent || state.refs.agentIdInput.value || "").trim();
    if (!agentId) {
      appendLine(state, "请先在下拉或输入框中指定要删除的 agent id");
      return;
    }
    if (agentId.toLowerCase() === "default") {
      appendLine(state, "不能删除内置 default agent");
      return;
    }
    if (!window.confirm(`确定删除 Agent「${agentId}」？本地 TopoClaw 将移除该助手及工作区。`)) {
      return;
    }
    if (!state.agentWs || state.agentWs.readyState !== WebSocket.OPEN) {
      appendLine(state, "请先连接 Agent WS");
      return;
    }
    const payload = {
      type: "delete_agent",
      request_id: rid("delete-agent"),
      agent_id: agentId,
      imei_id: currentRelayImeiId(state),
    };
    state.agentWs.send(JSON.stringify(payload));
    appendLine(state, `Agent => ${JSON.stringify(payload)}`);
    setTimeout(() => {
      refreshAgents(state);
    }, 400);
  }

  function sendAgentChat(state) {
    if (!state.allowAgent) {
      appendLine(state, "当前设备未启用 Agent 功能");
      return;
    }
    const selectedAgent = state.refs.customAgentSelect.value.trim();
    const agentId = (selectedAgent || state.refs.agentIdInput.value || "").trim();
    const threadId = state.refs.agentThreadInput.value.trim();
    const message = state.refs.agentChatInput.value.trim();
    if (!agentId || !threadId || !message) {
      appendLine(state, "请填写 agent id、thread id 和消息内容");
      return;
    }
    if (!state.agentWs || state.agentWs.readyState !== WebSocket.OPEN) {
      appendLine(state, "请先连接 Agent WS");
      return;
    }
    const payload = {
      type: "chat",
      request_id: rid("agent-chat"),
      agent_id: agentId,
      thread_id: threadId,
      message,
      images: [],
      imei_id: currentRelayImeiId(state),
    };
    state.agentWs.send(JSON.stringify(payload));
    appendLine(state, `Agent => ${JSON.stringify(payload)}`);
  }

  async function saveAllAgentsReplySetting(state) {
    const allAgentsReply = state.refs.allAgentsReplyToggle.checked;
    try {
      const res = await fetch(`${state.httpBase}/api/user-settings/all_agents_reply`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          imei: currentDeviceImei(state),
          key: "all_agents_reply",
          value: allAgentsReply,
        }),
      });
      const data = await res.json();
      if (data.success) {
        appendLine(state, `设置已保存: 所有助手回复 = ${allAgentsReply}`);
      } else {
        appendLine(state, `保存设置失败: ${data.detail || "未知错误"}`);
      }
    } catch (e) {
      appendLine(state, `保存设置错误: ${e.message || e}`);
    }
  }

  async function loadUserSettings(state) {
    try {
      const res = await fetch(`${state.httpBase}/api/user-settings?imei=${encodeURIComponent(currentDeviceImei(state))}`);
      const data = await res.json();
      if (data.success && data.settings) {
        state.refs.allAgentsReplyToggle.checked = data.settings.all_agents_reply || false;
        appendLine(state, `用户设置已加载: 所有助手回复 = ${data.settings.all_agents_reply || false}`);
      }
    } catch (e) {
      appendLine(state, `加载设置失败: ${e.message || e}`);
    }
  }

  function bindCardEvents(state) {
    const r = state.refs;
    r.imeiInput.addEventListener("change", () => {
      appendLine(
        state,
        `IMEI 已设为: ${currentDeviceImei(state)}（若聊天/Agent WS 已连接，请断开或重连后使用新身份）`,
      );
    });
    r.btnConnectChat.addEventListener("click", () => {
      connectChatWs(state);
    });
    r.btnRefreshFriends.addEventListener("click", () => refreshFriends(state));
    r.btnRefreshGroups.addEventListener("click", () => refreshGroups(state));
    r.groupSelect.addEventListener("change", () => {
      renderGroupMembersSummary(state);
      refreshGroupAssistantOptions(state);
    });
    r.btnCreateGroup.addEventListener("click", () => createGroup(state));
    r.btnInviteMembers.addEventListener("click", () => inviteMembersToGroup(state));
    r.btnRefreshLists.addEventListener("click", () => refreshAllLists(state));
    r.btnAddFriend.addEventListener("click", () => addFriend(state));
    r.btnSendFriend.addEventListener("click", () => sendFriendMessage(state));
    r.btnSendGroup.addEventListener("click", () => sendGroupMessage(state));
    r.btnInsertAssistantMention.addEventListener("click", () => insertAssistantMention(state));
    r.btnSaveAllAgentsSetting.addEventListener("click", () => saveAllAgentsReplySetting(state));
    r.btnConnectAgent.addEventListener("click", () => connectAgentWs(state));
    
    // 加载用户设置
    loadUserSettings(state);
    r.btnRefreshAgents.addEventListener("click", () => refreshAgents(state));
    r.btnCreateAgent.addEventListener("click", () => createAgent(state));
    r.btnDeleteAgent.addEventListener("click", () => deleteAgent(state));
    r.btnChatAgent.addEventListener("click", () => sendAgentChat(state));
    r.customAgentSelect.addEventListener("change", () => {
      if (r.customAgentSelect.value) r.agentIdInput.value = r.customAgentSelect.value;
    });
  }

  function createCard(device) {
    const tpl = document.getElementById("deviceCardTemplate");
    const node = tpl.content.firstElementChild.cloneNode(true);
    const refs = {
      title: node.querySelector(".device-title"),
      statusBadge: node.querySelector(".status-badge"),
      imeiInput: node.querySelector(".imei-input"),
      deviceTypeValue: node.querySelector(".device-type-value"),
      topoclawPortHint: node.querySelector(".topoclaw-port-hint"),
      relayImeiIdInput: node.querySelector(".relay-imei-id-input"),
      chatLog: node.querySelector(".chat-log"),
      friendSelect: node.querySelector(".friend-select"),
      addFriendInput: node.querySelector(".add-friend-input"),
      groupSelect: node.querySelector(".group-select"),
      groupDetailBody: node.querySelector(".group-detail-body"),
      createGroupNameInput: node.querySelector(".create-group-name-input"),
      createGroupMemberCheckboxes: node.querySelector(".create-group-member-checkboxes"),
      inviteMemberCheckboxes: node.querySelector(".invite-member-checkboxes"),
      friendMessageInput: node.querySelector(".friend-message-input"),
      groupMessageInput: node.querySelector(".group-message-input"),
      mentionAssistantToggle: node.querySelector(".mention-assistant-toggle"),
      mentionAssistantSelect: node.querySelector(".mention-assistant-select"),
      allAgentsReplyToggle: node.querySelector(".all-agents-reply-toggle"),
      btnSaveAllAgentsSetting: node.querySelector(".btn-save-all-agents-setting"),
      btnConnectChat: node.querySelector(".btn-connect-chat"),
      btnRefreshLists: node.querySelector(".btn-refresh-lists"),
      btnRefreshFriends: node.querySelector(".btn-refresh-friends"),
      btnRefreshGroups: node.querySelector(".btn-refresh-groups"),
      btnCreateGroup: node.querySelector(".btn-create-group"),
      btnInviteMembers: node.querySelector(".btn-invite-members"),
      btnAddFriend: node.querySelector(".btn-add-friend"),
      btnSendFriend: node.querySelector(".btn-send-friend"),
      btnSendGroup: node.querySelector(".btn-send-group"),
      btnInsertAssistantMention: node.querySelector(".btn-insert-assistant-mention"),
      customAgentSelect: node.querySelector(".custom-agent-select"),
      btnRefreshAgents: node.querySelector(".btn-refresh-agents"),
      agentSection: node.querySelector(".agent-section"),
      agentIdInput: node.querySelector(".agent-id-input"),
      agentPromptInput: node.querySelector(".agent-prompt-input"),
      agentThreadInput: node.querySelector(".agent-thread-input"),
      agentChatInput: node.querySelector(".agent-chat-input"),
      btnConnectAgent: node.querySelector(".btn-connect-agent"),
      btnCreateAgent: node.querySelector(".btn-create-agent"),
      btnDeleteAgent: node.querySelector(".btn-delete-agent"),
      btnChatAgent: node.querySelector(".btn-chat-agent"),
    };

    refs.title.textContent = device.title;
    refs.imeiInput.value = device.imei;
    refs.deviceTypeValue.textContent = device.deviceType;
    refs.topoclawPortHint.textContent = `:${device.topoclawPort} (node ${device.relayImeiId})`;
    refs.relayImeiIdInput.value = device.relayImeiId;
    refs.friendMessageInput.value = `你好，我是${device.title}`;
    refs.groupMessageInput.value = `群里好，我是${device.title}`;
    refs.createGroupNameInput.value = `${device.title}创建的测试群`;
    refs.agentIdInput.value = `agent-${device.id}`;
    refs.agentPromptInput.value = `你是${device.title}的小助手`;
    refs.agentThreadInput.value = `thread-${device.id}-1`;

    if (!device.allowAgent) {
      refs.agentSection.classList.add("restricted");
      refs.agentSection.querySelector("h3").textContent = "Agent（当前设备禁用）";
      refs.btnConnectAgent.disabled = true;
      refs.btnCreateAgent.disabled = true;
      refs.btnDeleteAgent.disabled = true;
      refs.btnChatAgent.disabled = true;
      refs.btnRefreshAgents.disabled = true;
      refs.customAgentSelect.disabled = true;
      refs.agentIdInput.disabled = true;
      refs.agentPromptInput.disabled = true;
      refs.agentThreadInput.disabled = true;
      refs.agentChatInput.disabled = true;
      refs.relayImeiIdInput.disabled = true;
    } else if (!device.canCreateAgent) {
      refs.agentSection.classList.add("restricted");
      refs.agentSection.querySelector("h3").textContent = "Agent（仅A/B可创建，当前可聊天）";
      refs.btnCreateAgent.disabled = true;
      refs.agentPromptInput.disabled = true;
    }

    const state = {
      ...device,
      imeiDefault: device.imei,
      httpBase: "http://127.0.0.1:8001",
      friends: [],
      groups: [],
      groupAssistants: [],
      agents: [],
      selectableMembers: [],
      chatWs: null,
      agentWs: null,
      refs,
    };

    appendLine(state, `${device.title} 已初始化`);
    refreshSelectableMemberOptions(state);
    bindCardEvents(state);
    deviceStates.set(device.id, state);
    return node;
  }

  async function bootstrapDefaultFriendships() {
    const uniqueImeis = Array.from(
      new Set(Array.from(deviceStates.values()).map((s) => currentDeviceImei(s))),
    ).filter(Boolean);
    for (let i = 0; i < uniqueImeis.length; i += 1) {
      for (let j = i + 1; j < uniqueImeis.length; j += 1) {
        const imeiA = uniqueImeis[i];
        const imeiB = uniqueImeis[j];
        try {
          await requestJson(`${getGlobalHttpBase()}/api/friends/add`, "POST", {
            imei: imeiA,
            friendImei: imeiB,
          });
        } catch (_) {
          // 已是好友或局部失败时，后续刷新时会显示实际状态。
        }
      }
    }
    for (const state of deviceStates.values()) {
      appendLine(state, "默认好友关系初始化已执行");
      await refreshFriends(state);
    }
  }

  async function registerProfile(state) {
    const form = new URLSearchParams();
    form.set("name", state.title);
    form.set("address", `${state.deviceType}-mock`);
    form.set("phone", currentDeviceImei(state));
    try {
      const response = await fetch(`${state.httpBase}/api/profile/${encodeURIComponent(currentDeviceImei(state))}`, {
        method: "POST",
        body: form,
      });
      const text = await response.text();
      if (!response.ok) {
        throw new Error(`${response.status} ${response.statusText}: ${text}`);
      }
      appendLine(state, "profile 注册/更新成功");
    } catch (error) {
      appendLine(state, `profile 注册失败: ${error.message || error}`);
      throw error;
    }
  }

  function getGlobalHttpBase() {
    return document.getElementById("globalHttpBase").value.trim() || "http://127.0.0.1:8001";
  }

  function applyHttpBaseToAll() {
    const base = getGlobalHttpBase();
    for (const state of deviceStates.values()) {
      state.httpBase = base;
      appendLine(state, `HTTP Base 已更新为 ${base}`);
    }
  }

  async function refreshAll() {
    for (const state of deviceStates.values()) {
      await refreshAllLists(state);
    }
  }

  async function connectAllChatWs() {
    await registerAllProfiles();
    for (const state of deviceStates.values()) {
      connectChatWs(state);
    }
  }

  async function registerAllProfiles() {
    for (const state of deviceStates.values()) {
      try {
        await registerProfile(state);
      } catch (_) {
        // 保留单设备日志，不在这里中断全局流程。
      }
    }
  }

  function bindGlobalEvents() {
    document.getElementById("btnApplyHttpBase").addEventListener("click", applyHttpBaseToAll);
    document.getElementById("btnConnectAll").addEventListener("click", () => {
      connectAllChatWs();
    });
    document.getElementById("btnRefreshAll").addEventListener("click", () => {
      refreshAll();
    });
    document.getElementById("btnBootstrapFriends").addEventListener("click", () => {
      bootstrapDefaultFriendships();
    });
  }

  async function init() {
    const grid = document.getElementById("deviceGrid");
    for (const d of DEVICES) {
      grid.appendChild(createCard(d));
    }
    bindGlobalEvents();
    applyHttpBaseToAll();
    await registerAllProfiles();
    await bootstrapDefaultFriendships();
    await refreshAll();
  }

  init();
})();
