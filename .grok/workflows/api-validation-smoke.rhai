let meta = #{
    name: "api-validation-smoke",
    description: "Static smoke audit of backend APIs + frontend clients: validation, auth, FE/BE contract, risk report",
    when_to_use: "When checking whether backend endpoints and frontend callers have solid input/auth validation",
    phases: [
        #{ title: "Inventory", detail: "Map controllers, DTOs, FE API clients, security config" },
        #{ title: "ModuleAudit", detail: "Parallel per-module validation and contract review" },
        #{ title: "CrossCut", detail: "Global exception, security filter, public routes, FE request layer" },
        #{ title: "Verify", detail: "Independent adversarial verify per finding" },
        #{ title: "Synthesize", detail: "Diff summary + risk assessment report" },
    ],
};

// --- schemas ---
let inventory_schema = #{
    "type": "object", "required": ["modules", "notes"],
    "properties": #{
        "modules": #{ "type": "array", "maxItems": 16, "items": #{
            "type": "object", "required": ["name", "controller_paths", "endpoint_count", "dto_paths", "fe_api_paths"],
            "properties": #{
                "name": #{ "type": "string" },
                "controller_paths": #{ "type": "array", "items": #{ "type": "string" }, "maxItems": 12 },
                "endpoint_count": #{ "type": "integer" },
                "dto_paths": #{ "type": "array", "items": #{ "type": "string" }, "maxItems": 20 },
                "fe_api_paths": #{ "type": "array", "items": #{ "type": "string" }, "maxItems": 12 },
            },
        }},
        "notes": #{ "type": "string" },
    },
};

let findings_schema = #{
    "type": "object", "required": ["domain", "findings"],
    "properties": #{
        "domain": #{ "type": "string" },
        "findings": #{ "type": "array", "maxItems": 6, "items": #{
            "type": "object",
            "required": ["id", "severity", "category", "file", "endpoint_or_field", "issue", "evidence", "suggested_fix"],
            "properties": #{
                "id": #{ "type": "string" },
                "severity": #{ "type": "string" },
                "category": #{ "type": "string" },
                "file": #{ "type": "string" },
                "endpoint_or_field": #{ "type": "string" },
                "issue": #{ "type": "string" },
                "evidence": #{ "type": "string" },
                "suggested_fix": #{ "type": "string" },
            },
        }},
        "coverage_note": #{ "type": "string" },
    },
};

let verdict_schema = #{
    "type": "object", "required": ["finding_id", "real", "reason", "evidence"],
    "properties": #{
        "finding_id": #{ "type": "string" },
        "real": #{ "type": "boolean" },
        "reason": #{ "type": "string" },
        "evidence": #{ "type": "string" },
        "severity_adjusted": #{ "type": "string" },
    },
};

let report_schema = #{
    "type": "object",
    "required": ["title", "executive_summary", "diff_summary", "risk_assessment", "confirmed_findings", "module_coverage"],
    "properties": #{
        "title": #{ "type": "string" },
        "executive_summary": #{ "type": "string" },
        "diff_summary": #{ "type": "string" },
        "risk_assessment": #{ "type": "string" },
        "module_coverage": #{ "type": "string" },
        "confirmed_findings": #{ "type": "array", "maxItems": 24, "items": #{
            "type": "object",
            "required": ["id", "severity", "domain", "category", "file", "issue"],
            "properties": #{
                "id": #{ "type": "string" },
                "severity": #{ "type": "string" },
                "domain": #{ "type": "string" },
                "category": #{ "type": "string" },
                "file": #{ "type": "string" },
                "endpoint_or_field": #{ "type": "string" },
                "issue": #{ "type": "string" },
                "evidence": #{ "type": "string" },
                "suggested_fix": #{ "type": "string" },
                "verify_note": #{ "type": "string" },
            },
        }},
        "dropped_or_false_positives": #{ "type": "integer" },
        "recommended_next_steps": #{ "type": "array", "items": #{ "type": "string" }, "maxItems": 10 },
    },
};

// --- helpers ---
fn trimmed(s) {
    if type_of(s) == "string" {
        s.trim();
        s
    } else {
        ""
    }
}

