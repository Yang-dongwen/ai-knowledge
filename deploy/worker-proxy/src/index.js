/**
 * Cloudflare Worker 反代：workers.dev → 源站 HTTPS（Quick Tunnel / 公网）
 *
 * 优先 ORIGIN_BASE（https://xxx.trycloudflare.com 或 http://IP:8088）
 * 注意：部分账号/区域对 Worker 直连裸 IP 会异常，优先用 tunnel HTTPS。
 */
export default {
  async fetch(request, env) {
    const inbound = new URL(request.url);

    let originBase = (env.ORIGIN_BASE || "").replace(/\/$/, "");
    if (!originBase) {
      const host = env.ORIGIN_HOST || "13.201.82.24";
      const port = env.ORIGIN_PORT || "8088";
      originBase = `http://${host}:${port}`;
    }

    const target = new URL(inbound.pathname + inbound.search, originBase + "/");

    const headers = new Headers(request.headers);
    headers.delete("host");
    // 源站为 tunnel 时用其 Host；裸 IP 时用 host:port
    try {
      const o = new URL(originBase);
      headers.set(
        "Host",
        o.port && o.port !== "80" && o.port !== "443"
          ? `${o.hostname}:${o.port}`
          : o.hostname
      );
    } catch {
      /* ignore */
    }
    headers.set("X-Forwarded-Proto", "https");
    headers.set("X-Forwarded-Host", inbound.host);
    const cip = request.headers.get("CF-Connecting-IP") || "";
    if (cip) {
      headers.set("X-Forwarded-For", cip);
      headers.set("X-Real-IP", cip);
    }

    const init = {
      method: request.method,
      headers,
      redirect: "manual",
      body: ["GET", "HEAD"].includes(request.method) ? undefined : request.body,
    };

    let resp;
    try {
      resp = await fetch(target.toString(), init);
    } catch (err) {
      return new Response(
        JSON.stringify({
          error: "origin_unreachable",
          message: String(err && err.message ? err.message : err),
          target: target.origin,
        }),
        {
          status: 502,
          headers: { "content-type": "application/json; charset=utf-8" },
        }
      );
    }

    const outHeaders = new Headers(resp.headers);
    const loc = outHeaders.get("Location");
    if (loc) {
      try {
        const u = new URL(loc, target);
        const o = new URL(originBase);
        if (u.hostname === o.hostname || u.hostname === "127.0.0.1") {
          u.protocol = "https:";
          u.hostname = inbound.hostname;
          u.port = "";
          outHeaders.set("Location", u.toString());
        }
      } catch {
        /* ignore */
      }
    }

    return new Response(resp.body, {
      status: resp.status,
      statusText: resp.statusText,
      headers: outHeaders,
    });
  },
};
