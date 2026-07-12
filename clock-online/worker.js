// clock-online Worker
// 功能：统计"最近 90 秒内有心跳的设备数" = 实时在线人数
// 设备通过 GET /heartbeat?id=<uuid> 上报心跳，响应里直接返回当前在线人数

// 全局在线计数器（用一个固定的 Durable Object 实例，所有设备共享）
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const id = env.COUNTER.idFromName("global");
    const stub = env.COUNTER.get(id);
    return stub.fetch(request);
  },
};

export class OnlineCounter {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const now = Date.now();
    const WINDOW = 90_000; // 90 秒窗口

    let map = (await this.state.storage.get("map")) || {};

    if (url.pathname === "/heartbeat") {
      const id = url.searchParams.get("id");
      if (!id) {
        return json({ error: "missing id" }, 400);
      }
      map[id] = now;
    } else if (url.pathname === "/count") {
      // 仅查询，不写入
    } else {
      return json({ error: "not found" }, 404);
    }

    // 清理过期设备
    const cutoff = now - WINDOW;
    for (const k of Object.keys(map)) {
      if (map[k] < cutoff) delete map[k];
    }
    await this.state.storage.put("map", map);

    const online = Object.keys(map).length;
    return json({ online, ts: now });
  }
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "access-control-allow-origin": "*",
      "cache-control": "no-store",
    },
  });
}