fn severity_rank(s) {
    let t = trimmed(s);
    if t == "critical" { 0 } else if t == "high" { 1 } else if t == "medium" { 2 } else if t == "low" { 3 } else { 4 }
}

// --- args ---
// Optional: args.modules = ["auth","kb"] to limit; args.max_findings = N (default 24)
let filter_modules = ();
let max_findings = 24;
if args != () {
    if args.modules != () { filter_modules = args.modules; }
    if args.max_findings != () { max_findings = args.max_findings; }
}

// Deterministic work-list (script-owned scope; agents must not invent modules outside this set)
let all_modules = [
    "auth",
    "kb",
    "chat",
    "video",
    "aigen",
    "article",
    "imggen",
    "member",
    "pay",
];

let modules = [];
if filter_modules == () {
    for m in all_modules { modules.push(m); }
} else {
    for m in all_modules {
        let keep = false;
        for f in filter_modules {
            if f == m { keep = true; }
        }
        if keep { modules.push(m); }
    }
    if modules.len() == 0 {
        pause("verification", "args.modules matched none of: auth,kb,chat,video,aigen,article,imggen,member,pay");
    }
}

log("api-validation-smoke: modules=" + modules.len().to_string() + " max_findings=" + max_findings.to_string());

// =============================================================================
// Phase 1: Inventory — one agent maps endpoints/DTOs/FE clients (context for auditors)
// =============================================================================
phase("Inventory");

let inv_prompt = "";
inv_prompt += "You are inventorying APIs for static smoke audit in repo auto-exchange. ";
inv_prompt += "Use ONLY read-only tools: list_dir, grep, read_file. Do NOT answer from memory. ";
inv_prompt += "Backend root: okx-bot/src/main/java/com/dwcode/okxbot/ ";
inv_prompt += "Frontend API roots: okx-trading-web/src/api/, kb-mobile/src/, kb-miniprogram/utils/ and pages/. ";
inv_prompt += "Also skim: okx-bot security filter/config under auth/security and common/config; GlobalExceptionHandler. ";
inv_prompt += "Modules to cover exactly (no others): ";
for m in modules {
    inv_prompt += m + " ";
}
inv_prompt += ". For each module: list controller file paths, rough endpoint count from @*Mapping, key DTO paths, matching FE api client paths. ";
inv_prompt += "Return structured modules array + short notes about auth patterns observed. Empty fe_api_paths is OK if none exist after search.";

let inv = agent(inv_prompt, #{
    label: "inventory",
    capability_mode: "read-only",
    output_schema: inventory_schema,
});

let inventory_notes = "";
if inv != () && inv.success && inv.output != () {
    if inv.output.notes != () { inventory_notes = inv.output.notes; }
    log("inventory ok");
} else {
    log("inventory failed or empty — auditors will discover paths themselves");
}

// Module path hints (deterministic anchors so auditors do not wander)
let module_hints = #{
    auth: "controllers: okx-bot/.../auth/controller/*, auth/oauth/OAuthController; DTOs: auth/dto/*; FE: okx-trading-web/src/api/auth.api.ts, admin.api.ts; also wx mini auth in kb-miniprogram/utils/auth.js",
    kb: "controllers: okx-bot/.../kb/controller/*; DTOs: kb/dto/*; FE: okx-trading-web/src/api/kb.api.ts, kb-mobile/src/api.js, kb-miniprogram pages+utils/request.js",
    chat: "controller: okx-bot/.../chat/controller/ChatController; DTOs: chat/dto/*; FE: okx-trading-web/src/api/chat.api.ts",
    video: "controller: okx-bot/.../video/controller/VideoProcessController; DTOs: video/dto/*; FE: okx-trading-web/src/api/video.api.ts + video.events.ts",
    aigen: "controller: okx-bot/.../aigen/controller/AigenTaskController; DTOs: aigen/dto/*; validators: aigen/service/*Validate*; FE: okx-trading-web/src/api/aigen.api.ts",
    article: "controller: okx-bot/.../article/controller/ArticleTaskController; DTOs: article/dto/*; security: article/security/*; FE: okx-trading-web/src/api/article.api.ts",
    imggen: "controller: okx-bot/.../imggen/controller/ImgGenTaskController; DTOs: imggen/dto/*; FE: okx-trading-web/src/api/imggen.api.ts",
    member: "controller: okx-bot/.../member/controller/MemberController; FE: okx-trading-web/src/api/member.api.ts",
    pay: "controllers: okx-bot/.../pay/controller/PayController, PayNotifyController; DTOs: pay/dto/*; FE: search member/pay related views and apis",
};

