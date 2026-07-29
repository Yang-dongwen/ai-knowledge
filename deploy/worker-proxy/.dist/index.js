// src/index.js
var index_default = {
  async fetch(request, env) {
    const originHost = env.ORIGIN_HOST || "13.201.82.24";
    const originPort = env.ORIGIN_PORT || "8088";
    const inbound = new URL(request.url);
    const target = new URL(request.url);
    target.protocol = "http:";
    target.hostname = originHost;
    target.port = String(originPort);
    const headers = new Headers(request.headers);
    headers.delete("host");
    headers.set("Host", `${originHost}:${originPort}`);
    headers.set("X-Forwarded-Proto", "https");
    headers.set("X-Forwarded-Host", inbound.host);
    headers.set("X-Forwarded-For", request.headers.get("CF-Connecting-IP") || "");
    headers.set("X-Real-IP", request.headers.get("CF-Connecting-IP") || "");
    const init = {
      method: request.method,
      headers,
      redirect: "manual",
      // Workers 透传 body（含 POST / SSE 上行）
      body: ["GET", "HEAD"].includes(request.method) ? void 0 : request.body
    };
    let resp;
    try {
      resp = await fetch(target.toString(), init);
    } catch (err) {
      return new Response(
        JSON.stringify({
          error: "origin_unreachable",
          message: String(err && err.message ? err.message : err),
          target: target.origin
        }),
        {
          status: 502,
          headers: { "content-type": "application/json; charset=utf-8" }
        }
      );
    }
    const outHeaders = new Headers(resp.headers);
    const loc = outHeaders.get("Location");
    if (loc) {
      try {
        const u = new URL(loc, target);
        if (u.hostname === originHost || u.hostname === "127.0.0.1") {
          u.protocol = "https:";
          u.hostname = inbound.hostname;
          u.port = "";
          outHeaders.set("Location", u.toString());
        }
      } catch {
      }
    }
    return new Response(resp.body, {
      status: resp.status,
      statusText: resp.statusText,
      headers: outHeaders
    });
  }
};
export {
  index_default as default
};
//# sourceMappingURL=index.js.map
