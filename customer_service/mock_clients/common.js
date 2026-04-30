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
  function $(id) {
    return document.getElementById(id);
  }

  function now() {
    return new Date().toLocaleTimeString();
  }

  function rid(prefix = "req") {
    return `${prefix}-${Math.random().toString(16).slice(2, 10)}`;
  }

  function wsBaseFromHttp(httpBase) {
    return httpBase.replace(/^http:\/\//, "ws://").replace(/^https:\/\//, "wss://");
  }

  async function postJson(url, payload) {
    const r = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const txt = await r.text();
    let data = {};
    try {
      data = txt ? JSON.parse(txt) : {};
    } catch (_) {
      data = { raw: txt };
    }
    if (!r.ok) throw new Error(`${r.status} ${r.statusText} ${JSON.stringify(data)}`);
    return data;
  }

  function appendLog(el, msg) {
    const line = `[${now()}] ${msg}`;
    el.value += `${line}\n`;
    el.scrollTop = el.scrollHeight;
  }

  function initMockClient(cfg) {
    const state = {
      httpBase: cfg.httpBase || "http://127.0.0.1:8001",
      imei: cfg.imei,
      deviceType: cfg.deviceType, // mobile | pc
      chatWs: null,
      agentWs: null,
      agentBuf: new Map(),
    };

    $("roleTitle").innerText = cfg.title;
    $("imei").value = cfg.imei;
    $("peerImei").value = cfg.defaultPeerImei || "";
    $("defaultMembers").value = cfg.defaultMembers || "";
    $("httpBase").value = state.httpBase;
    $("imeiId").value = cfg.defaultImeiId || "";

    const logEl = $("logs");
    appendLog(logEl, `ready: ${cfg.title}`);

    function chatWsUrl() {
      const base = wsBaseFromHttp($("httpBase").value.trim());
      const imei = $("imei").value.trim();
      const q = state.deviceType === "pc" ? "?device=pc" : "";
      return `${base}/ws/customer-service/${encodeURIComponent(imei)}${q}`;
    }

    function agentWsUrl() {
      const base = wsBaseFromHttp($("httpBase").value.trim());
      const imei = $("imei").value.trim();
      const imeiId = $("imeiId").value.trim();
      const q = new URLSearchParams();
      if (imei) q.set("imei", imei);
      if (imeiId) q.set("imei_id", imeiId);
      const s = q.toString();
      return s ? `${base}/ws?${s}` : `${base}/ws`;
    }

    function connectChat() {
      if (state.chatWs && state.chatWs.readyState === WebSocket.OPEN) return;
      const url = chatWsUrl();
      const ws = new WebSocket(url);
      state.chatWs = ws;
      ws.onopen = () => appendLog(logEl, `chat ws connected: ${url}`);
      ws.onclose = (e) => appendLog(logEl, `chat ws closed: code=${e.code} reason=${e.reason || "-"}`);
      ws.onerror = () => appendLog(logEl, "chat ws error");
      ws.onmessage = (ev) => appendLog(logEl, `chat <= ${ev.data}`);
    }

    function connectAgent() {
      if (state.agentWs && state.agentWs.readyState === WebSocket.OPEN) return;
      const url = agentWsUrl();
      const ws = new WebSocket(url);
      state.agentWs = ws;
      ws.onopen = () => {
        appendLog(logEl, `agent ws connected: ${url}`);
        const reg = {
          type: "register",
          thread_id: "",
          device_id: `${$("imei").value.trim()}-${state.deviceType}-mock`,
          device_type: state.deviceType,
          imei: $("imei").value.trim(),
          imei_id: $("imeiId").value.trim(),
        };
        ws.send(JSON.stringify(reg));
        appendLog(logEl, `agent => ${JSON.stringify(reg)}`);
      };
      ws.onclose = (e) => appendLog(logEl, `agent ws closed: code=${e.code} reason=${e.reason || "-"}`);
      ws.onerror = () => appendLog(logEl, "agent ws error");
      ws.onmessage = (ev) => {
        appendLog(logEl, `agent <= ${ev.data}`);
        let msg = null;
        try {
          msg = JSON.parse(ev.data);
        } catch (_) {
          return;
        }
        const reqId = String(msg.request_id || "");
        if (!reqId) return;
        if (msg.type === "delta") {
          const prev = state.agentBuf.get(reqId) || "";
          state.agentBuf.set(reqId, prev + String(msg.content || ""));
          return;
        }
        if (msg.type === "done") {
          const merged = (state.agentBuf.get(reqId) || "") + (msg.response || "");
          state.agentBuf.delete(reqId);
          appendLog(logEl, `agent done[${reqId}]: ${merged}`);
        }
      };
    }

    function sendChat(payload) {
      if (!state.chatWs || state.chatWs.readyState !== WebSocket.OPEN) {
        appendLog(logEl, "chat ws not connected");
        return;
      }
      state.chatWs.send(JSON.stringify(payload));
      appendLog(logEl, `chat => ${JSON.stringify(payload)}`);
    }

    function sendAgent(payload) {
      if (!state.agentWs || state.agentWs.readyState !== WebSocket.OPEN) {
        appendLog(logEl, "agent ws not connected");
        return;
      }
      state.agentWs.send(JSON.stringify(payload));
      appendLog(logEl, `agent => ${JSON.stringify(payload)}`);
    }

    $("btnConnectChat").onclick = connectChat;
    $("btnConnectAgent").onclick = connectAgent;
    $("btnPingChat").onclick = () => sendChat({ type: "ping", request_id: rid("c-ping") });
    $("btnPingAgent").onclick = () => sendAgent({ type: "ping", request_id: rid("a-ping") });

    $("btnAddFriend").onclick = async () => {
      try {
        const httpBase = $("httpBase").value.trim();
        const imei = $("imei").value.trim();
        const peer = $("peerImei").value.trim();
        const out = await postJson(`${httpBase}/api/friends/add`, { imei, friendImei: peer });
        appendLog(logEl, `http add_friend <= ${JSON.stringify(out)}`);
      } catch (e) {
        appendLog(logEl, `http add_friend err: ${e.message || e}`);
      }
    };

    $("btnPrivateMsg").onclick = () => {
      const peer = $("peerImei").value.trim();
      const txt = $("privateMsg").value.trim();
      if (!peer || !txt) return;
      sendChat({
        type: "friend_message",
        targetImei: peer,
        content: txt,
        message_type: "text",
        message_id: rid("fm"),
      });
    };

    $("btnCreateGroup").onclick = async () => {
      try {
        const httpBase = $("httpBase").value.trim();
        const imei = $("imei").value.trim();
        const name = $("groupName").value.trim() || `group-${Date.now()}`;
        const members = $("defaultMembers")
          .value.split(",")
          .map((x) => x.trim())
          .filter(Boolean);
        const out = await postJson(`${httpBase}/api/groups/create`, {
          imei,
          name,
          memberImeis: members,
          assistantEnabled: true,
        });
        appendLog(logEl, `http create_group <= ${JSON.stringify(out)}`);
        if (out && out.groupId) $("groupId").value = out.groupId;
      } catch (e) {
        appendLog(logEl, `http create_group err: ${e.message || e}`);
      }
    };

    $("btnGroupMsg").onclick = () => {
      const gid = $("groupId").value.trim();
      const txt = $("groupMsg").value.trim();
      if (!gid || !txt) return;
      sendChat({
        type: "group_message",
        groupId: gid,
        content: txt,
        message_type: "text",
      });
    };

    $("btnCreateAgent").onclick = () => {
      const agentId = $("agentId").value.trim();
      const prompt = $("agentPrompt").value.trim();
      const skills = $("skillsInclude")
        .value.split(",")
        .map((x) => x.trim())
        .filter(Boolean);
      const payload = {
        type: "create_agent",
        request_id: rid("create"),
        agent_id: agentId,
        default_prompt: prompt,
        skills_include: skills,
      };
      const imeiId = $("imeiId").value.trim();
      if (imeiId) payload.imei_id = imeiId;
      sendAgent(payload);
    };

    $("btnDeleteAgent").onclick = () => {
      const agentId = $("agentId").value.trim();
      if (!agentId) {
        appendLog(logEl, "delete_agent: 请填写 Agent ID");
        return;
      }
      if (agentId.toLowerCase() === "default") {
        appendLog(logEl, "delete_agent: 不能删除内置 default");
        return;
      }
      if (!window.confirm(`确定删除 Agent「${agentId}」？`)) return;
      const payload = {
        type: "delete_agent",
        request_id: rid("delete"),
        agent_id: agentId,
      };
      const imeiId = $("imeiId").value.trim();
      if (imeiId) payload.imei_id = imeiId;
      sendAgent(payload);
    };

    $("btnAgentChat").onclick = () => {
      const agentId = $("agentId").value.trim();
      const threadId = $("threadId").value.trim();
      const msg = $("agentMsg").value.trim();
      if (!agentId || !threadId || !msg) return;
      const payload = {
        type: "chat",
        request_id: rid("chat"),
        agent_id: agentId,
        thread_id: threadId,
        message: msg,
        images: [],
      };
      const imeiId = $("imeiId").value.trim();
      if (imeiId) payload.imei_id = imeiId;
      sendAgent(payload);
    };
  }

  window.initMockClient = initMockClient;
})();