// =============================================================================
// Phase 2: ModuleAudit — parallel one auditor per module
// =============================================================================
phase("ModuleAudit");

let audit_jobs = [];
for m in modules {
    let hint = "";
    if m == "auth" { hint = module_hints.auth; }
    else if m == "kb" { hint = module_hints.kb; }
    else if m == "chat" { hint = module_hints.chat; }
    else if m == "video" { hint = module_hints.video; }
    else if m == "aigen" { hint = module_hints.aigen; }
    else if m == "article" { hint = module_hints.article; }
    else if m == "imggen" { hint = module_hints.imggen; }
    else if m == "member" { hint = module_hints.member; }
    else if m == "pay" { hint = module_hints.pay; }

    let p = "";
    p += "STATIC API SMOKE AUDIT for module `" + m + "` in auto-exchange. ";
    p += "Read-only only (read_file, grep, list_dir). You MUST open real files before reporting. ";
    p += "Path anchors: " + hint + ". ";
    p += "Inventory context notes: " + inventory_notes + ". ";
    p += "Check these categories and report gaps as findings (max 6, highest severity first):\n";
    p += "1) INPUT VALIDATION: @Valid on @RequestBody; Bean Validation on DTO fields; path/query params (@PathVariable/@RequestParam) bounds; missing validation on create/update; oversized strings; enum/status ranges.\n";
    p += "2) AUTH / AUTHZ: which routes need auth; ownership checks (IDOR risk: taskId/noteId/orderNo belonging to other users); admin-only endpoints; public share tokens; notify webhooks signature verify.\n";
    p += "3) FE/BE CONTRACT: FE api client fields vs backend DTO required fields; wrong methods/paths; missing error handling on 400/401/403; token attach on protected routes.\n";
    p += "4) ERROR / EDGE: empty body, wrong content-type handling, null IDs, pagination limits, file upload size/type if applicable.\n";
    p += "5) TESTS: note if unit/integration tests cover validation for this module under okx-bot/src/test — lack of tests is at most low/medium, not critical by itself.\n";
    p += "Severity: critical (auth bypass/IDOR/payment forge), high (missing validation on sensitive write), medium (partial gaps), low (style/consistency).\n";
    p += "Each finding: short id like " + m + "-01, severity, category (validation|auth|fe_contract|error_edge|test_gap), file path, endpoint_or_field, concrete issue, evidence (quote symbols/lines you read), suggested_fix.\n";
    p += "coverage_note: what you actually opened. Empty findings ONLY if you inspected controllers+DTOs and found no material gaps — state that in coverage_note.";
    p += " domain field in output must be exactly `" + m + "`.";

    audit_jobs.push(#{
        prompt: p,
        label: "audit:" + m,
        capability_mode: "read-only",
        output_schema: findings_schema,
    });
}

let audit_results = parallel(audit_jobs);

// Collect findings; enforce module scope in plain Rhai
let raw_findings = [];
let i = 0;
for r in audit_results {
    let expected_mod = modules[i];
    if r != () && r.success && r.output != () && r.output.findings != () {
        for f in r.output.findings {
            // scope guard: drop findings that clearly reference another top-level module path incorrectly is hard;
            // require id prefix or set module
            let item = #{
                id: if f.id != () { f.id } else { expected_mod + "-x" },
                severity: if f.severity != () { f.severity } else { "medium" },
                category: if f.category != () { f.category } else { "validation" },
                domain: expected_mod,
                file: if f.file != () { f.file } else { "" },
                endpoint_or_field: if f.endpoint_or_field != () { f.endpoint_or_field } else { "" },
                issue: if f.issue != () { f.issue } else { "" },
                evidence: if f.evidence != () { f.evidence } else { "" },
                suggested_fix: if f.suggested_fix != () { f.suggested_fix } else { "" },
            };
            if trimmed(item.issue) != "" && trimmed(item.file) != "" {
                raw_findings.push(item);
            }
        }
    } else {
        log("audit failed or empty for " + expected_mod);
    }
    i += 1;
}

log("raw findings: " + raw_findings.len().to_string());

// =============================================================================
// Phase 3: CrossCut — global security / exception / FE request layer
// =============================================================================
phase("CrossCut");

let cross_prompt = "";
cross_prompt += "CROSS-CUTTING static smoke audit for auto-exchange APIs. Read-only tools only; open real files.\n";
cross_prompt += "Inspect:\n";
cross_prompt += "- okx-bot GlobalExceptionHandler (validation/business/unknown errors)\n";
cross_prompt += "- Spring security / filter / WebConfig / auth security packages (which paths public vs authenticated)\n";
cross_prompt += "- okx-trading-web/src/api/request.ts (interceptors, token, error mapping)\n";
cross_prompt += "- kb-mobile and kb-miniprogram request helpers if present\n";
cross_prompt += "- Pay notify endpoints authenticity; public share routes\n";
cross_prompt += "Report max 6 findings with same schema fields. domain must be `crosscut`. ";
cross_prompt += "Focus on systemic gaps (e.g. stack traces leaked, CORS, CSRF, missing rate limit on send-code, unauthenticated admin). ";
cross_prompt += "Do not re-list module-specific DTO field gaps already obvious per-controller.";

let cross = agent(cross_prompt, #{
    label: "audit:crosscut",
    capability_mode: "read-only",
    output_schema: findings_schema,
});

if cross != () && cross.success && cross.output != () && cross.output.findings != () {
    for f in cross.output.findings {
        let item = #{
            id: if f.id != () { f.id } else { "crosscut-x" },
            severity: if f.severity != () { f.severity } else { "medium" },
            category: if f.category != () { f.category } else { "auth" },
            domain: "crosscut",
            file: if f.file != () { f.file } else { "" },
            endpoint_or_field: if f.endpoint_or_field != () { f.endpoint_or_field } else { "" },
            issue: if f.issue != () { f.issue } else { "" },
            evidence: if f.evidence != () { f.evidence } else { "" },
            suggested_fix: if f.suggested_fix != () { f.suggested_fix } else { "" },
        };
        if trimmed(item.issue) != "" && trimmed(item.file) != "" {
            raw_findings.push(item);
        }
    }
} else {
    log("crosscut audit failed or empty");
}

// Cap findings before verify (budget headroom): keep highest severity first via simple selection
// Sort by rank using insertion into buckets
let bucket_c = [];
let bucket_h = [];
let bucket_m = [];
let bucket_l = [];
let bucket_o = [];
for f in raw_findings {
    let rnk = severity_rank(f.severity);
    if rnk == 0 { bucket_c.push(f); }
    else if rnk == 1 { bucket_h.push(f); }
    else if rnk == 2 { bucket_m.push(f); }
    else if rnk == 3 { bucket_l.push(f); }
    else { bucket_o.push(f); }
}

let ranked = [];
for f in bucket_c { ranked.push(f); }
for f in bucket_h { ranked.push(f); }
for f in bucket_m { ranked.push(f); }
for f in bucket_l { ranked.push(f); }
for f in bucket_o { ranked.push(f); }

let to_verify = [];
let dropped_cap = 0;
let idx = 0;
for f in ranked {
    if idx < max_findings {
        to_verify.push(f);
    } else {
        dropped_cap += 1;
    }
    idx += 1;
}
if dropped_cap > 0 {
    log("capped findings: dropped " + dropped_cap.to_string() + " lower-severity items beyond max_findings");
}

if to_verify.len() == 0 {
    let empty_report = "# API Validation Smoke Report\n\nNo findings after module + cross-cut audit (or all audits failed).\n";
    empty_report += "Modules scanned: " + modules.len().to_string() + "\n";
    empty_report += "Inventory notes: " + inventory_notes + "\n";
    let path = write_scratch_file("api-validation-smoke-report.md", empty_report);
    complete(#{
        path: path,
        summary: "No findings",
        confirmed: [],
        dropped_or_false_positives: 0,
        modules: modules.len(),
    });
}

// =============================================================================
// Phase 4: Verify — independent adversarial agent per finding
// =============================================================================
phase("Verify");

let vjobs = [];
for f in to_verify {
    let issue_json = json_encode(f.issue);
    let file_json = json_encode(f.file);
    let ep_json = json_encode(f.endpoint_or_field);
    let ev_json = json_encode(f.evidence);
    let vp = "";
    vp += "Adversarially VERIFY this static-audit finding. Default real=false unless you independently prove it.\n";
    vp += "finding_id: " + f.id + "\n";
    vp += "domain: " + f.domain + " severity_claimed: " + f.severity + " category: " + f.category + "\n";
    vp += "file: " + file_json + "\n";
    vp += "endpoint_or_field: " + ep_json + "\n";
    vp += "issue: " + issue_json + "\n";
    vp += "claimed_evidence: " + ev_json + "\n";
    vp += "Instructions: open the cited file and related controller/DTO/security with read_file/grep. ";
    vp += "Set real=true ONLY if the gap is still present with concrete evidence you re-read (quote symbols). ";
    vp += "If validation exists elsewhere (service layer, security filter, FE-only cosmetic), set real=false. ";
    vp += "If real but severity wrong, set severity_adjusted. reason must explain the decision. evidence must be your independent quote.";

    vjobs.push(#{
        prompt: vp,
        label: "verify:" + f.id,
        capability_mode: "read-only",
        output_schema: verdict_schema,
    });
}

let verdicts = parallel(vjobs);

let confirmed = [];
let false_pos = 0;
let unverified = 0;
let vi = 0;
for v in verdicts {
    let base = to_verify[vi];
    if v == () || !v.success || v.output == () {
        // fail closed: no usable verify = not confirmed
        unverified += 1;
    } else if v.output.real == true && v.output.evidence != () && trimmed(v.output.evidence) != "" {
        let sev = base.severity;
        if v.output.severity_adjusted != () && trimmed(v.output.severity_adjusted) != "" {
            sev = v.output.severity_adjusted;
        }
        confirmed.push(#{
            id: base.id,
            severity: sev,
            domain: base.domain,
            category: base.category,
            file: base.file,
            endpoint_or_field: base.endpoint_or_field,
            issue: base.issue,
            evidence: v.output.evidence,
            suggested_fix: base.suggested_fix,
            verify_note: if v.output.reason != () { v.output.reason } else { "" },
        });
    } else {
        false_pos += 1;
    }
    vi += 1;
}

log("verified: " + confirmed.len().to_string()
    + " confirmed / " + false_pos.to_string()
    + " false_pos / " + unverified.to_string()
    + " unverified of " + to_verify.len().to_string());

// =============================================================================
// Phase 5: Synthesize — report with diff summary + risk assessment
// =============================================================================
phase("Synthesize");

let conf_json = json_encode(confirmed);
let modules_json = json_encode(modules);
let syn = "";
syn += "Synthesize the FINAL report for API validation smoke audit of auto-exchange.\n";
syn += "You may re-open a few key files for precise citations if needed (read-only).\n";
syn += "Modules scanned: " + modules_json + "\n";
syn += "Inventory notes: " + inventory_notes + "\n";
syn += "Dropped by cap before verify: " + dropped_cap.to_string() + "\n";
syn += "False positives rejected: " + false_pos.to_string() + "; unverified (no usable skeptic): " + unverified.to_string() + "\n";
syn += "CONFIRMED findings JSON:\n" + conf_json + "\n";
syn += "Write fields:\n";
syn += "- title\n";
syn += "- executive_summary (≤200 words)\n";
syn += "- diff_summary: treat as gap-vs-ideal checklist — what is missing vs solid validation/auth/FE contract baseline; group by module; call out positive controls already present\n";
syn += "- risk_assessment: overall risk level, top attack/abuse scenarios, business impact (auth, pay, data leak, IDOR), priority order to fix\n";
syn += "- module_coverage: which modules audited and relative depth\n";
syn += "- confirmed_findings: pass through (may lightly polish wording, do not invent new)\n";
syn += "- dropped_or_false_positives: " + (false_pos + unverified + dropped_cap).to_string() + "\n";
syn += "- recommended_next_steps: 3-8 concrete actions\n";
syn += "Do not invent findings not in CONFIRMED JSON.";

let report_agent = agent(syn, #{
    label: "synthesize-report",
    capability_mode: "read-only",
    output_schema: report_schema,
});

// Build markdown report regardless of schema agent success
let md = "";
md += "# API Validation Smoke Report\n\n";
md += "_Workflow: api-validation-smoke (static analysis; not live HTTP)_\n\n";

if report_agent != () && report_agent.success && report_agent.output != () {
    let o = report_agent.output;
    if o.title != () { md += "## " + o.title + "\n\n"; }
    if o.executive_summary != () {
        md += "## Executive summary\n\n" + o.executive_summary + "\n\n";
    }
    if o.diff_summary != () {
        md += "## Diff summary (gap vs ideal)\n\n" + o.diff_summary + "\n\n";
    }
    if o.risk_assessment != () {
        md += "## Risk assessment\n\n" + o.risk_assessment + "\n\n";
    }
    if o.module_coverage != () {
        md += "## Module coverage\n\n" + o.module_coverage + "\n\n";
    }
    md += "## Confirmed findings (" + confirmed.len().to_string() + ")\n\n";
    for f in confirmed {
        md += "### [" + f.severity + "] " + f.id + " — " + f.domain + " / " + f.category + "\n\n";
        md += "- **File:** `" + f.file + "`\n";
        md += "- **Endpoint/field:** " + f.endpoint_or_field + "\n";
        md += "- **Issue:** " + f.issue + "\n";
        md += "- **Evidence:** " + f.evidence + "\n";
        md += "- **Suggested fix:** " + f.suggested_fix + "\n";
        if f.verify_note != () && trimmed(f.verify_note) != "" {
            md += "- **Verify:** " + f.verify_note + "\n";
        }
        md += "\n";
    }
    md += "## Stats\n\n";
    md += "- Confirmed: " + confirmed.len().to_string() + "\n";
    md += "- False positives / unverified / capped: " + false_pos.to_string() + " / " + unverified.to_string() + " / " + dropped_cap.to_string() + "\n\n";
    if o.recommended_next_steps != () {
        md += "## Recommended next steps\n\n";
        for s in o.recommended_next_steps {
            md += "- " + s + "\n";
        }
        md += "\n";
    }
} else {
    log("synthesize agent failed — writing fallback report from confirmed list");
    md += "## Executive summary\n\n";
    md += "Synthesizer agent failed; listing " + confirmed.len().to_string() + " adversarially confirmed findings only.\n\n";
    md += "## Diff summary (gap vs ideal)\n\n";
    md += "See confirmed findings below. Modules scanned: " + modules_json + ".\n\n";
    md += "## Risk assessment\n\n";
    md += "Manual triage required. Count by severity in findings list.\n\n";
    md += "## Confirmed findings\n\n";
    for f in confirmed {
        md += "### [" + f.severity + "] " + f.id + " — " + f.domain + "\n\n";
        md += "- **File:** `" + f.file + "`\n";
        md += "- **Issue:** " + f.issue + "\n";
        md += "- **Evidence:** " + f.evidence + "\n";
        md += "- **Fix:** " + f.suggested_fix + "\n\n";
    }
}

let report_path = write_scratch_file("api-validation-smoke-report.md", md);

complete(#{
    path: report_path,
    summary: confirmed.len().to_string() + " confirmed findings",
    confirmed_count: confirmed.len(),
    false_positives: false_pos,
    unverified: unverified,
    dropped_by_cap: dropped_cap,
    modules: modules,
});
